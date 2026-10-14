package com.dead.unlockvideo;

/** Сцена режима «Сцена»: рисует кадр шейдером. Создаётся и рисуется на потоке рендера. */
interface Scene {
    /**
     * t — момент анимации (0..1). cropBox — видимая часть кадра сцены (как у видео): доля по
     * X и Y, левый нижний угол. pal — палитра (GlassScene.palette). viewX, viewY — сдвиг точки
     * зрения для параллакса, в единицах сцены (ширина кадра — 1).
     */
    void draw(int width, int height, float[] cropBox, float t, float[] pal, float viewX,
            float viewY);

    /** Освободить ресурсы GL (на том же потоке, пока контекст жив). */
    void release();
}
