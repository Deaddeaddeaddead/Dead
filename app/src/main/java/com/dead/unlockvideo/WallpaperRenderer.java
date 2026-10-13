package com.dead.unlockvideo;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Рисует кадры MediaPlayer на поверхность обоев через OpenGL на своём потоке:
 * обрезает видео под экран и умеет приближать его.
 */
final class WallpaperRenderer implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "WallpaperRenderer";

    private final HandlerThread thread = new HandlerThread("wallpaper-gl");
    private final Handler handler;
    private final float[] matrix = new float[16];

    // Всё ниже трогается только на потоке рендера.
    private GlUtil.Egl egl;
    private GlUtil.FrameDrawer drawer;
    private SurfaceTexture texture;
    private Surface videoSurface;
    private int viewWidth;
    private int viewHeight;
    private int videoWidth;
    private int videoHeight;
    private float zoom = 1f;
    private boolean hasFrame;

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
            texture = new SurfaceTexture(drawer.textureId);
            texture.setOnFrameAvailableListener(this, handler);
            videoSurface = new Surface(texture);
            viewWidth = width;
            viewHeight = height;
            drawer.clear(width, height);
            egl.swap(-1);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "OpenGL init failed", e);
            return false;
        }
    }

    /** Поверхность, в которую MediaPlayer выводит кадры. */
    Surface videoSurface() {
        return videoSurface;
    }

    void setViewSize(int width, int height) {
        handler.post(() -> {
            viewWidth = width;
            viewHeight = height;
            draw();
        });
    }

    void setVideoSize(int width, int height) {
        handler.post(() -> {
            videoWidth = width;
            videoHeight = height;
            draw();
        });
    }

    /** 1 — без приближения. */
    void setZoom(float value) {
        handler.post(() -> {
            if (zoom == value) return;
            zoom = value;
            draw();
        });
    }

    void release() {
        CountDownLatch done = new CountDownLatch(1);
        boolean posted = handler.post(() -> {
            if (videoSurface != null) videoSurface.release();
            if (texture != null) texture.release();
            if (egl != null) egl.release();
            videoSurface = null;
            texture = null;
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

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        if (texture == null) return;
        try {
            texture.updateTexImage();
        } catch (RuntimeException e) {
            Log.w(TAG, "updateTexImage failed", e);
            return;
        }
        hasFrame = true;
        draw();
    }

    private void draw() {
        if (egl == null || !hasFrame || viewWidth <= 0 || viewHeight <= 0) return;
        texture.getTransformMatrix(matrix);
        float scaleX = 1f;
        float scaleY = 1f;
        if (videoWidth > 0 && videoHeight > 0) {
            float videoAspect = videoWidth / (float) videoHeight;
            float viewAspect = viewWidth / (float) viewHeight;
            // Заполняем экран целиком, лишнее обрезаем по краям.
            if (videoAspect > viewAspect) {
                scaleX = viewAspect / videoAspect;
            } else {
                scaleY = videoAspect / viewAspect;
            }
        }
        drawer.draw(matrix, viewWidth, viewHeight, scaleX / zoom, scaleY / zoom);
        egl.swap(-1);
    }
}
