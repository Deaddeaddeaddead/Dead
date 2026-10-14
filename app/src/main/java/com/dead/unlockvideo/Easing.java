package com.dead.unlockvideo;

/**
 * Мягкий старт и мягкая остановка движения: скорость плавно нарастает от нуля в начале и так
 * же спадает к нулю в конце. Кривые симметричны — f(1 − t) = 1 − f(t), поэтому обратный ход —
 * точное зеркало прямого.
 */
final class Easing {
    /** Масштаб при разблокировке: доля времени на разгон и на торможение. */
    static final float ZOOM_RAMP = 0.35f;
    /**
     * Сцена: разгон и торможение длятся каждый такую долю основной длительности и
     * добавляются к ней — середина идёт с исходной скоростью, медленнее становятся только
     * начало и конец.
     */
    static final float SCENE_RAMP = 0.6f;

    /**
     * Вся длительность анимации сцены: основное движение плюс, если сглаживание включено,
     * разгон и торможение.
     */
    static int sceneDuration(int durationMs, boolean smooth) {
        return smooth ? Math.round(durationMs * (1f + SCENE_RAMP)) : durationMs;
    }

    /**
     * Ход сцены 0..1 в момент t (0..1 всей длительности, см. sceneDuration). Со сглаживанием
     * посередине — исходная скорость; в начале скорость плавно (smootherstep) растёт от нуля,
     * в конце так же спадает до нуля. Без сглаживания — равномерно.
     */
    static float scene(float t, boolean smooth) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        if (!smooth) return t;
        // Время — в долях основной длительности.
        float total = 1f + SCENE_RAMP;
        float x = t * total;
        if (x < SCENE_RAMP) return SCENE_RAMP * rampPath(x / SCENE_RAMP);
        if (x > total - SCENE_RAMP) return 1f - SCENE_RAMP * rampPath((total - x) / SCENE_RAMP);
        return SCENE_RAMP / 2f + (x - SCENE_RAMP);
    }

    /**
     * Ход по участку анимации сцены: её время идёт от a до b (0..1, назад — b < a) за t от 0 до
     * 1; результат — доля пути этого участка (0..1). Масштаб «как у видео» по нему идёт в такт
     * сцене, даже если начался не с начала анимации.
     */
    static float sceneSegment(float t, float a, float b, boolean smooth) {
        float from = scene(a, smooth);
        float span = scene(b, smooth) - from;
        if (Math.abs(span) < 1e-4f) return Math.max(0f, Math.min(1f, t));
        return (scene(a + (b - a) * Math.max(0f, Math.min(1f, t)), smooth) - from) / span;
    }

    /**
     * Путь за долю u разгона, где скорость растёт от 0 до исходной как 6u⁵ − 15u⁴ + 10u³, — в
     * долях пути, пройденного за всё время разгона на исходной скорости (за весь разгон — 0.5).
     */
    private static float rampPath(float u) {
        return u * u * u * u * (u * u - 3f * u + 2.5f);
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
