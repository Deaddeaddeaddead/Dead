package com.dead.unlockvideo;

/**
 * Сцены на выбор. «Оригинал» — копия ролика (GlassScene, свет снят с видео); остальные —
 * свои шейдеры из assets/scenes (ProceduralScene). Номера сцен постоянные — по ним
 * сохраняется выбор; в списке — только те, что есть сейчас.
 */
final class ScenePresets {
    static final int ORIGINAL = 0;
    static final int WAVES = 5;
    /** Сцены в порядке показа. */
    static final int[] LIST = {ORIGINAL, WAVES};

    static String title(int id) {
        return id == WAVES ? "Волны" : "Оригинал";
    }

    /** Шейдер сцены в assets/scenes без расширения (у ORIGINAL — свой класс). */
    static String shader(int id) {
        return id == WAVES ? "waves" : null;
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
