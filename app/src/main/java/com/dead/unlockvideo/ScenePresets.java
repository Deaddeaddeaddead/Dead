package com.dead.unlockvideo;

/**
 * Сцены на выбор. «Оригинал» — копия ролика (GlassScene, свет снят с видео); «Свет» — копии
 * роликов со спиралями (LightScene); остальные — свои шейдеры из assets/scenes
 * (ProceduralScene). Номера сцен постоянные — по ним сохраняется выбор; в списке — только те,
 * что есть сейчас.
 */
final class ScenePresets {
    static final int ORIGINAL = 0;
    static final int WAVES = 5;
    static final int LIGHT_TEAL = 7;
    static final int LIGHT_VIOLET = 8;
    /** Сцены в порядке показа. */
    static final int[] LIST = {ORIGINAL, LIGHT_TEAL, LIGHT_VIOLET, WAVES};

    static String title(int id) {
        switch (id) {
            case WAVES:
                return "Волны";
            case LIGHT_TEAL:
                return "Бирюзовый свет";
            case LIGHT_VIOLET:
                return "Фиолетовый свет";
            default:
                return "Оригинал";
        }
    }

    /** Шейдер сцены в assets/scenes без расширения (у ORIGINAL и «Света» — свои классы). */
    static String shader(int id) {
        return id == WAVES ? "waves" : null;
    }

    /** Данные сцены «Свет» (assets/scenes/light_<имя>.bin) или null, если сцена другая. */
    static String light(int id) {
        if (id == LIGHT_TEAL) return "teal";
        if (id == LIGHT_VIOLET) return "violet";
        return null;
    }

    /** Сцена берёт цвета из палитры (у «Света» цвета — как в ролике). */
    static boolean colored(int id) {
        return light(id) == null;
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
