package com.dead.unlockvideo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Рисует кадры видео на поверхность обоев через OpenGL на своём потоке: обрезает видео
 * под экран, масштабирует и сдвигает его (эффекты). Источников два — оригинал и обратное видео, на экран
 * выводится один; переключение происходит, когда у нового источника появляется кадр,
 * чтобы не мигнуть устаревшей картинкой. Пока у видео нет кадра, показывается неподвижный
 * кадр-картинка (если задан).
 */
final class WallpaperRenderer {
    private static final String TAG = "WallpaperRenderer";
    static final int SOURCE_FORWARD = 0;
    static final int SOURCE_REVERSE = 1;
    /** Если новый источник так и не прислал кадр — переключаемся всё равно. */
    private static final long SWITCH_TIMEOUT_MS = 300;
    /** Порог изменения масштаба и сдвига (доля кадра) — около трети пикселя. */
    private static final float TRANSFORM_EPSILON = 0.0003f;

    private final HandlerThread thread = new HandlerThread("wallpaper-gl");
    private final Handler handler;
    private final Input[] inputs = new Input[2];

    // Всё ниже трогается только на потоке рендера.
    private GlUtil.Egl egl;
    private GlUtil.FrameDrawer drawer;
    private int viewWidth;
    private int viewHeight;
    private int active = SOURCE_FORWARD;
    // Масштаб и сдвиг (в долях видимого кадра) — пишутся с главного потока.
    private volatile float zoom = 1f;
    private volatile float shiftX;
    private volatile float shiftY;
    private final AtomicBoolean drawPosted = new AtomicBoolean();
    private final Runnable drawTransform = () -> {
        drawPosted.set(false);
        draw();
    };
    private int pending = -1;
    /** Неподвижный кадр (текстура) и его размер; 0 — нет. */
    private int stillTexture;
    private int stillWidth;
    private int stillHeight;
    /** Обрезка под экран: доля кадра по X и Y, левый нижний угол. */
    private final float[] crop = new float[4];

    private final Runnable forceSwitch = () -> {
        if (pending >= 0) {
            active = pending;
            pending = -1;
            draw();
        }
    };

    /** Один источник видео: своя текстура и поверхность для MediaPlayer. */
    private final class Input implements SurfaceTexture.OnFrameAvailableListener {
        final int index;
        final int textureId;
        final SurfaceTexture texture;
        final Surface surface;
        final float[] matrix = new float[16];
        boolean hasFrame;
        int videoWidth;
        int videoHeight;

        Input(int index) {
            this.index = index;
            textureId = GlUtil.FrameDrawer.createExternalTexture();
            texture = new SurfaceTexture(textureId);
            texture.setOnFrameAvailableListener(this, handler);
            surface = new Surface(texture);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            if (egl == null) return;
            try {
                texture.updateTexImage();
            } catch (RuntimeException e) {
                Log.w(TAG, "updateTexImage failed", e);
                return;
            }
            texture.getTransformMatrix(matrix);
            hasFrame = true;
            if (index == pending) {
                handler.removeCallbacks(forceSwitch);
                active = index;
                pending = -1;
            }
            if (index == active) draw();
        }

        void release() {
            surface.release();
            texture.release();
        }
    }

    private WallpaperRenderer() {
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Создаёт рендер и ждёт его готовности. null — если OpenGL не поднялся. */
    static WallpaperRenderer create(Surface target, int width, int height) {
        WallpaperRenderer renderer = new WallpaperRenderer();
        CountDownLatch ready = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        renderer.handler.post(() -> {
            ok[0] = renderer.init(target, width, height);
            ready.countDown();
        });
        try {
            ready.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!ok[0]) {
            renderer.release();
            return null;
        }
        return renderer;
    }

    private boolean init(Surface target, int width, int height) {
        try {
            egl = new GlUtil.Egl(target, false);
            drawer = new GlUtil.FrameDrawer();
            inputs[SOURCE_FORWARD] = new Input(SOURCE_FORWARD);
            inputs[SOURCE_REVERSE] = new Input(SOURCE_REVERSE);
            viewWidth = width;
            viewHeight = height;
            drawer.clear(width, height);
            egl.swap(-1);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "OpenGL init failed", e);
            egl = null;
            return false;
        }
    }

    /** Поверхность, в которую MediaPlayer выводит кадры этого источника. */
    Surface videoSurface(int source) {
        return inputs[source].surface;
    }

    /** Показать источник, как только у него появится свежий кадр. */
    void showSource(int source) {
        handler.post(() -> {
            if (source == active) {
                if (pending >= 0) {
                    handler.removeCallbacks(forceSwitch);
                    pending = -1;
                }
                return;
            }
            pending = source;
            handler.removeCallbacks(forceSwitch);
            handler.postDelayed(forceSwitch, SWITCH_TIMEOUT_MS);
        });
    }

