package com.dead.unlockvideo;

/**
 * Мягкий старт и мягкая остановка движения: скорость плавно нарастает от нуля в начале и так
 * же спадает к нулю в конце. Кривая симметрична — soft(1 − t) = 1 − soft(t), поэтому обратный
 * ход — точное зеркало прямого.
 */
final class Easing {
    /** Масштаб при разблокировке: доля времени на разгон и на торможение. */
    static final float ZOOM_RAMP = 0.35f;

    /**
     * Ход сцены 0..1 в момент t (0..1): скорость ∝ sin⁴(πt) — разгон медленнее, чем у
     * «полусинуса» (sin²): за первую четверть времени проходится 3.8% пути вместо 9%, и так же
     * плавно — торможение.
     */
    static float soft(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        double a = 2 * Math.PI * t;
        return (float) (t - 2.0 / (3 * Math.PI) * Math.sin(a)
                + 1.0 / (12 * Math.PI) * Math.sin(2 * a));
    }

    /**
     * Ход 0..1 в момент t (0..1) с разгоном и торможением по косинусу: ramp — доля времени на
     * разгон и на торможение (0 — равномерно, 0.5 — разгон до середины).
     */
    static float soft(float t, float ramp) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float a = Math.max(0f, Math.min(0.5f, ramp));
        if (a <= 0f) return t;
        if (t > 0.5f) return 1f - soft(1f - t, a);
        float scale = 1f / (1f - a);
        if (t < a) {
            return scale * (t / 2f - (float) (a / (2 * Math.PI) * Math.sin(Math.PI * t / a)));
        }
        return scale * (t - a / 2f);
    }

    private Easing() {
    }
}
