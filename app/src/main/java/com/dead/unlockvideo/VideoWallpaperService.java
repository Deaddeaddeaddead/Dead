package com.dead.unlockvideo;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.animation.DecelerateInterpolator;

import java.io.File;

/**
 * Видеообои. Вперёд всегда играет оригинальный файл (полное качество). Для плавного
 * возврата к началу есть второй плеер с обратным видео: он включается только на время
 * перемотки назад, рендер переключается между ними без мигания.
 */
public class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "VideoWallpaper";
    /** Насколько приближаются обои при открытом приложении. */
    private static final float ZOOM_AMOUNT = 0.08f;
    private static final long RETURN_ZOOM_MS = 550;
    /** Ближе к концу, чем на столько, — считаем, что видео стоит в конце. */
    private static final int END_EPSILON_MS = 80;
    /** positionMs == AT_END — стоим на последнем кадре (длительность ещё неизвестна). */
    private static final int AT_END = Integer.MAX_VALUE;
    /** Как часто проверять разблокировку, пока виден экран блокировки. */
    private static final long UNLOCK_POLL_MS = 100;

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private final class VideoEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        /** Серия ждёт запуска по событию. */
        private static final int PHASE_WAITING = 0;
        /** Серия идёт (может стоять на паузе, пока обои не видны). */
        private static final int PHASE_PLAYING = 1;
        /** Серия закончилась; на экране последний или первый кадр. */
        private static final int PHASE_DONE = 2;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private SharedPreferences sp;
        private Prefs cfg;
        private PowerManager power;
        private KeyguardManager keyguard;
        private WallpaperRenderer renderer;

        /** Оригинал — для воспроизведения вперёд. */
        private MediaPlayer forward;
        private boolean forwardReady;
        /** Обратное видео — только для перемотки назад. */
        private MediaPlayer backward;
        private boolean backwardReady;
        /** Есть готовое обратное видео и реверс выбран в настройках. */
        private boolean reverseAvailable;
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

        private float systemZoom;
        private float returnZoom;
        private boolean systemZoomSeen;
        private ValueAnimator returnZoomAnimator;

        /** Пока виден экран блокировки, следим за разблокировкой (лицо, задержки системы). */
        private final Runnable unlockWatch = new Runnable() {
            @Override
            public void run() {
                if (!visible || unlocked) return;
                if (unlockedNow()) {
                    onUnlocked();
                } else {
                    handler.postDelayed(this, UNLOCK_POLL_MS);
                }
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

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }

            unlocked = unlockedNow();
            loadConfig();
            phase = finishedForever ? PHASE_DONE : PHASE_WAITING;
            positionMs = finishedForever && cfg.endOnLastFrame && cfg.lockAction == Prefs.LOCK_KEEP
                    ? AT_END : 0;
            // Превью сразу показывает, как играет видео; настоящие обои ждут события.
            armed = isPreview();
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacksAndMessages(null);
            cancelReturnZoom();
            sp.unregisterOnSharedPreferenceChangeListener(this);
            VideoWallpaperService.this.unregisterReceiver(screenReceiver);
            closePlayers();
            releaseRenderer();
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (renderer == null) {
                renderer = WallpaperRenderer.create(holder.getSurface(), width, height);
                applyZoom();
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
            if (!isVisible) {
                handler.removeCallbacks(unlockWatch);
                hiddenByApp = power.isInteractive() && unlocked;
                pauseAll();
                // Обои закрыло приложение — приближаем, чтобы при возврате плавно отдалить.
                // Если лаунчер сам присылает onZoomChanged, он делает это лучше нас.
                if (cfg.zoomEffect && !systemZoomSeen && hiddenByApp) {
                    cancelReturnZoom();
                    returnZoom = 1f;
                    applyZoom();
                }
                return;
            }
            boolean backFromApp = hiddenByApp;
            hiddenByApp = false;
            if (returnZoom > 0f) animateReturnZoom();
            watchUnlock();
            if (backFromApp && cfg.startOnHome && !isPreview()) {
                triggerForward(true);
            } else if (!unlocked && needReverse) {
                startReverse();
            } else {
                resume();
            }
        }

        private void onScreenOff() {
            unlocked = isPreview();
            handler.removeCallbacks(unlockWatch);
            hiddenByApp = false;
            armed = isPreview();
            pauseAll();
            boolean atStart = forwardPosition() <= 30;
            if (cfg.lockAction == Prefs.LOCK_KEEP || atStart) return;
            if (cfg.lockAction == Prefs.LOCK_REVERSE && reverseAvailable) {
                // Назад проиграем на экране блокировки, когда он появится.
                needReverse = true;
            } else {
                // Мгновенно, пока экран выключен — к включению на экране первый кадр.
                seekForward(0);
            }
            // Прерванная серия сбрасывается — дальше ждём нового запуска.
            if (phase == PHASE_PLAYING) phase = PHASE_WAITING;
        }

        private void onScreenOn() {
            // Например, разблокировали лицом ещё до того, как экран включился полностью.
            if (!unlocked && unlockedNow()) {
                onUnlocked();
                return;
            }
            watchUnlock();
            if (cfg.startOnScreenOn) {
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
            if (unlocked) {
                resume();
                return;
            }
            unlocked = true;
            handler.removeCallbacks(unlockWatch);
            if (reversing) stopReverse();
            if (cfg.startOnUnlock) {
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

        /** Android 11+: лаунчер сообщает, насколько «отодвинуть» обои (0..1). */
        @Override
        public void onZoomChanged(float zoom) {
            systemZoom = zoom;
            if (zoom > 0f) systemZoomSeen = true;
            applyZoom();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (Prefs.KEY_REVISION.equals(key)) {
                // Новые настройки, текущее состояние сохраняем.
                closePlayers();
                loadConfig();
                if (finishedForever) phase = PHASE_DONE;
                if (needReverse && !reverseAvailable) {
                    needReverse = false;
                    positionMs = 0;
                }
                applyZoom();
                openPlayers();
            } else if (Prefs.KEY_PLAY_NOW.equals(key)) {
                // «Проиграть сейчас» или новое видео: с начала, как только обои станут видны.
                closePlayers();
                loadConfig();
                phase = PHASE_WAITING;
                positionMs = 0;
                needReverse = false;
                armed = true;
                applyZoom();
                openPlayers();
            }
        }

        private void loadConfig() {
            Context context = VideoWallpaperService.this;
            cfg = Prefs.load(context);
            reverseAvailable = cfg.wantsReverse() && cfg.reverseReady && cfg.reverseLastMs > 0
                    && Prefs.reverseFile(context).isFile();
            lastFrameMs = cfg.reverseLastMs;
            finishedForever = !isPreview() && cfg.once && cfg.finished;
            if (!cfg.zoomEffect) {
                cancelReturnZoom();
                returnZoom = 0f;
            }
        }

        // ---- Логика воспроизведения ----

        /**
         * Разблокирован ли телефон прямо сейчас. С защищённой блокировкой — подтверждена ли
         * личность (при разблокировке лицом экран блокировки ещё может быть на месте),
         * без защиты — убран ли экран блокировки.
         */
        private boolean unlockedNow() {
            if (isPreview()) return true;
            return keyguard.isKeyguardSecure() ? !keyguard.isDeviceLocked()
                    : !keyguard.isKeyguardLocked();
        }

        private void watchUnlock() {
            handler.removeCallbacks(unlockWatch);
            if (visible && !unlocked) handler.postDelayed(unlockWatch, UNLOCK_POLL_MS);
        }

        /** Играть вперёд можно, когда обои видны и телефон разблокирован (или так настроено). */
        private boolean canPlayForward() {
            return visible && (unlocked || cfg.startOnScreenOn);
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
            if (!canPlayForward()) return;
            if (phase == PHASE_WAITING) {
                if (!armed) return;
                armed = false;
                phase = PHASE_PLAYING;
                playsLeft = cfg.plays;
            }
            if (!forward.isPlaying()) {
                renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
                forward.start();
            }
        }

        private void startReverse() {
            if (!reverseAvailable || backward == null || !backwardReady || !visible) return;
            int position = forwardPosition();
            needReverse = false;
            if (position <= 0) return;
            reversing = true;
            seek(backward, lastFrameMs - Math.min(position, lastFrameMs));
            backward.start();
            renderer.showSource(WallpaperRenderer.SOURCE_REVERSE);
        }

        /** Останавливает реверс; оригинал встаёт на тот же кадр. */
        private void stopReverse() {
            if (!reversing) return;
            reversing = false;
            int position = clamp(lastFrameMs - backward.getCurrentPosition());
            backward.pause();
            seekForward(position);
            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
        }

        private void onBackwardCompleted(MediaPlayer mp) {
            if (mp != backward || !reversing) return;
            reversing = false;
            seekForward(0);
            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
        }

        private void onForwardCompleted(MediaPlayer mp) {
            if (mp != forward || cfg.infinite()) return;
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
                return Math.max(0, forward.getDuration() - 50);
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

        private void seek(MediaPlayer mp, int positionMs) {
            mp.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
        }

        // ---- Плееры ----

        private void openPlayers() {
            if (renderer == null || forward != null) return;
            Context context = VideoWallpaperService.this;
            File video = Prefs.videoFile(context);
            if (!video.isFile()) return;
            forward = createPlayer(video, WallpaperRenderer.SOURCE_FORWARD, cfg.sound);
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
            if (reverseAvailable) {
                backward = createPlayer(Prefs.reverseFile(context),
                        WallpaperRenderer.SOURCE_REVERSE, false);
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
        }

        private MediaPlayer createPlayer(File file, int source, boolean sound) {
            MediaPlayer mp = new MediaPlayer();
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
                    if (p == backward) {
                        // Без реверса обойдёмся — возвращаем к началу мгновенно.
                        reversing = false;
                        reverseAvailable = false;
                        releaseBackward();
                        seekForward(0);
                        if (renderer != null) {
                            renderer.showSource(WallpaperRenderer.SOURCE_FORWARD);
                        }
                    } else if (p == forward) {
                        closePlayers();
                    }
                    return true;
                });
                return mp;
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

        // ---- Приближение ----

        private void applyZoom() {
            if (renderer == null) return;
            float amount = cfg.zoomEffect ? Math.max(systemZoom, returnZoom) : 0f;
            renderer.setZoom(1f + ZOOM_AMOUNT * amount);
        }

        private void animateReturnZoom() {
            cancelReturnZoom();
            returnZoomAnimator = ValueAnimator.ofFloat(returnZoom, 0f);
            returnZoomAnimator.setDuration(RETURN_ZOOM_MS);
            returnZoomAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
            returnZoomAnimator.addUpdateListener(a -> {
                returnZoom = (float) a.getAnimatedValue();
                applyZoom();
            });
            returnZoomAnimator.start();
        }

        private void cancelReturnZoom() {
            if (returnZoomAnimator != null) {
                returnZoomAnimator.cancel();
                returnZoomAnimator = null;
            }
        }
    }
}