    void setViewSize(int width, int height) {
        handler.post(() -> {
            viewWidth = width;
            viewHeight = height;
            draw();
        });
    }

    /**
     * Масштаб (1 — как есть) и сдвиг изображения в долях кадра без масштаба. Сдвиг
     * ограничивается так, чтобы края кадра не показывались. Можно вызывать часто —
     * лишние перерисовки схлопываются.
     */
    void setTransform(float zoom, float shiftX, float shiftY) {
        float z = Math.max(1f, zoom);
        // Изменение меньше доли пикселя не видно — не тратим на него перерисовку.
        if (Math.abs(z - this.zoom) < TRANSFORM_EPSILON
                && Math.abs(shiftX - this.shiftX) < TRANSFORM_EPSILON
                && Math.abs(shiftY - this.shiftY) < TRANSFORM_EPSILON) {
            return;
        }
        this.zoom = z;
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        if (drawPosted.compareAndSet(false, true)) handler.post(drawTransform);
    }

    /**
     * Неподвижный кадр — виден, пока у видео нет своего кадра (в превью — всегда).
     * Картинку забирает и освобождает сам.
     */
    void showStill(Bitmap bitmap) {
        boolean posted = handler.post(() -> {
            Input input = inputs[active];
            if (egl == null || (input != null && input.hasFrame)) {
                bitmap.recycle();
                return;
            }
            deleteStill();
            stillTexture = GlUtil.FrameDrawer.createTexture(bitmap);
            stillWidth = bitmap.getWidth();
            stillHeight = bitmap.getHeight();
            bitmap.recycle();
            draw();
        });
        if (!posted) bitmap.recycle();
    }

    private void deleteStill() {
        if (stillTexture == 0) return;
        GlUtil.FrameDrawer.deleteTexture(stillTexture);
        stillTexture = 0;
    }

    void setVideoSize(int source, int width, int height) {
        handler.post(() -> {
            Input input = inputs[source];
            if (input == null) return;
            input.videoWidth = width;
            input.videoHeight = height;
            if (source == active) draw();
        });
    }

    void release() {
        CountDownLatch done = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            handler.removeCallbacks(forceSwitch);
            for (int i = 0; i < inputs.length; i++) {
                if (inputs[i] != null) inputs[i].release();
                inputs[i] = null;
            }
            if (egl != null) egl.release();
            egl = null;
            done.countDown();
        });
        if (posted) {
            try {
                done.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread.quitSafely();
    }

    private void draw() {
        if (egl == null || viewWidth <= 0 || viewHeight <= 0) return;
        Input input = inputs[active];
        if (input != null && input.hasFrame) {
            // Видео пошло — картинка больше не нужна.
            deleteStill();
            computeCrop(input.videoWidth, input.videoHeight);
            drawer.draw(input.textureId, input.matrix, viewWidth, viewHeight,
                    crop[0], crop[1], crop[2], crop[3]);
        } else if (stillTexture != 0) {
            computeCrop(stillWidth, stillHeight);
            try {
                drawer.drawImage(stillTexture, viewWidth, viewHeight,
                        crop[0], crop[1], crop[2], crop[3]);
            } catch (java.io.IOException e) {
                Log.w(TAG, "still shader failed", e);
                deleteStill();
                return;
            }
        } else {
            return;
        }
        egl.swap(-1);
    }

    /** Кадр width×height закрывает экран целиком (лишнее обрезается), плюс масштаб и сдвиг. */
    private void computeCrop(int width, int height) {
        float scaleX = 1f;
        float scaleY = 1f;
        if (width > 0 && height > 0) {
            float frameAspect = width / (float) height;
            float viewAspect = viewWidth / (float) viewHeight;
            if (frameAspect > viewAspect) {
                scaleX = viewAspect / frameAspect;
            } else {
                scaleY = frameAspect / viewAspect;
            }
        }
        // Видимая доля кадра с учётом масштаба; сдвиг — в пределах запаса от масштаба.
        float visibleX = scaleX / zoom;
        float visibleY = scaleY / zoom;
        float marginX = (scaleX - visibleX) / 2f;
        float marginY = (scaleY - visibleY) / 2f;
        crop[0] = visibleX;
        crop[1] = visibleY;
        crop[2] = 0.5f - visibleX / 2f + Math.max(-marginX, Math.min(marginX, shiftX * scaleX));
        crop[3] = 0.5f - visibleY / 2f + Math.max(-marginY, Math.min(marginY, shiftY * scaleY));
    }
}
