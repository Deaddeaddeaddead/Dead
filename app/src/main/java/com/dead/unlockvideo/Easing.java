package com.dead.unlockvideo;

/**
 * Мягкий старт и мягкая остановка движения: скорость плавно (по косинусу) нарастает от нуля в
 * начале и так же спадает к нулю в конце, посередине — ровная. Кривая симметрична:
 * soft(1 − t) = 1 − soft(t), поэтому обратный ход — точное зеркало прямого.
 */
final class Easing {
    /** Плавность сцены по умолчанию, %. */
    static final int DEFAULT_SOFTNESS = 60;
    /** Масштаб при разблокировке: доля времени на разгон и на торможение. */
    static final float ZOOM_RAMP = 0.35f;

    /** Доля времени на разгон (и столько же на торможение) для плавности 0..100 %. */
    static float ramp(int softness) {
        return 0.5f * Math.max(0, Math.min(100, softness)) / 100f;
    }

    /**
     * Ход 0..1 в момент t (0..1). ramp — доля времени на разгон и на торможение: 0 —
     * равномерно, 0.5 — разгон до середины, затем торможение.
     */
    static float soft(float t, float ramp) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float a = Math.max(0f, Math.min(0.5f, ramp));
        if (a <= 0f) return t;
        if (t > 0.5f) return 1f - soft(1f - t, a);
        // Скорость: (1 − cos(π t / a)) / 2 на разгоне, 1 посередине; всё делится на (1 − a),
        // чтобы весь путь был равен 1.
        float scale = 1f / (1f - a);
        if (t < a) {
            return scale * (t / 2f - (float) (a / (2 * Math.PI) * Math.sin(Math.PI * t / a)));
        }
        return scale * (t - a / 2f);
    }

    private Easing() {
    }
}
