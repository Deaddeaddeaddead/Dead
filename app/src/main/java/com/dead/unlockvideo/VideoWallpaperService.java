package com.dead.unlockvideo;

import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.File;

/**
 * Видеообои. Вперёд всегда играет оригинальный файл (полное качество). Для плавного
 * возврата к началу есть второй плеер с обратным видео: он включается только на время
 * перемотки назад, рендер переключается между ними без мигания.
 */
public class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "VideoWallpaper";
    /** Ближе к концу, чем на столько, — считаем, что видео стоит в конце. */
    private static final int END_EPSILON_MS = 80;
    /** positionMs == AT_END — стоим на последнем кадре (длительность ещё неизвестна). */
    private static final int AT_END = Integer.MAX_VALUE;
    /** Как часто проверять разблокировку, пока виден экран блокировки. */
    private static final long UNLOCK_POLL_MS = 100;
    /** Опрос экрана лаунчера при возврате на рабочий стол. */
    private static final long HOME_PROBE_MS = 40;
    /** Дольше не опрашиваем: рабочий стол к этому времени давно виден. */
    private static final long HOME_PROBE_LIMIT_MS = 1500;
    /** Короче этого масштаб не делаем — иначе выглядит как скачок. */
    private static final int MIN_ZOOM_MS = 150;
    /** Параллакс на 100%: дополнительный масштаб, дающий запас для сдвига. */
    private static final float PARALLAX_MAX_ZOOM = 0.12f;
    /** Поворот (рад), при котором сдвиг доходит до края запаса. */
    private static final float PARALLAX_MAX_ANGLE = 0.3f;
    /** За сколько секунд смещение примерно возвращается к центру, если телефон не крутить. */
    private static final float PARALLAX_RECENTER_S = 1.5f;
    private static final float PARALLAX_SMOOTHING = 0.25f;

    private static int engineCount;
    private static Handler probeHandler;

    /**
     * Поток опроса лаунчера: во время анимации закрытия приложения ответ лаунчера идёт до
     * секунды — главный поток обоев ждать его не должен.
     */
    private static synchronized Handler probeHandler() {
        if (probeHandler == null) {
            HandlerThread thread = new HandlerThread("launcher-probe");
            thread.start();
            probeHandler = new Handler(thread.getLooper());
        }
        return probeHandler;
    }

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private final class VideoEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener,
            LauncherState.Listener {

        /** Серия ждёт запуска по событию. */
        private static final int PHASE_WAITING = 0;
        /** Серия идёт (может стоять на паузе, пока обои не видны). */
        private static final int PHASE_PLAYING = 1;
        /** Серия закончилась; на экране последний или первый кадр. */
        private static final int PHASE_DONE = 2;

        private final Handler handler = new Handler(Looper.getMainLooper());

        /** Номер экземпляра — на некоторых прошивках экран блокировки рисует отдельный. */
        private final int engineId = ++engineCount;

        private void log(String event) {
            EventLog.add(VideoWallpaperService.this, "[#" + engineId + " " + where() + "] " + event);
        }

        /** Где показаны эти обои: рабочий стол, экран блокировки или превью. */
        private String where() {
            if (isPreview()) return "превью";
            int flags = getWallpaperFlags();
            boolean home = (flags & android.app.WallpaperManager.FLAG_SYSTEM) != 0;
            boolean lock = (flags & android.app.WallpaperManager.FLAG_LOCK) != 0;
            return home && lock ? "стол+блок" : lock ? "блокировка" : home ? "стол" : "?" + flags;
        }

        private String state() {
            return "фаза=" + new String[] {"ждёт", "играет", "доиграло"}[phase]
                    + " позиция=" + forwardPosition() + "мс"
                    + (needReverse ? " нужен_реверс" : "")
                    + (reversing ? " идёт_реверс" : "");
        }
        private SharedPreferences sp;
        private Prefs cfg;
        private PowerManager power;
        private KeyguardManager keyguard;
        private WallpaperRenderer renderer;

        /** Вперёд: оригинал видео или сцена «Стекло». */
        private Clip forward;
        private boolean forwardReady;
        /** Назад: обратное видео или та же сцена задом наперёд — только для возврата. */
        private Clip backward;
        private boolean backwardReady;
        /** Реверс выбран в настройках и готов (у видео — обратный файл, у сцены — всегда). */
        private boolean reverseAvailable;
        /** Режим «Стекло»: сцена из кода вместо видео. */
        private boolean glass;
        /** Последний кадр оригинала; позиция r в обратном видео = lastFrameMs - r вперёд. */
        private int lastFrameMs;

        private boolean visible;
        private int phase;
        private int playsLeft;
        /** Позиция в оригинале, пока плеер не готов (или AT_END). */
        private int positionMs;
        /** Был запуск по событию; серия стартует, как только станет можно. */
        private boolean armed;
        /** Режим «Только один раз», и видео уже отыграло. */
        private boolean finishedForever;
        /** После блокировки нужно отмотать назад, когда появится экран блокировки. */
        private boolean needReverse;
        /** Сейчас играет обратное видео. */
        private boolean reversing;
        /** Обои закрыло приложение (а не выключение экрана). */
        private boolean hiddenByApp;
        /**
         * Телефон разблокирован: личность подтверждена (палец, лицо, код) или
         * пришло событие разблокировки. Сбрасывается при выключении экрана.
         */
        private boolean unlocked;
        /** Экран блокировки убран (свайпом или сразу при разблокировке пальцем). */
        private boolean keyguardGone;
        /** Подготовка к выключению экрана уже сделана (до следующего включения). */
        private boolean screenOffHandled;
        /** На AOD видео доигрывает вперёд до конца («рабочий стол»). */
        private boolean aodForward;
        /** При включении экрана мгновенно вернуться к началу. */
        private boolean rewindOnScreenOn;
        /**
         * Был ли телефон заблокирован (требовал проверки личности), когда включился экран.
         * Если нет (отсрочка блокировки, Smart Lock), разблокировкой считаем только уход
         * экрана блокировки — иначе реверс не успел бы показаться.
         */
        private boolean deviceLockedAtWake = true;
        /** Экран выключался — при пробуждении нужно заново запомнить состояние блокировки. */
        private boolean sampleOnWake;
        /**
         * Вернулись из приложения: видео уже играет, а опрос лаунчера проверяет, не список ли
         * это недавних (тогда пауза).
         */
        private boolean recentsWatch;
        /** Видео на паузе, потому что открыт список недавних; на рабочем столе — продолжить. */
        private boolean pausedInRecents;
        /** Когда обои стали видны после приложения — старые наблюдения лаунчера не в счёт. */
        private long visibleSince;
        private long lastHomeStart;
        /**
         * После возврата из приложения раз в HOME_PROBE_MS смотрим экран лаунчера, пока не
         * станет ясно: рабочий стол — видео играет дальше; список недавних (или лаунчер без
         * рабочего стола) — пауза. События лаунчера приходят только после анимации, опрос
         * видит его раньше.
         */
        /** Номер текущего наблюдения — ответы от прошлых не применяем. */
        private int probeGen;
        private final Runnable probeLoop = new Runnable() {
            @Override
            public void run() {
                RecentsDetectorService detector = RecentsDetectorService.instance;
                if (!recentsWatch || !visible || detector == null) return;
                int gen = probeGen;
                long started = SystemClock.uptimeMillis();
                probeHandler().post(() -> {
                    int seen = detector.probe();
                    String marker = detector.lastMarker;
                    handler.post(() -> onProbe(gen, seen, marker, started));
                });
            }
        };

        private void onProbe(int gen, int seen, String marker, long started) {
            if (gen != probeGen || !recentsWatch || !visible) return;
            long now = SystemClock.uptimeMillis();
            String when = (now - visibleSince) + "мс (опрос " + (now - started) + "мс)";
            if (LauncherState.changedAt() > started
                    && LauncherState.state() != LauncherState.UNKNOWN) {
                // Пока шёл опрос, лаунчер сам сообщил, что на экране, — это свежее.
                seen = LauncherState.state();
                marker = "событие лаунчера";
            }
            if (seen == LauncherState.HOME) {
                recentsWatch = false;
                log("рабочий стол через " + when);
                return;
            }
            if (seen == LauncherState.RECENTS || seen == LauncherState.OTHER) {
                pauseForRecents("через " + when + ", признак «" + marker + "»");
                return;
            }
            if (now - visibleSince < HOME_PROBE_LIMIT_MS) {
                handler.postDelayed(probeLoop, HOME_PROBE_MS);
            } else {
                recentsWatch = false;
            }
        }

        /** Цвета обоев для системы (часы, палитра) — по неподвижному кадру. */
        private volatile WallpaperColors colors;
        private int surfaceWidth;
        private int surfaceHeight;
        /** Номер последнего запроса неподвижного кадра — старые результаты не применяем. */
        private volatile int stillRequest;
        /** Для какого кадра покоя (последнего или первого) посчитаны цвета. */
        private Boolean colorsAtEnd;

        /** Пока открыто приложение, видео уже перемотано в начало к возврату. */
        private boolean preparedForHome;
        private int homeRestorePosition;

        // Эффекты
        /** Текущий масштаб от эффекта разблокировки. */
        private float unlockScale = 1f;
        // Плавный переход масштаба (и, при выключении экрана, возврат параллакса в центр).
        // Свой таймер вместо ValueAnimator: в AOD кадры экрана идут редко, а время — нет.
        private boolean zoomAnimating;
        private float zoomFrom;
        private float zoomTo;
        private long zoomStart;
        private int zoomDuration;
        /** Переход масштаба как есть — для рендера и расчёта текущего масштаба. */
        private WallpaperRenderer.ScaleAnimation zoomAnimation;
        /** Вместе с масштабом плавно вернуть параллакс в центр. */
        private boolean tiltToCenter;
        private float tiltFromX;
        private float tiltFromY;
        // Сам масштаб считает рендер на каждом кадре (WallpaperRenderer.animateScale) — ровно и
        // в такт анимации; здесь — возврат параллакса в центр и конец перехода.
        private final Runnable zoomStep = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                float t = Math.min(1f, Math.max(0f, (now - zoomStart) / (float) Math.max(1,
                        zoomDuration)));
                float eased = zoomAnimation.eased(t);
                unlockScale = zoomFrom + (zoomTo - zoomFrom) * eased;
                if (tiltToCenter) {
                    tiltX = tiltFromX * (1f - eased);
                    tiltY = tiltFromY * (1f - eased);
                    applyTransform();
                }
                if (t < 1f) {
                    handler.postDelayed(this, 16);
                } else {
                    unlockScale = zoomTo;
                    zoomAnimating = false;
                    tiltToCenter = false;
                    applyTransform();
                }
            }
        };
        private SensorManager sensorManager;
        private Sensor gyroscope;
        private boolean gyroscopeOn;
        private long lastGyroNs;
        /** Накопленный поворот (рад) — плавно возвращается к нулю. */
        private float angleX;
        private float angleY;
        /** Сглаженный сдвиг, -1..1. */
        private float tiltX;
        private float tiltY;

        private final SensorEventListener gyroListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (lastGyroNs != 0) {
                    float dt = (event.timestamp - lastGyroNs) * 1e-9f;
                    if (dt > 0f && dt < 0.1f) {
                        // Поворот вокруг вертикальной оси двигает изображение вбок,
                        // вокруг горизонтальной — вверх-вниз.
                        angleX += event.values[1] * dt;
                        angleY += event.values[0] * dt;
                        float decay = (float) Math.exp(-dt / PARALLAX_RECENTER_S);
                        angleX = clampAngle(angleX * decay);
                        angleY = clampAngle(angleY * decay);
                    }
                }
                lastGyroNs = event.timestamp;
                tiltX += (angleX / PARALLAX_MAX_ANGLE - tiltX) * PARALLAX_SMOOTHING;
                tiltY += (angleY / PARALLAX_MAX_ANGLE - tiltY) * PARALLAX_SMOOTHING;
                applyTransform();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        /** Пока виден экран блокировки, следим за разблокировкой (лицо, задержки системы). */
        private final Runnable unlockWatch = new Runnable() {
            @Override
            public void run() {
                if (!visible) return;
                if (!unlocked && unlockedNow()) onUnlocked();
                if (!keyguardGone && !keyguard.isKeyguardLocked()) onKeyguardGone();
                if (!unlocked || !keyguardGone) handler.postDelayed(this, UNLOCK_POLL_MS);
            }
        };

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    onScreenOn();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    onUnlocked();
                    onKeyguardGone();
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            Context context = VideoWallpaperService.this;
            power = context.getSystemService(PowerManager.class);
            keyguard = context.getSystemService(KeyguardManager.class);
            sp = Prefs.prefs(context);
            sp.registerOnSharedPreferenceChangeListener(this);
            LauncherState.addListener(this);
            // Таблица света стекла — 128 КБ из assets и шейдеры сцен, один раз на процесс.
            GlassScene.load(context);
            ProceduralScene.load(context);
            LightScene.load(context);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);

            loadConfig();
            unlocked = unlockedNow();
            keyguardGone = isPreview() || !keyguard.isKeyguardLocked();
            sensorManager = context.getSystemService(SensorManager.class);
            gyroscope = sensorManager == null ? null
                    : sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            unlockScale = isPreview() ? previewScale() : unlocked ? homeScale() : lockScale();
            phase = finishedForever ? PHASE_DONE : PHASE_WAITING;
            log("обои запущены, реверс " + (reverseAvailable ? "готов" : "недоступен"));
            positionMs = finishedForever && cfg.endOnLastFrame && cfg.lockAction == Prefs.LOCK_KEEP
                    ? AT_END : 0;
            // Превью сразу показывает, как играет видео; настоящие обои ждут события.
            armed = isPreview();
        }

        @Override
        public void onDestroy() {
            log("обои закрыты");
            handler.removeCallbacksAndMessages(null);
            cancelUnlockZoom();
            setGyroscope(false);
            sp.unregisterOnSharedPreferenceChangeListener(this);
            LauncherState.removeListener(this);
            VideoWallpaperService.this.unregisterReceiver(screenReceiver);
            closePlayers();
            releaseRenderer();
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceWidth = width;
            surfaceHeight = height;
            if (renderer == null) {
                renderer = WallpaperRenderer.create(holder.getSurface(), width, height);
                if (renderer != null) {
                    renderer.setErrorListener(message -> handler.post(() -> log(message)));
                }
                if (isPreview()) {
                    log("превью: поверхность " + width + "×" + height + ", флаги "
                            + getWallpaperFlags());
                }
                applyTransform();
                loadStill(true);
                openPlayers();
            } else {
                renderer.setViewSize(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            closePlayers();
            releaseRenderer();
            super.onSurfaceDestroyed(holder);
        }

        // ---- События ----

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) sampleWakeState();
            log((isVisible ? "обои видны" : "обои скрыты") + " (заблокировано="
                    + !unlockedNow() + ") " + state());
            if (!isVisible) {
                handler.removeCallbacks(unlockWatch);
                setGyroscope(false);
                recentsWatch = false;
                pausedInRecents = false;
                hiddenByApp = power.isInteractive() && unlocked;
                if (!power.isInteractive()) goingOff("обои скрыты при гаснущем экране");
                pauseAll();
                if (hiddenByApp && cfg.startOnHome && !finishedForever && !isPreview()) {
                    // Обои закрыло приложение — перематываем в начало сейчас, пока этого
                    // никто не видит: при возврате видео просто начнёт играть, без рывка.
                    homeRestorePosition = forwardPosition();
                    preparedForHome = true;
                    seekForward(0);
                    log("приложение открыто — видео перемотано в начало к возврату");
                }
                return;
            }
            if (unlocked && !isPreview() && keyguard.isKeyguardLocked() && !unlockedNow()) {
                // Экран блокировки снова на месте (например, вышли из его редактора, куда
                // входили с разблокировкой): видео снова ждёт разблокировки.
                log("экран блокировки снова показан — ждём разблокировки");
                unlocked = false;
                keyguardGone = false;
            }
            boolean backFromApp = hiddenByApp;
            hiddenByApp = false;
            watchUnlock();
            setGyroscope(cfg.parallax > 0);
            // Появился экран блокировки — масштаб плавно возвращается к его состоянию.
            if (!unlocked) zoomToLock();
            if (backFromApp && unlocked && cfg.startOnHome && !isPreview()) {
                // Старт сразу, как только приложение начало закрываться. Если служба видит
                // экран лаунчера — следим, не список ли это недавних (тогда пауза).
                startFromHome();
                if (!cfg.startInRecents && LauncherState.serviceRunning()) {
                    recentsWatch = true;
                    probeGen++;
                    visibleSince = SystemClock.uptimeMillis();
                    handler.removeCallbacks(probeLoop);
                    handler.postDelayed(probeLoop, HOME_PROBE_MS);
                }
            } else if (!unlocked && needReverse) {
                startReverse();
            } else {
                resume();
            }
        }

        /**
         * Запуск при возврате на рабочий стол. Если видео запустилось так меньше 1,5 с назад
         * (обои на мгновение скрылись при переходе One UI), не перезапускаем его с начала.
         */
        private void startFromHome() {
            preparedForHome = false;
            pausedInRecents = false;
            long now = SystemClock.uptimeMillis();
            if (phase == PHASE_PLAYING && now - lastHomeStart < 1500) {
                log("возврат на рабочий стол: видео уже идёт, продолжаем");
                resume();
                return;
            }
            lastHomeStart = now;
            log("запуск: возврат на рабочий стол");
            triggerForward(true);
        }

        /** Служба специальных возможностей сообщила, что теперь на экране лаунчера. */
        @Override
        public void onLauncherState(int state) {
            if (!visible) return;
            if (state == LauncherState.HOME && pausedInRecents) {
                // Из списка недавних — на рабочий стол: продолжаем с того же кадра.
                pausedInRecents = false;
                log("из списка недавних на рабочий стол — продолжаем");
                resume();
            } else if (state == LauncherState.RECENTS && recentsWatch) {
                pauseForRecents("по событию лаунчера");
            }
        }

        /** Открыт список недавних — видео на паузу до рабочего стола. */
        private void pauseForRecents(String why) {
            recentsWatch = false;
            pausedInRecents = true;
            handler.removeCallbacks(probeLoop);
            if (forward != null && forwardReady && forward.isPlaying()) forward.pause();
            log("список недавних — пауза (" + why + ")");
        }

        /** Команды системы: о засыпании Samsung сообщает раньше события выключения экрана. */
        @Override
        public android.os.Bundle onCommand(String action, int x, int y, int z,
                                           android.os.Bundle extras, boolean resultRequested) {
            if ("android.wallpaper.goingtosleep".equals(action)) {
                goingOff("команда засыпания");
            } else if ("android.wallpaper.wakingup".equals(action)) {
                // Засыпание могло не состояться (экран сразу включили) — следующее учтём.
                screenOffHandled = false;
            }
            return null;
        }

        private void onScreenOff() {
            log("экран выключен (событие), " + state());
            goingOff("событие выключения экрана");
        }

        /**
         * Экран гаснет (или уходит в AOD). Вызывается по самому раннему из сигналов —
         * смена состояния дисплея, скрытие обоев, событие выключения — и только один раз.
         */
        private void goingOff(String reason) {
            if (screenOffHandled) return;
            screenOffHandled = true;
            log("подготовка к выключению экрана: " + reason + "; настройки: AOD="
                    + new String[] {"как есть", "блокировка", "рабочий стол"}[cfg.screenOffPose]
                    + ", при блокировке="
                    + new String[] {"оставить", "к началу", "реверсом"}[cfg.lockAction]
                    + (cfg.lockAction == Prefs.LOCK_REVERSE && !reverseAvailable
                            ? " (реверс не готов)" : "")
                    + ", повторов=" + (cfg.infinite() ? "∞" : String.valueOf(cfg.plays)));
            unlocked = isPreview();
            keyguardGone = isPreview();
            sampleOnWake = true;
            handler.removeCallbacks(unlockWatch);
            hiddenByApp = false;
            recentsWatch = false; // ожидание рабочего стола не переживает сон
            pausedInRecents = false;
            if (preparedForHome) {
                // Блокировка прямо из приложения: незаметно возвращаем прежний кадр,
                // чтобы AOD и реверс шли от него, как раньше.
                preparedForHome = false;
                if (!visible) seekForward(homeRestorePosition);
            }
            armed = isPreview();
            // Обои видны и на AOD, поэтому переходим плавно: масштаб — к выбранному
            // для AOD, параллакс — в центр. Гироскоп выключаем (батарея).
            float target = cfg.screenOffPose == Prefs.POSE_LOCK ? lockScale()
                    : cfg.screenOffPose == Prefs.POSE_HOME ? homeScale() : unlockScale;
            animateTransform(target, cfg.zoomDurationMs, true);
            setGyroscope(false);
            pauseAll();
            // Прерванная серия сбрасывается — дальше ждём нового запуска.
            if (phase == PHASE_PLAYING && cfg.lockAction != Prefs.LOCK_KEEP) {
                phase = PHASE_WAITING;
            }

            // Кадр на AOD (обои видны и там): «рабочий стол» — доигрываем вперёд до конца,
            // а возврат к началу будет на экране блокировки.
            if (cfg.screenOffPose == Prefs.POSE_HOME && !cfg.infinite()) {
                needReverse = cfg.lockAction == Prefs.LOCK_REVERSE && reverseAvailable;
                rewindOnScreenOn = cfg.lockAction == Prefs.LOCK_REWIND;
                playToEndOnAod();
                return;
            }
            boolean atStart = forwardPosition() <= 30;
            if (cfg.lockAction == Prefs.LOCK_KEEP || atStart) {
                log("AOD: кадр не меняем" + (atStart ? " (уже в начале)" : ""));
                return;
            }
            if (cfg.lockAction == Prefs.LOCK_REVERSE && reverseAvailable) {
                needReverse = true;
                if (cfg.screenOffPose != Prefs.POSE_LOCK) {
                    log("AOD: кадр не меняем, реверс — на экране блокировки");
                }
                if (cfg.screenOffPose == Prefs.POSE_LOCK) {
                    // «Блокировка»: реверс играет прямо на AOD.
                    startReverse();
                }
                // Иначе — на экране блокировки, когда он появится.
            } else {
                // Мгновенно — к включению на экране первый кадр.
                seekForward(0);
            }
        }

        /** На AOD доигрываем видео вперёд до конца (состояние рабочего стола). */
        private void playToEndOnAod() {
            if (forward == null || !forwardReady) return;
            if (forwardPosition() >= forwardEndMs() - END_EPSILON_MS) {
                log("AOD: видео уже в конце (состояние рабочего стола)");
                if (!cfg.endOnLastFrame) seekForward(0);
                return;
            }
            aodForward = true;
            log("▶ AOD: доигрываем до конца с " + forward.getCurrentPosition() + "мс");
            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
            forward.start();
        }

        private void onScreenOn() {
            screenOffHandled = false;
            if (aodForward) {
                // Экран включили раньше, чем видео доиграло на AOD — останавливаемся здесь.
                aodForward = false;
                if (forward != null && forwardReady && forward.isPlaying()) forward.pause();
            }
            if (rewindOnScreenOn) {
                rewindOnScreenOn = false;
                seekForward(0);
            }
            sampleWakeState();
            log("экран включён (заблокировано=" + !unlockedNow() + ") " + state());
            // Например, разблокировали лицом ещё до того, как экран включился полностью.
            if (!unlocked && unlockedNow()) {
                onUnlocked();
                return;
            }
            watchUnlock();
            setGyroscope(visible && cfg.parallax > 0);
            if (visible && !unlocked) zoomToLock();
            if (startOnScreenOn()) {
                log("запуск: включение экрана");
                triggerForward(false);
            } else if (visible && !unlocked && needReverse) {
                startReverse();
            }
        }

        /**
         * Разблокировали (событие системы или подтверждение личности на экране блокировки):
         * реверс, если идёт, останавливается, дальше видео идёт вперёд.
         */
        private void onUnlocked() {
            log("разблокировано" + (unlocked ? " (повторно)" : "") + ", " + state());
            if (unlocked) {
                resume();
                return;
            }
            unlocked = true;
            watchUnlock(); // дальше ждём, когда уберётся экран блокировки
            if (reversing) stopReverse();
            if (!cfg.zoomOnDismiss) zoomToHome();
            if (cfg.startOnUnlock) {
                log("запуск: разблокировка");
                triggerForward(false);
            } else {
                if (needReverse) {
                    // Назад не успели — просто к началу.
                    needReverse = false;
                    seekForward(0);
                }
                resume();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (isPreview()) {
                // Превью — неподвижный кадр: обновляем его и масштаб.
                if (Prefs.KEY_REVISION.equals(key) || Prefs.KEY_PLAY_NOW.equals(key)) {
                    loadConfig();
                    cancelUnlockZoom();
                    unlockScale = previewScale();
                    setGyroscope(visible && cfg.parallax > 0);
                    applyTransform();
                    loadStill(true);
                }
                return;
            }
            if (Prefs.KEY_REVISION.equals(key)) {
                // Новые настройки, текущее воспроизведение сохраняем. Плееры пересоздаём,
                // только если поменялось, есть ли обратное видео.
                Prefs next = Prefs.load(VideoWallpaperService.this);
                boolean sourceChanged = next.source != cfg.source;
                if (sourceChanged || reverseFor(next) != reverseAvailable
                        || (next.glass() && (next.glassDurationMs != cfg.glassDurationMs
                                || next.sceneSmooth != cfg.sceneSmooth))) {
                    closePlayers();
                }
                if (sourceChanged) {
                    // Другой источник — с кадра покоя, без запуска.
                    positionMs = 0;
                    needReverse = false;
                    if (phase == PHASE_PLAYING) phase = PHASE_WAITING;
                }
                loadConfig();
                if (renderer != null) {
                    renderer.setScene(cfg.scene, glass ? ScenePresets.params(cfg) : null);
                }
                log("настройки обновлены, реверс " + (reverseAvailable ? "готов" : "недоступен"));
                if (finishedForever) phase = PHASE_DONE;
                if (needReverse && !reverseAvailable) {
                    needReverse = false;
                    seekForward(0);
                }
                if (forward == null) {
                    openPlayers();
                } else {
                    forward.setVolume(cfg.sound ? 1f : 0f);
                    forward.setLooping(cfg.infinite());
                }
                cancelUnlockZoom();
                unlockScale = unlocked ? homeScale() : lockScale();
                setGyroscope(visible && cfg.parallax > 0);
                applyTransform();
                // Кадр покоя или источник сменился — пересчитать цвета для системы.
                if (glass || sourceChanged || colorsAtEnd == null || colorsAtEnd != restAtEnd()) {
                    loadStill(sourceChanged);
                }
            } else if (Prefs.KEY_PLAY_NOW.equals(key)) {
                // «Проиграть сейчас» или новое видео: с начала, как только обои станут видны.
                closePlayers();
                loadConfig();
                phase = PHASE_WAITING;
                positionMs = 0;
                needReverse = false;
                armed = true;
                openPlayers();
                loadStill(false); // новое видео — новые цвета
            }
        }

        /** Кадр покоя: последний, если видео так и остаётся в конце, иначе первый. */
        private boolean restAtEnd() {
            return cfg.lockAction == Prefs.LOCK_KEEP && cfg.endOnLastFrame && !cfg.infinite();
        }

        /** Превью только экрана блокировки — с его масштабом, остальные — рабочего стола. */
        private float previewScale() {
            int flags = getWallpaperFlags();
            boolean lockOnly = (flags & WallpaperManager.FLAG_LOCK) != 0
                    && (flags & WallpaperManager.FLAG_SYSTEM) == 0;
            return lockOnly ? lockScale() : homeScale();
        }

        /**
         * Неподвижный кадр в фоне: в превью он и есть изображение, у обоев — виден до первого
         * кадра плеера (show), если видео стоит в начале или в конце.
         * По кадру покоя считаются цвета обоев для системы.
         */
        private void loadStill(boolean show) {
            WallpaperRenderer target = renderer;
            if (glass) {
                // Сцена рисуется сразу; в превью — неподвижный кадр покоя.
                stillRequest++;
                if (target != null) {
                    target.setScene(cfg.scene, ScenePresets.params(cfg));
                    if (isPreview()) {
                        float rest = restAtEnd() ? 1f : 0f;
                        target.setSceneSource(WallpaperRenderer.SOURCE_FORWARD,
                                new WallpaperRenderer.SceneSource() {
                                    @Override
                                    public float progress() {
                                        return rest;
                                    }

                                    @Override
                                    public boolean animating() {
                                        return false;
                                    }
                                });
                    }
                }
                int[] tones = ScenePresets.systemColors(cfg);
                colors = new WallpaperColors(Color.valueOf(tones[0]), Color.valueOf(tones[1]),
                        Color.valueOf(tones[2]));
                colorsAtEnd = null;
                notifyColorsChanged();
                return;
            }
            if (target != null) target.setScene(cfg.scene, null);
            boolean restEnd = restAtEnd();
            // Превью — кадр покоя; обои — кадр, с которого пойдёт видео.
            boolean showEnd = isPreview() ? restEnd : positionMs == AT_END;
            boolean visibleStill = show && target != null
                    && (isPreview() || positionMs == 0 || positionMs == AT_END);
            int width = surfaceWidth;
            int height = surfaceHeight;
            int request = ++stillRequest;
            Context context = VideoWallpaperService.this;
            new Thread(() -> {
                long start = SystemClock.uptimeMillis();
                if (!Stills.ensure(context)) return;
                Bitmap rest = Stills.load(restEnd ? Stills.last(context) : Stills.first(context),
                        width, height);
                if (rest == null || request != stillRequest) return;
                WallpaperColors computed = WallpaperColors.fromBitmap(rest);
                Bitmap shown = null;
                if (visibleStill) {
                    shown = showEnd == restEnd ? rest : Stills.load(
                            showEnd ? Stills.last(context) : Stills.first(context), width, height);
                }
                if (shown != rest) rest.recycle();
                Bitmap still = shown;
                long took = SystemClock.uptimeMillis() - start;
                handler.post(() -> {
                    if (request != stillRequest) {
                        if (still != null) still.recycle();
                        return;
                    }
                    if (still != null && renderer == target && target != null) {
                        target.showStill(still);
                        if (isPreview()) {
                            log("превью: неподвижный кадр (" + (showEnd ? "последний" : "первый")
                                    + ") за " + took + "мс");
                        }
                    } else if (still != null) {
                        still.recycle();
                    }
                    colors = computed;
                    colorsAtEnd = restEnd;
                    notifyColorsChanged();
                });
            }, "wallpaper-still").start();
        }

        @Override
        public WallpaperColors onComputeColors() {
            return colors;
        }

        private void loadConfig() {
            Context context = VideoWallpaperService.this;
            cfg = Prefs.load(context);
            glass = cfg.glass();
            reverseAvailable = reverseFor(cfg);
            // Позиция r назад = lastFrameMs − r вперёд; у сцены назад та же длительность.
            lastFrameMs = glass ? Easing.sceneDuration(cfg.glassDurationMs, cfg.sceneSmooth)
                    : cfg.reverseLastMs;
            finishedForever = !isPreview() && cfg.once && cfg.finished;
        }

        /** Будет ли реверс с такими настройками. */
        private boolean reverseFor(Prefs prefs) {
            return prefs.glass() ? prefs.wantsReverse() : reverseUsable(prefs);
        }

        private boolean reverseUsable(Prefs prefs) {
            return prefs.wantsReverse() && prefs.reverseReady && prefs.reverseLastMs > 0
                    && Prefs.reverseFile(VideoWallpaperService.this).isFile();
        }

        // ---- Эффекты ----

        private float lockScale() {
            return cfg.unlockZoom == Prefs.ZOOM_OUT ? 1f + cfg.unlockZoomAmount / 100f : 1f;
        }

        private float homeScale() {
            return cfg.unlockZoom == Prefs.ZOOM_IN ? 1f + cfg.unlockZoomAmount / 100f : 1f;
        }

        private void applyTransform() {
            if (renderer == null) return;
            // Эффекты складываются, а не перемножаются: размах покачивания зависит
            // только от силы параллакса и не растёт от масштаба при разблокировке.
            float parallaxZoom = 1f + PARALLAX_MAX_ZOOM * cfg.parallax / 100f;
            float parallaxMargin = (1f - 1f / parallaxZoom) / 2f;
            renderer.setTransform(parallaxZoom - 1f, tiltX * parallaxMargin,
                    tiltY * parallaxMargin);
            if (zoomAnimating) {
                renderer.animateScale(zoomAnimation);
            } else {
                renderer.setScale(unlockScale);
            }
        }

        /** Масштаб от разблокировки сейчас — с учётом идущего перехода. */
        private float currentScale() {
            return zoomAnimating ? zoomAnimation.at(SystemClock.uptimeMillis()) : unlockScale;
        }

        /**
         * Сколько длится масштаб при разблокировке: «как у видео» — пока видео играет
         * вперёд (с текущего кадра до конца), иначе — своя длительность.
         */
        private int unlockZoomDuration() {
            if (cfg.zoomSync && cfg.startOnUnlock && !finishedForever && forwardReady) {
                int end = forwardEndMs();
                int position = forwardPosition();
                int remaining = position >= end - END_EPSILON_MS ? end : end - position;
                return Math.max(MIN_ZOOM_MS, remaining);
            }
            return cfg.zoomDurationMs;
        }

        /** На экране блокировки: «как у видео» — пока идёт реверс, иначе — своя длительность. */
        private int lockZoomDuration() {
            if (cfg.zoomSync && needReverse && reverseAvailable && !startOnScreenOn()) {
                return Math.max(MIN_ZOOM_MS, forwardPosition());
            }
            return cfg.zoomDurationMs;
        }

        private void animateUnlockZoom(float target, int durationMs) {
            animateTransform(target, durationMs, false, 0f, 0f);
        }

        /**
         * К масштабу рабочего стола при разблокировке. У сцены с масштабом «как у видео» — в
         * такт её анимации вперёд: с текущего места до конца.
         */
        private void zoomToHome() {
            int duration = unlockZoomDuration();
            if (glass && cfg.zoomSync && cfg.startOnUnlock && !finishedForever && forwardReady) {
                int end = Math.max(1, forwardEndMs());
                int position = forwardPosition();
                float from = position >= end - END_EPSILON_MS ? 0f : position / (float) end;
                animateTransform(homeScale(), duration, false, from, 1f);
            } else {
                animateUnlockZoom(homeScale(), duration);
            }
        }

        /** К масштабу экрана блокировки; у сцены «как у видео» — в такт обратному ходу. */
        private void zoomToLock() {
            int duration = lockZoomDuration();
            if (glass && cfg.zoomSync && needReverse && reverseAvailable && !startOnScreenOn()) {
                float from = forwardPosition() / (float) Math.max(1, forwardEndMs());
                animateTransform(lockScale(), duration, false, Math.min(1f, from), 0f);
            } else {
                animateUnlockZoom(lockScale(), duration);
            }
        }

        private void animateTransform(float target, int durationMs, boolean centerTilt) {
            animateTransform(target, durationMs, centerTilt, 0f, 0f);
        }

        /**
         * Плавно к масштабу target; с centerTilt — заодно вернуть параллакс в центр. Если время
         * сцены идёт от sceneFrom к sceneTo (sceneFrom != sceneTo) — в такт её анимации, иначе
         * мягко сам по себе.
         */
        private void animateTransform(float target, int durationMs, boolean centerTilt,
                float sceneFrom, float sceneTo) {
            if (zoomAnimating && Math.abs(zoomTo - target) < 0.001f && !centerTilt) return;
            cancelUnlockZoom();
            boolean tiltMoved = centerTilt && (Math.abs(tiltX) > 0.001f || Math.abs(tiltY) > 0.001f);
            if (Math.abs(unlockScale - target) < 0.001f && !tiltMoved) return;
            zoomFrom = unlockScale;
            zoomTo = target;
            zoomStart = SystemClock.uptimeMillis();
            zoomDuration = durationMs;
            zoomAnimation = new WallpaperRenderer.ScaleAnimation(zoomFrom, zoomTo, zoomStart,
                    durationMs, sceneFrom, sceneTo, cfg.sceneSmooth);
            tiltToCenter = tiltMoved;
            tiltFromX = tiltX;
            tiltFromY = tiltY;
            zoomAnimating = true;
            applyTransform();
            handler.post(zoomStep);
        }

        /** Переход масштаба прерван — масштаб остаётся, где был. */
        private void cancelUnlockZoom() {
            if (zoomAnimating) unlockScale = currentScale();
            handler.removeCallbacks(zoomStep);
            zoomAnimating = false;
            tiltToCenter = false;
            if (renderer != null) renderer.setScale(unlockScale);
        }

        /** Гироскоп работает, только пока обои видны и параллакс включён. */
        private void setGyroscope(boolean on) {
            if (on == gyroscopeOn || gyroscope == null) return;
            gyroscopeOn = on;
            if (on) {
                lastGyroNs = 0;
                sensorManager.registerListener(gyroListener, gyroscope,
                        SensorManager.SENSOR_DELAY_GAME);
            } else {
                sensorManager.unregisterListener(gyroListener);
                angleX = angleY = 0f;
                if (!tiltToCenter) {
                    tiltX = tiltY = 0f;
                    applyTransform();
                }
            }
        }

        private float clampAngle(float angle) {
            return Math.max(-PARALLAX_MAX_ANGLE, Math.min(PARALLAX_MAX_ANGLE, angle));
        }

        // ---- Логика воспроизведения ----

        /**
         * Разблокирован ли телефон прямо сейчас. С защищённой блокировкой — подтверждена ли
         * личность (при разблокировке лицом экран блокировки ещё может быть на месте),
         * без защиты — убран ли экран блокировки.
         */
        private boolean unlockedNow() {
            if (isPreview()) return true;
            if (!keyguard.isKeyguardLocked()) return true;
            // Экран блокировки ещё показан: разблокировано, только если личность
            // подтвердили уже на нём (например, лицом).
            return cfg.unlockOnLockscreen && keyguard.isKeyguardSecure() && deviceLockedAtWake
                    && !keyguard.isDeviceLocked();
        }

        /** Запоминает при пробуждении, требовал ли телефон проверки личности. */
        private void sampleWakeState() {
            if (!sampleOnWake) return;
            sampleOnWake = false;
            deviceLockedAtWake = keyguard.isDeviceLocked();
            log("пробуждение: экран_блокировки=" + keyguard.isKeyguardLocked()
                    + " нужна_проверка=" + deviceLockedAtWake);
            if (!isPreview() && !keyguard.isKeyguardLocked()) wakeWithoutLock();
        }

        /**
         * Экран включился без экрана блокировки (телефон не успел заблокироваться): реверса
         * нет, перезапуска с начала нет. Если видео не в конце — плавно вперёд с этого кадра.
         */
        private void wakeWithoutLock() {
            log("пробуждение без экрана блокировки — реверса нет");
            needReverse = false;
            rewindOnScreenOn = false;
            unlocked = true;
            keyguardGone = true;
            handler.removeCallbacks(unlockWatch);
            animateUnlockZoom(homeScale(), cfg.zoomDurationMs);
            if (cfg.startOnUnlock && forwardReady
                    && forwardPosition() < forwardEndMs() - END_EPSILON_MS) {
                log("запуск: вперёд с текущего кадра");
                triggerForward(false);
            }
        }

        private void watchUnlock() {
            handler.removeCallbacks(unlockWatch);
            if (visible && (!unlocked || !keyguardGone)) {
                handler.postDelayed(unlockWatch, UNLOCK_POLL_MS);
            }
        }

        /** Экран блокировки убран — начинается переход на рабочий стол. */
        private void onKeyguardGone() {
            if (keyguardGone) return;
            keyguardGone = true;
            log("экран блокировки убран");
            if (cfg.zoomOnDismiss) zoomToHome();
        }

        /**
         * Играть ли вперёд прямо на экране блокировки. При возврате реверсом — нет:
         * там играет реверс, а вперёд — после разблокировки.
         */
        private boolean startOnScreenOn() {
            return cfg.startOnScreenOn
                    && !(cfg.lockAction == Prefs.LOCK_REVERSE && reverseAvailable);
        }

        /** Играть вперёд можно, когда обои видны и телефон разблокирован (или так настроено). */
        private boolean canPlayForward() {
            return visible && (unlocked || startOnScreenOn());
        }

        /**
         * Запуск по событию. Идущую серию продолжает, а с restart — начинает заново.
         * После прерванного реверса видео идёт вперёд с того же кадра.
         */
        private void triggerForward(boolean restart) {
            if (finishedForever) return;
            if (reversing) stopReverse();
            needReverse = false;
            if (phase == PHASE_PLAYING && !restart) {
                resume();
                return;
            }
            if (forward != null && forwardReady && forward.isPlaying()) forward.pause();
            boolean atEnd = forwardPosition() >= forwardEndMs() - END_EPSILON_MS;
            if (restart || atEnd) seekForward(0);
            phase = PHASE_WAITING;
            armed = true;
            resume();
        }

        /** Играть вперёд, если всё позволяет. */
        private void resume() {
            if (forward == null || !forwardReady || reversing || phase == PHASE_DONE) return;
            if (pausedInRecents) return;
            if (!canPlayForward()) return;
            if (phase == PHASE_WAITING) {
                if (!armed) return;
                armed = false;
                phase = PHASE_PLAYING;
                playsLeft = cfg.plays;
            }
            if (!forward.isPlaying()) {
                log("▶ вперёд с " + forward.getCurrentPosition() + "мс");
                renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
                forward.start();
            }
        }

        private void startReverse() {
            if (reversing) return;
            if (!reverseAvailable || backward == null || !backwardReady || !visible) {
                log("реверс не запущен: " + (!reverseAvailable ? "реверс не готов"
                        : backward == null || !backwardReady ? "плеер реверса не готов"
                        : "обои не видны"));
                return;
            }
            int position = forwardPosition();
            needReverse = false;
            if (position <= 0) return;
            reversing = true;
            log("◀ реверс с " + position + "мс (в обратном видео "
                    + (lastFrameMs - Math.min(position, lastFrameMs)) + "мс)");
            seek(backward, lastFrameMs - Math.min(position, lastFrameMs));
            backward.start();
            renderer.showSource(WallpaperRenderer.SOURCE_REVERSE);
        }

        /** Останавливает реверс; оригинал встаёт на тот же кадр. */
        private void stopReverse() {
            if (!reversing) return;
            reversing = false;
            int position = clamp(lastFrameMs - backward.getCurrentPosition());
            log("■ реверс остановлен на " + position + "мс");
            backward.pause();
            seekForward(position);
            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
        }

        private void onBackwardCompleted(Clip mp) {
            if (mp != backward || !reversing) return;
            log("■ реверс доиграл до начала");
            reversing = false;
            seekForward(0);
            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
        }

        private void onForwardCompleted(Clip mp) {
            if (mp != forward || cfg.infinite()) return;
            log("■ видео доиграло до конца");
            if (aodForward) {
                // Доиграли на AOD — это не новое проигрывание серии.
                aodForward = false;
                phase = PHASE_DONE;
                if (cfg.endOnLastFrame) {
                    positionMs = AT_END;
                } else {
                    seekForward(0);
                }
                return;
            }
            playsLeft--;
            if (playsLeft > 0) {
                mp.start(); // после окончания start() начинает с начала
                return;
            }
            phase = PHASE_DONE;
            if (cfg.once && !isPreview()) {
                finishedForever = true;
                sp.edit().putBoolean(Prefs.KEY_FINISHED, true).apply();
            }
            if (cfg.endOnLastFrame) {
                positionMs = AT_END; // на экране остаётся последний кадр
            } else {
                seekForward(0);
            }
        }

        private void pauseAll() {
            if (reversing) {
                stopReverse();
                // Реверс прервали — доиграем его при следующем появлении экрана блокировки.
                needReverse = forwardPosition() > 0;
            }
            if (forward != null && forwardReady && forward.isPlaying()) forward.pause();
        }

        private int forwardPosition() {
            if (reversing && backward != null) {
                return clamp(lastFrameMs - backward.getCurrentPosition());
            }
            if (forward != null && forwardReady) return forward.getCurrentPosition();
            return positionMs == AT_END ? forwardEndMs() : positionMs;
        }

        private int forwardEndMs() {
            if (forward != null && forwardReady && forward.getDuration() > 0) {
                return Math.max(0, forward.getDuration() - forward.endOffsetMs());
            }
            return lastFrameMs;
        }

        private void seekForward(int position) {
            positionMs = position;
            if (forward != null && forwardReady) seek(forward, position);
        }

        private int clamp(int position) {
            return Math.max(0, Math.min(lastFrameMs, position));
        }

        private void seek(Clip mp, int positionMs) {
            mp.seekTo(positionMs);
        }

        // ---- Плееры ----

        private void openPlayers() {
            // Превью — только неподвижный кадр: декодеры видео не занимаем, батарею не тратим.
            if (isPreview() || renderer == null || forward != null) return;
            Context context = VideoWallpaperService.this;
            if (glass) {
                forward = sceneClip(false, WallpaperRenderer.SOURCE_FORWARD);
                backward = reverseAvailable ? sceneClip(true, WallpaperRenderer.SOURCE_REVERSE)
                        : null;
            } else {
                File video = Prefs.videoFile(context);
                if (!video.isFile()) return;
                forward = createPlayer(video, WallpaperRenderer.SOURCE_FORWARD, cfg.sound);
                backward = reverseAvailable ? createPlayer(Prefs.reverseFile(context),
                        WallpaperRenderer.SOURCE_REVERSE, false) : null;
            }
            if (forward != null) {
                forward.setLooping(cfg.infinite());
                forward.setOnPreparedListener(mp -> {
                    if (mp != forward) return;
                    forwardReady = true;
                    int end = forwardEndMs();
                    seek(mp, positionMs == AT_END ? end : Math.min(positionMs, end));
                    if (visible && !unlocked && needReverse) {
                        startReverse();
                    } else {
                        resume();
                    }
                });
                forward.setOnCompletionListener(this::onForwardCompleted);
                forward.prepareAsync();
            }
            if (backward != null) {
                backward.setOnPreparedListener(mp -> {
                    if (mp != backward) return;
                    backwardReady = true;
                    if (visible && !unlocked && needReverse) startReverse();
                });
                backward.setOnCompletionListener(this::onBackwardCompleted);
                backward.prepareAsync();
            }
        }

        /** Часы сцены «Стекло» для источника; рендер рисует по ним кадры. */
        private Clip sceneClip(boolean reversed, int source) {
            SceneClip clip = new SceneClip(Easing.sceneDuration(cfg.glassDurationMs,
                    cfg.sceneSmooth), reversed, cfg.sceneSmooth);
            clip.setOnChange(() -> {
                if (renderer != null) renderer.requestSceneFrame();
            });
            renderer.setScene(cfg.scene, ScenePresets.params(cfg));
            renderer.setSceneSource(source, clip);
            return clip;
        }

        private Clip createPlayer(File file, int source, boolean sound) {
            MediaPlayer mp = new MediaPlayer();
            VideoClip clip = new VideoClip(mp);
            try {
                mp.setSurface(renderer.videoSurface(source));
                mp.setDataSource(file.getAbsolutePath());
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                float volume = sound ? 1f : 0f;
                mp.setVolume(volume, volume);
                mp.setOnVideoSizeChangedListener((p, width, height) -> {
                    if (renderer != null) renderer.setVideoSize(source, width, height);
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    Log.w(TAG, "MediaPlayer error " + what + "/" + extra + " source " + source);
                    log("ошибка плеера " + (source == WallpaperRenderer.SOURCE_REVERSE
                            ? "реверса" : "видео") + ": " + what + "/" + extra);
                    if (clip == backward) {
                        // Без реверса обойдёмся — возвращаем к началу мгновенно.
                        reversing = false;
                        reverseAvailable = false;
                        releaseBackward();
                        seekForward(0);
                        if (renderer != null) {
                            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
                        }
                    } else if (clip == forward) {
                        closePlayers();
                    }
                    return true;
                });
                return clip;
            } catch (Exception e) {
                Log.w(TAG, "Cannot open video", e);
                mp.release();
                return null;
            }
        }

        private void closePlayers() {
            if (forward != null || backward != null) {
                boolean wasReversing = reversing;
                positionMs = forwardPosition();
                reversing = false;
                if (wasReversing) needReverse = positionMs > 0;
            }
            if (forward != null) {
                forward.release();
                forward = null;
            }
            forwardReady = false;
            releaseBackward();
        }

        private void releaseBackward() {
            if (backward != null) {
                backward.release();
                backward = null;
            }
            backwardReady = false;
        }

        private void releaseRenderer() {
            if (renderer != null) {
                renderer.release();
                renderer = null;
            }
        }
    }
}
