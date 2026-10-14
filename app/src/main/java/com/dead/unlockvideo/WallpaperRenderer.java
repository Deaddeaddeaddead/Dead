package com.dead.unlockvideo;

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
 * чтобы не мигнуть устаревшей картинкой.
 */
final class WallpaperRenderer {
    private static final String TAG = "WallpaperRenderer";
    static final int SOURCE_FORWARD = 0;
    static final int SOURCE_REVERSE = 1;
    /** Если новый источник так и не прислал кадр — переключаемся всё равно. */
    private static final long SWITCH_TIMEOUT_MS = 300;

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
        this.zoom = Math.max(1f, zoom);
        this.shiftX = shiftX;
        this.shiftY = shiftY;
        if (drawPosted.compareAndSet(false, true)) handler.post(drawTransform);
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
        Input input = inputs[active];
        if (egl == null || input == null || !input.hasFrame
                || viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float scaleX = 1f;
        float scaleY = 1f;
        if (input.videoWidth > 0 && input.videoHeight > 0) {
            float videoAspect = input.videoWidth / (float) input.videoHeight;
            float viewAspect = viewWidth / (float) viewHeight;
            // Заполняем экран целиком, лишнее обрезаем по краям.
            if (videoAspect > viewAspect) {
                scaleX = viewAspect / videoAspect;
            } else {
                scaleY = videoAspect / viewAspect;
            }
        }
        // Видимая доля кадра с учётом масштаба; сдвиг — в пределах запаса от масштаба.
        float visibleX = scaleX / zoom;
        float visibleY = scaleY / zoom;
        float marginX = (scaleX - visibleX) / 2f;
        float marginY = (scaleY - visibleY) / 2f;
        float offsetX = 0.5f - visibleX / 2f
                + Math.max(-marginX, Math.min(marginX, shiftX * scaleX));
        float offsetY = 0.5f - visibleY / 2f
                + Math.max(-marginY, Math.min(marginY, shiftY * scaleY));
        drawer.draw(input.textureId, input.matrix, viewWidth, viewHeight,
                visibleX, visibleY, offsetX, offsetY);
        egl.swap(-1);
    }
}
