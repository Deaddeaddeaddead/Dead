package com.dead.unlockvideo;

/**
 * Сцены на выбор. «Оригинал» — копия ролика (GlassScene, свет снят с видео); «Ленты» — копия
 * роликов со спиралями (LightScene); «Квадраты» — стеклянные квадраты с их светом
 * (SquaresScene); остальные — свои шейдеры из assets/scenes (ProceduralScene). Номера сцен
 * постоянные — по ним сохраняется выбор; в списке — только те, что есть сейчас.
 */
final class ScenePresets {
    static final int ORIGINAL = 0;
    static final int WAVES = 5;
    static final int RIBBONS = 7;
    /** Были «Ленты» в фиолетовом — теперь это цвет «Лент» (см. Prefs). */
    static final int RIBBONS_VIOLET_OLD = 8;
    static final int SQUARES = 9;
    /** Сцены в порядке показа. */
    static final int[] LIST = {ORIGINAL, RIBBONS, SQUARES, WAVES};

    static String title(int id) {
        switch (id) {
            case WAVES:
                return "Волны";
            case RIBBONS:
                return "Ленты";
            case SQUARES:
                return "Квадраты";
            default:
                return "Оригинал";
        }
    }

    /** Шейдер сцены в assets/scenes без расширения (у остальных — свои классы). */
    static String shader(int id) {
        return id == WAVES ? "waves" : null;
    }

    /** Сцена «Свет» (Ленты, Квадраты): свои цвета — LightColors, а не палитра стекла. */
    static boolean light(int id) {
        return id == RIBBONS || id == SQUARES;
    }

    /** Настройки сцены для рендера: палитра стекла или LightColors. */
    static float[] params(Prefs cfg) {
        return light(cfg.scene) ? LightColors.params(cfg) : GlassScene.palette(cfg);
    }

    /** Основные цвета сцены для системы (часы, палитра One UI). */
    static int[] systemColors(Prefs cfg) {
        return light(cfg.scene) ? LightColors.systemColors(LightColors.params(cfg))
                : GlassScene.colors(GlassScene.palette(cfg));
    }

    static boolean exists(int id) {
        for (int value : LIST) {
            if (value == id) return true;
        }
        return false;
    }

    private ScenePresets() {
    }
}
