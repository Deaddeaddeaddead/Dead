package com.dead.unlockvideo;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Анимация, нарисованная кодом: время идёт по часам, и назад она играет так же, как вперёд
 * (reversed — позиция p показывает кадр duration − p). Ход — с мягким стартом и остановкой
 * (Easing), обратный ход — его точное зеркало. Управляется с главного потока,
 * а кадр (progress) читает поток рендера.
 */
final class SceneClip extends Clip implements WallpaperRenderer.SceneSource {
    /** Неизменяемое состояние часов — поток рендера читает его целиком. */
    private static final class State {
        final int position;
        final long since;
        final boolean playing;

        State(int position, long since, boolean playing) {
            this.position = position;
            this.since = since;
            this.playing = playing;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int duration;
    private final boolean reversed;
    /** Доля времени на мягкий разгон и торможение (Easing.soft). */
    private final float ramp;
    private volatile State state = new State(0, 0, false);
    private volatile boolean looping;
    /** Позицию сменили — рендеру нужен новый кадр. */
    private Runnable onChange;
    private final Runnable finish = this::onReachedEnd;

    SceneClip(int durationMs, boolean reversed, float ramp) {
        duration = Math.max(1, durationMs);
        this.reversed = reversed;
        this.ramp = ramp;
    }

    void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    private void changed() {
        if (onChange != null) onChange.run();
    }

    private int positionOf(State s, long now) {
        if (!s.playing) return s.position;
        long p = s.position + (now - s.since);
        return looping ? (int) (p % duration) : (int) Math.min(duration, p);
    }

    @Override
    void prepareAsync() {
        handler.post(() -> {
            if (onPrepared != null) onPrepared.on(this);
        });
    }

    @Override
    void start() {
        State s = state;
        if (s.playing) return;
        int from = s.position >= duration ? 0 : s.position;
        state = new State(from, SystemClock.uptimeMillis(), true);
        scheduleEnd(from);
        changed();
    }

    @Override
    void pause() {
        State s = state;
        if (!s.playing) return;
        state = new State(positionOf(s, SystemClock.uptimeMillis()), 0, false);
        handler.removeCallbacks(finish);
        changed();
    }

    @Override
    boolean isPlaying() {
        return state.playing;
    }

    @Override
    int getCurrentPosition() {
        return positionOf(state, SystemClock.uptimeMillis());
    }

    @Override
    int getDuration() {
        return duration;
    }

    @Override
    void seekTo(int positionMs) {
        int p = Math.max(0, Math.min(duration, positionMs));
        boolean playing = state.playing;
        state = new State(p, SystemClock.uptimeMillis(), playing);
        if (playing) scheduleEnd(p);
        changed();
    }

    @Override
    void setLooping(boolean looping) {
        this.looping = looping;
        State s = state;
        if (s.playing) scheduleEnd(positionOf(s, SystemClock.uptimeMillis()));
    }

    @Override
    void setVolume(float volume) {
        // Звука у сцены нет.
    }

    @Override
    void release() {
        handler.removeCallbacks(finish);
        state = new State(positionOf(state, SystemClock.uptimeMillis()), 0, false);
        onChange = null;
    }

    @Override
    int endOffsetMs() {
        return 0;
    }

    private void scheduleEnd(int from) {
        handler.removeCallbacks(finish);
        if (!looping) handler.postDelayed(finish, Math.max(0, duration - from));
    }

    private void onReachedEnd() {
        if (!state.playing) return;
        state = new State(duration, 0, false);
        changed();
        if (onCompletion != null) onCompletion.on(this);
    }

    // ---- Для рендера (любой поток) ----

    @Override
    public float progress() {
        float t = positionOf(state, SystemClock.uptimeMillis()) / (float) duration;
        float eased = Easing.soft(t, ramp);
        return reversed ? 1f - eased : eased;
    }

    @Override
    public boolean animating() {
        return state.playing;
    }
}
