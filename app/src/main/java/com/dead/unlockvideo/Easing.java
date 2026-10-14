package com.dead.unlockvideo;

/**
 * Мягкий старт и мягкая остановка движения: скорость плавно (по косинусу) нарастает от нуля в
 * начале и так же спадает к нулю в конце, посередине — ровная. Разгон и торможение могут быть
 * разной длины; обратный ход сцены берёт ту же кривую от конца (SceneClip) — точное зеркало.
 */
final class Easing {
    /** Плавность старта и остановки сцены по умолчанию, %. */
    static final int DEFAULT_SOFTNESS = 60;
    /** Масштаб при разблокировке: доля времени на разгон и на торможение. */
    static final float ZOOM_RAMP = 0.35f;

    /** Доля времени на разгон (или на торможение) для плавности 0..100 %. */
    static float ramp(int softness) {
        return 0.5f * Math.max(0, Math.min(100, softness)) / 100f;
    }

    /**
     * Ход 0..1 в момент t (0..1). ramp — доля времени на разгон и на торможение: 0 —
     * равномерно, 0.5 — разгон до середины, затем торможение.
     */
    static float soft(float t, float ramp) {
        return soft(t, ramp, ramp);
    }

    /**
     * То же с разными долями: start — на разгон в начале, end — на торможение в конце
     * (каждая 0..0.5). Скорость: (1 − cos(π t / start)) / 2 на разгоне, 1 посередине,
     * зеркально на торможении; всё делится на (1 − (start + end) / 2), чтобы путь был равен 1.
     */
    static float soft(float t, float start, float end) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float a = Math.max(0f, Math.min(0.5f, start));
        float b = Math.max(0f, Math.min(0.5f, end));
        float scale = 1f / (1f - (a + b) / 2f);
        if (t < a) return scale * rampPath(t, a);
        if (t > 1f - b) return 1f - scale * rampPath(1f - t, b);
        return scale * (t - a / 2f);
    }

    /** Путь за время x разгона длиной a (скорость растёт от 0 до 1). */
    private static float rampPath(float x, float a) {
        return x / 2f - (float) (a / (2 * Math.PI) * Math.sin(Math.PI * x / a));
    }

    private Easing() {
    }
}
