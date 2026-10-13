package com.dead.unlockvideo;

import android.animation.ValueAnimator;
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

public class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "VideoWallpaper";
    /** Насколько приближаются обои при открытом приложении. */
    private static final float ZOOM_AMOUNT = 0.08f;
    private static final long RETURN_ZOOM_MS = 550;
    /** Как часто проверяем, не дошло ли видео до конца прямой половины. */
    private static final long FORWARD_WATCH_MS = 10;

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private final class VideoEngine extends Engine
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        /** Серия проигрываний ждёт своего запуска; на экране первый кадр. */
        private static final int PHASE_WAITING = 0;
        /** Серия идёт (может стоять на паузе, пока обои не видны). */
        private static final int PHASE_PLAYING = 1;
        /** Серия закончилась; на экране последний или первый кадр. */
        private static final int PHASE_DONE = 2;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private SharedPreferences sp;
        private Prefs cfg;
        private PowerManager power;
        private WallpaperRenderer renderer;
        private MediaPlayer player;
        private boolean prepared;
        private boolean visible;
        /** Была ли разблокировка после последнего выключения экрана. */
        private boolean unlockedSinceScreenOff;
        private int phase;
        private int playsLeft;
        /** Позиция в прямом видео, с которой продолжать. */
        private int resumePositionMs;
        /** Что показывать в PHASE_DONE: первый кадр или последний. */
        private boolean restAtStart;

        /**
         * Плавный возврат: в файле после прямого видео лежит оно же задом наперёд,
         * [0, lastFrameMs] — вперёд, [splitMs, конец] — назад.
         */
        private boolean reversible;
        private int lastFrameMs;
        private int splitMs;
        /** Нужно вернуться к началу реверсом, как только обои станут видны. */
        private boolean pendingRewind;
        /** Сейчас играет обратная половина. */
        private boolean reversing;

        private float systemZoom;
        private float returnZoom;
        private boolean systemZoomSeen;
        private ValueAnimator returnZoomAnimator;

        private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    onUserPresent();
                }
            }
        };

        private final Runnable forwardWatch = new Runnable() {
            @Override
            public void run() {
                if (player == null || !prepared || reversing || !player.isPlaying()) return;
                if (player.getCurrentPosition() >= lastFrameMs - 15) {
                    player.pause();
                    onForwardEnd();
                } else {
                    handler.postDelayed(this, FORWARD_WATCH_MS);
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            Context context = VideoWallpaperService.this;
            power = context.getSystemService(PowerManager.class);
            sp = Prefs.prefs(context);
            sp.registerOnSharedPreferenceChangeListener(this);

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(screenReceiver, filter);
            }

            // Превью открывается на уже разблокированном телефоне, в нём ждать нечего.
            unlockedSinceScreenOff = isPreview();
            resetState();
        }

        @Override
        public void onDestroy() {
            handler.removeCallbacksAndMessages(null);
            cancelReturnZoom();
            sp.unregisterOnSharedPreferenceChangeListener(this);
            VideoWallpaperService.this.unregisterReceiver(screenReceiver);
            closePlayer();
            releaseRenderer();
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (renderer == null) {
                renderer = WallpaperRenderer.create(holder.getSurface(), width, height);
                applyZoom();
                openPlayer();
            } else {
                renderer.setViewSize(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            closePlayer();
            releaseRenderer();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (!isVisible) {
                pausePlayback();
                // Обои закрыло приложение (а не выключение экрана) — приближаем,
                // чтобы при возврате плавно отдалить. Если лаунчер сам присылает
                // onZoomChanged, он делает это лучше нас.
                if (cfg.zoomEffect && !systemZoomSeen && power.isInteractive()) {
                    cancelReturnZoom();
                    returnZoom = 1f;
                    applyZoom();
                }
                return;
            }
            if (returnZoom > 0f) animateReturnZoom();
            if (phase == PHASE_DONE && cfg.replay && cfg.replayOnHome) {
                restartSeries();
            } else {
                tryPlay();
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
                closePlayer();
                resetState();
                applyZoom();
                openPlayer();
            }
        }

        private void resetState() {
            Context context = VideoWallpaperService.this;
            cfg = Prefs.load(context);
            reversible = !cfg.loop && cfg.rewindOnLock && cfg.rewindReverse && cfg.reverseReady
                    && cfg.reverseLastMs > 0 && Prefs.reversibleFile(context).isFile();
            lastFrameMs = cfg.reverseLastMs;
            splitMs = cfg.reverseSplitMs;
            boolean alreadyPlayed = !isPreview() && !cfg.replay && cfg.finished;
            phase = alreadyPlayed ? PHASE_DONE : PHASE_WAITING;
            playsLeft = cfg.playCount;
            resumePositionMs = 0;
            pendingRewind = false;
            reversing = false;
            restAtStart = !cfg.endOnLastFrame || (cfg.rewindOnLock && !cfg.loop);
            if (!cfg.zoomEffect) {
                cancelReturnZoom();
                returnZoom = 0f;
            }
        }

        // ---- Плеер ----

        private void openPlayer() {
            if (renderer == null || player != null) return;
            Context context = VideoWallpaperService.this;
            File video = reversible ? Prefs.reversibleFile(context) : Prefs.videoFile(context);
            if (!video.isFile()) return;

            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setSurface(renderer.videoSurface());
                mp.setDataSource(video.getAbsolutePath());
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
                float volume = cfg.sound ? 1f : 0f;
                mp.setVolume(volume, volume);
                mp.setLooping(cfg.loop);
                mp.setOnPreparedListener(this::onPrepared);
                mp.setOnCompletionListener(this::onCompleted);
                mp.setOnVideoSizeChangedListener((p, width, height) -> {
                    if (p == player && renderer != null) renderer.setVideoSize(width, height);
                });
                mp.setOnErrorListener((p, what, extra) -> {
                    Log.w(TAG, "MediaPlayer error " + what + "/" + extra);
                    if (p == player) closePlayer();
                    return true;
                });
                mp.prepareAsync();
                player = mp;
                prepared = false;
            } catch (Exception e) {
                Log.w(TAG, "Cannot open video", e);
                mp.release();
            }
        }

        private void closePlayer() {
            handler.removeCallbacks(forwardWatch);
            if (player == null) return;
            if (prepared) {
                if (reversing) {
                    resumePositionMs = toForward(player.getCurrentPosition());
                    reversing = false;
                } else if (phase == PHASE_PLAYING && !pendingRewind) {
                    resumePositionMs = player.getCurrentPosition();
                }
            }
            player.release();
            player = null;
            prepared = false;
        }

        private void releaseRenderer() {
            if (renderer != null) {
                renderer.release();
                renderer = null;
            }
        }

        private void onPrepared(MediaPlayer mp) {
            if (mp != player) return;
            prepared = true;
            if (phase == PHASE_DONE && !pendingRewind) {
                seek(mp, restAtStart ? 0 : endPositionMs(mp));
            } else {
                seek(mp, resumePositionMs);
            }
            tryPlay();
        }

        private void onCompleted(MediaPlayer mp) {
            if (mp != player) return;
            if (reversing) {
                finishRewind();
            } else if (!cfg.loop) {
                onForwardEnd();
            }
        }

        /** Видео доиграло вперёд до конца. */
        private void onForwardEnd() {
            playsLeft--;
            if (playsLeft > 0) {
                if (reversible) {
                    seek(player, 0);
                    player.start();
                    watchForward();
                } else {
                    player.start(); // после окончания start() начинает с начала
                }
                return;
            }
            phase = PHASE_DONE;
            resumePositionMs = 0;
            restAtStart = !cfg.endOnLastFrame;
            if (!cfg.replay && !isPreview()) {
                sp.edit().putBoolean(Prefs.KEY_FINISHED, true).apply();
            }
            if (!cfg.endOnLastFrame) seek(player, 0);
            // Иначе на экране остаётся последний кадр.
        }

        // ---- События ----

        private void onScreenOff() {
            unlockedSinceScreenOff = isPreview();
            pausePlayback();
            boolean restart = cfg.replay && cfg.replayAfterLock && phase != PHASE_WAITING;
            boolean rewind = restart || (cfg.rewindOnLock && !cfg.loop);
            if (rewind && !atStart()) {
                if (reversible && player != null && prepared) {
                    // Назад проиграем, когда экран снова включится.
                    if (!pendingRewind) resumePositionMs = currentForwardPosition();
                    pendingRewind = true;
                } else {
                    // Мгновенно, пока экран выключен — к включению на экране первый кадр.
                    pendingRewind = false;
                    resumePositionMs = 0;
                    if (player != null && prepared) seek(player, 0);
                    if (phase == PHASE_DONE) restAtStart = true;
                }
            }
            if (restart) {
                phase = PHASE_WAITING;
                playsLeft = cfg.playCount;
            }
        }

        private void onUserPresent() {
            unlockedSinceScreenOff = true;
            if (reversing && phase != PHASE_DONE && player != null) {
                // Разблокировали во время обратной перемотки — едем вперёд с того же кадра.
                int position = toForward(player.getCurrentPosition());
                player.pause();
                reversing = false;
                pendingRewind = false;
                resumePositionMs = position;
                seek(player, position);
            }
            tryPlay();
        }

        // ---- Управление воспроизведением ----

        private void tryPlay() {
            if (player == null || !prepared || !visible || reversing) return;
            boolean unlocked = !cfg.afterUnlock || unlockedSinceScreenOff;
            if (pendingRewind) {
                if (phase != PHASE_DONE && unlocked) {
                    // Видео всё равно сейчас пойдёт вперёд — перематывать назад незачем.
                    pendingRewind = false;
                    resumePositionMs = 0;
                    seek(player, 0);
                } else {
                    startRewind();
                    return;
                }
            }
            if (phase == PHASE_DONE || !unlocked || player.isPlaying()) return;
            if (phase == PHASE_WAITING) {
                phase = PHASE_PLAYING;
                playsLeft = cfg.playCount;
            }
            player.start();
            if (reversible) watchForward();
        }

        private void startRewind() {
            reversing = true;
            seek(player, toReverse(resumePositionMs));
            player.start();
        }

        private void finishRewind() {
            reversing = false;
            pendingRewind = false;
            resumePositionMs = 0;
            seek(player, 0);
            if (phase == PHASE_DONE) restAtStart = true;
            tryPlay();
        }

        private void restartSeries() {
            pausePlayback();
            phase = PHASE_WAITING;
            playsLeft = cfg.playCount;
            resumePositionMs = 0;
            pendingRewind = false;
            if (player != null && prepared) seek(player, 0);
            tryPlay();
        }

        private void pausePlayback() {
            handler.removeCallbacks(forwardWatch);
            if (player == null || !prepared) return;
            if (reversing) {
                resumePositionMs = toForward(player.getCurrentPosition());
                reversing = false;
            }
            if (player.isPlaying()) player.pause();
        }

        private void watchForward() {
            handler.removeCallbacks(forwardWatch);
            handler.post(forwardWatch);
        }

        private boolean atStart() {
            if (pendingRewind) return false;
            if (phase == PHASE_WAITING) return true;
            if (phase == PHASE_DONE) return restAtStart;
            return false;
        }

        private int currentForwardPosition() {
            if (phase == PHASE_DONE) return restAtStart ? 0 : lastFrameMs;
            return Math.min(player.getCurrentPosition(), lastFrameMs);
        }

        private int endPositionMs(MediaPlayer mp) {
            return reversible ? lastFrameMs : Math.max(0, mp.getDuration() - 50);
        }

        private int toReverse(int forwardMs) {
            return splitMs + (lastFrameMs - clamp(forwardMs));
        }

        private int toForward(int fileMs) {
            if (!reversible || fileMs < splitMs) return clamp(fileMs);
            return clamp(lastFrameMs - (fileMs - splitMs));
        }

        private int clamp(int forwardMs) {
            return Math.max(0, Math.min(lastFrameMs, forwardMs));
        }

        private void seek(MediaPlayer mp, int positionMs) {
            mp.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
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
