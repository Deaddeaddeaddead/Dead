package com.dead.unlockvideo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.File;

public class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "VideoWallpaper";

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

        private SharedPreferences sp;
        private Prefs cfg;
        private SurfaceHolder surfaceHolder;
        private MediaPlayer player;
        private boolean prepared;
        private boolean visible;
        /** Была ли разблокировка после последнего выключения экрана. */
        private boolean unlockedSinceScreenOff;
        private int phase;
        private int playsLeft;
        private int resumePositionMs;

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

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            Context context = VideoWallpaperService.this;
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
            sp.unregisterOnSharedPreferenceChangeListener(this);
            VideoWallpaperService.this.unregisterReceiver(screenReceiver);
            closePlayer();
            super.onDestroy();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            surfaceHolder = holder;
            openPlayer();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            closePlayer();
            surfaceHolder = null;
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (!isVisible) {
                pausePlayback();
            } else if (phase == PHASE_DONE && cfg.replay && cfg.replayOnHome) {
                restartSeries();
            } else {
                tryPlay();
            }
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (Prefs.KEY_REVISION.equals(key)) {
                closePlayer();
                resetState();
                openPlayer();
            }
        }

        private void resetState() {
            cfg = Prefs.load(VideoWallpaperService.this);
            boolean alreadyPlayed = !isPreview() && !cfg.replay && cfg.finished;
            phase = alreadyPlayed ? PHASE_DONE : PHASE_WAITING;
            playsLeft = cfg.playCount;
            resumePositionMs = 0;
        }

        private void openPlayer() {
            if (surfaceHolder == null || player != null) return;
            File video = Prefs.videoFile(VideoWallpaperService.this);
            if (!video.isFile()) return;

            MediaPlayer mp = new MediaPlayer();
            try {
                mp.setSurface(surfaceHolder.getSurface());
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
            if (player == null) return;
            if (prepared && phase == PHASE_PLAYING) {
                resumePositionMs = player.getCurrentPosition();
            }
            player.release();
            player = null;
            prepared = false;
        }

        private void onPrepared(MediaPlayer mp) {
            if (mp != player) return;
            prepared = true;
            mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            if (phase == PHASE_DONE) {
                seek(mp, cfg.endOnLastFrame ? Math.max(0, mp.getDuration() - 50) : 0);
            } else {
                seek(mp, resumePositionMs);
            }
            tryPlay();
        }

        private void onCompleted(MediaPlayer mp) {
            if (mp != player || cfg.loop) return;
            playsLeft--;
            if (playsLeft > 0) {
                mp.start(); // после окончания start() начинает с начала
                return;
            }
            phase = PHASE_DONE;
            resumePositionMs = 0;
            if (!cfg.replay && !isPreview()) {
                sp.edit().putBoolean(Prefs.KEY_FINISHED, true).apply();
            }
            if (!cfg.endOnLastFrame) {
                seek(mp, 0);
            }
            // Иначе MediaPlayer сам оставляет на экране последний кадр.
        }

        private void onScreenOff() {
            unlockedSinceScreenOff = isPreview();
            if (cfg.replay && cfg.replayAfterLock && phase != PHASE_WAITING) {
                // Перематываем, пока экран выключен — к следующему включению на экране первый кадр.
                restartSeries();
            } else {
                pausePlayback();
            }
        }

        private void onUserPresent() {
            unlockedSinceScreenOff = true;
            tryPlay();
        }

        private void restartSeries() {
            pausePlayback();
            phase = PHASE_WAITING;
            playsLeft = cfg.playCount;
            resumePositionMs = 0;
            if (player != null && prepared) seek(player, 0);
            tryPlay();
        }

        private void tryPlay() {
            if (player == null || !prepared || !visible || phase == PHASE_DONE) return;
            if (cfg.afterUnlock && !unlockedSinceScreenOff) return;
            if (player.isPlaying()) return;
            if (phase == PHASE_WAITING) {
                phase = PHASE_PLAYING;
                playsLeft = cfg.playCount;
            }
            player.start();
        }

        private void pausePlayback() {
            if (player != null && prepared && player.isPlaying()) {
                player.pause();
            }
        }

        private void seek(MediaPlayer mp, int positionMs) {
            mp.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
        }
    }
}
