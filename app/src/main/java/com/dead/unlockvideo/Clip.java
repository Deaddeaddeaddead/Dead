package com.dead.unlockvideo;

/**
 * Источник кадров обоев: видео-файл или сцена, нарисованная кодом. Повторяет нужную
 * обоям часть MediaPlayer, поэтому вся логика воспроизведения у них общая. Главный поток.
 */
abstract class Clip {
    interface Callback {
        void on(Clip clip);
    }

    Callback onPrepared;
    Callback onCompletion;

    void setOnPreparedListener(Callback callback) {
        onPrepared = callback;
    }

    void setOnCompletionListener(Callback callback) {
        onCompletion = callback;
    }

    abstract void prepareAsync();

    /** После окончания начинает сначала — как MediaPlayer. */
    abstract void start();

    abstract void pause();

    abstract boolean isPlaying();

    abstract int getCurrentPosition();

    abstract int getDuration();

    /** Точно на кадр позиции. */
    abstract void seekTo(int positionMs);

    abstract void setLooping(boolean looping);

    abstract void setVolume(float volume);

    abstract void release();

    /** Насколько раньше конца стоит последний кадр, на который можно встать. */
    abstract int endOffsetMs();
}
