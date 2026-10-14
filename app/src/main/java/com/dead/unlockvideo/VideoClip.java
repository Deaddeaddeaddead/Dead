package com.dead.unlockvideo;

import android.media.MediaPlayer;

/** Видео-файл: обёртка над MediaPlayer. */
final class VideoClip extends Clip {
    final MediaPlayer player;

    VideoClip(MediaPlayer player) {
        this.player = player;
        player.setOnPreparedListener(mp -> {
            if (onPrepared != null) onPrepared.on(this);
        });
        player.setOnCompletionListener(mp -> {
            if (onCompletion != null) onCompletion.on(this);
        });
    }

    @Override
    void prepareAsync() {
        player.prepareAsync();
    }

    @Override
    void start() {
        player.start();
    }

    @Override
    void pause() {
        player.pause();
    }

    @Override
    boolean isPlaying() {
        return player.isPlaying();
    }

    @Override
    int getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    int getDuration() {
        return player.getDuration();
    }

    @Override
    void seekTo(int positionMs) {
        player.seekTo(positionMs, MediaPlayer.SEEK_CLOSEST);
    }

    @Override
    void setLooping(boolean looping) {
        player.setLooping(looping);
    }

    @Override
    void setVolume(float volume) {
        player.setVolume(volume, volume);
    }

    @Override
    void release() {
        player.release();
    }

    @Override
    int endOffsetMs() {
        // Последний кадр видео стоит чуть раньше заявленной длительности.
        return 50;
    }
}
