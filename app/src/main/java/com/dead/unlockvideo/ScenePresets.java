package com.dead.unlockvideo;

/**
 * Сцены на выбор. «Оригинал» — копия ролика (GlassScene, свет снят с видео). Остальные
 * рисуются расчётным светом (ProceduralScene): светящиеся формы и стёкла над ними из
 * assets/scenes/glass.frag или свои шейдеры (волны, аврора). Формы — скруглённые
 * прямоугольники (капсула и круг — частные случаи); координаты — в единицах кадра сцены:
 * ширина 1, высота 2.175, центр — 0.
 */
final class ScenePresets {
    static final int ORIGINAL = 0;
    static final int LENS = 1;
    static final int THREE = 2;
    static final int TILES = 3;
    static final int GLOW = 4;
    static final int WAVES = 5;
    static final int AURORA = 6;
    static final int COUNT = 7;

    static final String[] TITLES = {
            "Оригинал", "Линза", "Три стекла", "Плитки", "Свечение", "Волны", "Аврора",
    };

    /** Форма: путь при разблокировке (from → to, с задержкой), размеры и свойства. */
    static final class Shape {
        final float fromX;
        final float fromY;
        final float fromAngle;
        final float toX;
        final float toY;
        final float toAngle;
        /** Доля анимации, после которой форма трогается. */
        final float delay;
        final float halfX;
        final float halfY;
        final float round;
        /** Светящаяся форма: яркость, сила и угол блика. Стекло: скос, высота купола, цвет, матовость. */
        final float[] extra;

        Shape(float fromX, float fromY, float fromAngle, float toX, float toY, float toAngle,
                float delay, float halfX, float halfY, float round, float... extra) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.fromAngle = fromAngle;
            this.toX = toX;
            this.toY = toY;
            this.toAngle = toAngle;
            this.delay = delay;
            this.halfX = halfX;
            this.halfY = halfY;
            this.round = round;
            this.extra = extra;
        }

        /** Ход формы по общему ходу анимации t (0..1): с задержкой и замедлением к концу. */
        float progress(float t) {
            float local = Math.max(0f, Math.min(1f, (t - delay) / (1f - delay)));
            float rest = 1f - local;
            return 1f - rest * rest * rest;
        }
    }

    static final class Preset {
        /** Шейдер из assets/scenes без расширения. */
        final String shader;
        final Shape[] lights;
        final Shape[] glass;

        Preset(String shader, Shape[] lights, Shape[] glass) {
            this.shader = shader;
            this.lights = lights;
            this.glass = glass;
        }
    }

    private static final float AXIS_ANGLE = 0.8695f;

    private static Shape light(float x0, float y0, float a0, float x1, float y1, float a1,
            float delay, float hx, float hy, float r, float power, float sheen, float sheenAngle) {
        return new Shape(x0, y0, a0, x1, y1, a1, delay, hx, hy, r, power, sheen, sheenAngle);
    }

    private static Shape glass(float x0, float y0, float a0, float x1, float y1, float a1,
            float delay, float hx, float hy, float r, float bevel, int color, float frost) {
        return new Shape(x0, y0, a0, x1, y1, a1, delay, hx, hy, r, bevel, 0.53f, color, frost);
    }

    /** Сцена id (кроме ORIGINAL — у неё свой класс). */
    static Preset get(int id) {
        switch (id) {
            case LENS:
                // Солнце и круглая линза, которая наезжает на него снизу.
                return new Preset("glass", new Shape[] {
                        light(0f, 0.14f, 0f, 0f, 0.14f, 0f, 0f, 0.17f, 0.17f, 0.17f, 1.1f, 0.35f,
                                0.8f),
                }, new Shape[] {
                        glass(0f, -0.85f, 0f, 0.05f, 0f, 0f, 0f, 0.27f, 0.27f, 0.27f, 0.27f, 0, 0f),
                });
            case THREE:
                // Светящаяся капсула стоя и три стеклянные, въезжающие слева одна за другой.
                return new Preset("glass", new Shape[] {
                        light(0f, 0f, 1.5708f, 0f, 0f, 1.5708f, 0f, 0.48f, 0.13f, 0.13f, 1f, 0.45f,
                                0.5f),
                }, new Shape[] {
                        glass(-0.85f, 0.42f, 0.6f, 0.02f, 0.42f, 0.6f, 0f, 0.27f, 0.1f, 0.1f, 0.1f,
                                0, 0f),
                        glass(-0.85f, 0f, 0.6f, 0.02f, 0f, 0.6f, 0.12f, 0.27f, 0.1f, 0.1f, 0.1f,
                                1, 0f),
                        glass(-0.85f, -0.42f, 0.6f, 0.02f, -0.42f, 0.6f, 0.24f, 0.27f, 0.1f, 0.1f,
                                0.1f, 0, 0f),
                });
            case TILES:
                // Солнце и капсула под четырьмя матовыми плитками, плитки поднимаются снизу.
                return new Preset("glass", new Shape[] {
                        light(-0.1f, 0.32f, 0f, -0.1f, 0.22f, 0f, 0f, 0.19f, 0.19f, 0.19f, 1f,
                                0.3f, 0.8f),
                        light(0.16f, -0.32f, -0.6f, 0.14f, -0.24f, -0.6f, 0f, 0.26f, 0.11f, 0.11f,
                                0.9f, 0.4f, 0.5f),
                }, new Shape[] {
                        glass(-0.19f, -0.45f, 0.12f, -0.19f, 0.19f, 0f, 0f, 0.165f, 0.165f, 0.06f,
                                0.06f, 0, 0.03f),
                        glass(0.19f, -0.45f, -0.12f, 0.19f, 0.19f, 0f, 0.08f, 0.165f, 0.165f,
                                0.06f, 0.06f, 1, 0.03f),
                        glass(-0.19f, -0.85f, 0.12f, -0.19f, -0.19f, 0f, 0.16f, 0.165f, 0.165f,
                                0.06f, 0.06f, 1, 0.03f),
                        glass(0.19f, -0.85f, -0.12f, 0.19f, -0.19f, 0f, 0.24f, 0.165f, 0.165f,
                                0.06f, 0.06f, 0, 0.03f),
                });
            case GLOW:
                // Две светящиеся капсулы, съезжающиеся навстречу друг другу.
                return new Preset("glass", new Shape[] {
                        light(-0.35f, 0.75f, AXIS_ANGLE, -0.06f, 0.22f, AXIS_ANGLE, 0f, 0.42f,
                                0.15f, 0.15f, 1f, 0.45f, 0.6f),
                        light(0.35f, -0.75f, AXIS_ANGLE, 0.06f, -0.22f, AXIS_ANGLE, 0f, 0.42f,
                                0.15f, 0.15f, 1f, 0.45f, 0.6f),
                }, new Shape[0]);
            case WAVES:
                return new Preset("waves", new Shape[0], new Shape[0]);
            case AURORA:
                return new Preset("aurora", new Shape[0], new Shape[0]);
            default:
                return null;
        }
    }

    private ScenePresets() {
    }
}
