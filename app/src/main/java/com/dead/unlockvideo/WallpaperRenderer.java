package com.dead.unlockvideo;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Рисует кадры видео на поверхность обоев через OpenGL на своём потоке: обрезает видео
 * под экран, масштабирует и сдвигает его (эффекты). Источников два — оригинал и обратное видео, на экран
 * выводится один; переключение происходит, когда у нового источника появляется кадр,
 * чтобы не мигнуть устаревшей картинкой. Пока у видео нет кадра, показывается неподвижный
 * кадр-картинка (если задан). В режиме «Сцена» вместо видео рисуется сцена (ScenePresets):
 * кадр по ходу её часов, покадрово, только пока она движется.
 */
final class WallpaperRenderer {
    private static final String TAG = "WallpaperRenderer";
    static final int SOURCE_FORWARD = 0;
    static final int SOURCE_REVERSE = 1;
    /** Если новый источник так и не прислал кадр — переключаемся всё равно. */
    private static final long SWITCH_TIMEOUT_MS = 300;
    /** Порог изменения масштаба и сдвига (доля кадра) — около трети пикселя. */
    private static final float TRANSFORM_EPSILON = 0.0003f;
    /**
     * Параллакс сцены: во сколько раз сдвиг точки зрения (в единицах сцены) больше сдвига
     * кадра. На 100% кадр сдвигается до ~5% ширины, точка зрения — до ~0.25: стёкла уходят
     * относительно капсулы примерно на 1.5% ширины, и заметно меняется вид сквозь них.
     */
    private static final float SCENE_VIEW_PER_SHIFT = 4.7f;

    private final HandlerThread thread = new HandlerThread("wallpaper-gl");
    private final Handler handler;
    private final Input[] inputs = new Input[2];

    // Всё ниже трогается только на потоке рендера.
    private GlUtil.Egl egl;
    private GlUtil.FrameDrawer drawer;
    private int viewWidth;
    private int viewHeight;
    private int active = SOURCE_FORWARD;
    // Добавочный масштаб (параллакс) и сдвиг (в долях видимого кадра) — пишутся с главного
    // потока; масштаб от разблокировки — scale, считается на каждом кадре.
    private volatile float zoom;
    private volatile float shiftX;
    private volatile float shiftY;
    private volatile ScaleAnimation scale = new ScaleAnimation(1f, 1f, 0, 0, 0f, 0f);
    /** Масштаб этого кадра — считается в начале draw. */
    private float frameZoom = 1f;
    private final AtomicBoolean drawPosted = new AtomicBoolean();
    private final Runnable drawTransform = () -> {
        drawPosted.set(false);
        // Кадры и так идут (сцена или масштаб движутся) — следующий возьмёт новый масштаб и
        // сдвиг; лишний кадр между ними сбил бы ровный ход.
        if (this.frameScheduled) return;
        draw();
    };
    private final Runnable drawAnimatedFrame = this::drawAnimated;

    /**
     * Масштаб от разблокировки: от from к to с момента start (SystemClock.uptimeMillis) за
     * duration мс — в такт анимации сцены, пока её время идёт от sceneFrom к sceneTo
     * (Easing.sceneSegment), или мягко (sceneFrom == sceneTo).
     */
    static final class ScaleAnimation {
        final float from;
        final float to;
        final long start;
        final int duration;
        final float sceneFrom;
        final float sceneTo;

        ScaleAnimation(float from, float to, long start, int duration, float sceneFrom,
                float sceneTo) {
            this.from = from;
            this.to = to;
            this.start = start;
            this.duration = duration;
            this.sceneFrom = sceneFrom;
            this.sceneTo = sceneTo;
        }

        float at(long now) {
            if (duration <= 0 || now >= start + duration) return to;
            return from + (to - from) * eased(Math.max(0f, (now - start) / (float) duration));
        }

        float eased(float t) {
            return sceneFrom != sceneTo ? Easing.sceneSegment(t, sceneFrom, sceneTo)
                    : Easing.soft(t, Easing.ZOOM_RAMP);
        }

        boolean running(long now) {
            return duration > 0 && now < start + duration && from != to;
        }

        boolean same(ScaleAnimation other) {
            return from == other.from && to == other.to && start == other.start
                    && duration == other.duration && sceneFrom == other.sceneFrom
                    && sceneTo == other.sceneTo;
        }
    }
    private int pending = -1;
    /** Неподвижный кадр (текстура) и его размер; 0 — нет. */
    private int stillTexture;
    private int stillWidth;
    private int stillHeight;
    /** Обрезка под экран: доля кадра по X и Y, левый нижний угол. */
    private final float[] crop = new float[4];

    /** Ход анимации сцены для источника — читается потоком рендера. */
    interface SceneSource {
        /** 0..1 — кадр сцены. */
        float progress();

        /** Сцена движется — нужны новые кадры. */
        boolean animating();
    }

    /** Сообщения об ошибках рендера — в журнал обоев (вызывается на потоке рендера). */
    interface ErrorListener {
        void onError(String message);
    }

    private volatile ErrorListener errorListener;

    void setErrorListener(ErrorListener listener) {
        errorListener = listener;
    }

    /** Палитра сцены; null — режим видео. */
    private float[] scenePalette;
    /** Какая сцена (ScenePresets.*) и её шейдер — создаётся при первом кадре. */
    private int sceneId = ScenePresets.ORIGINAL;
    private Scene scene;
    private final SceneSource[] sceneSources = new SceneSource[2];
    private Choreographer choreographer;
    private boolean frameScheduled;
    private final Choreographer.FrameCallback sceneFrame = time -> sceneTick();
    /** На AOD кадры экрана идут редко или не идут — тогда рисуем по таймеру. */
    private final Runnable sceneFrameFallback = this::sceneTick;

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
            if (scenePalette != null) {
                // У сцены кадр есть всегда — переключаемся сразу.
                active = source;
                pending = -1;
                handler.removeCallbacks(forceSwitch);
                drawScene();
                return;
            }
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
     * Добавочный масштаб (0 — нет; складывается с масштабом от разблокировки) и сдвиг
     * изображения в долях кадра без масштаба. Сдвиг ограничивается так, чтобы края кадра не
     * показывались. Можно вызывать часто — лишние перерисовки схлопываются.
     */
    void setTransform(float zoom, float shiftX, float shiftY) {
        float z = Math.max(0f, zoom);
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

    /** Масштаб от разблокировки — сразу value. */
    void setScale(float value) {
        animateScale(new ScaleAnimation(value, value, 0, 0, 0f, 0f));
    }

    /**
     * Масштаб от разблокировки — переход (см. ScaleAnimation). Считается на каждом кадре,
     * кадры идут, пока он меняется, — ровно, в такт экрану.
     */
    void animateScale(ScaleAnimation next) {
        if (next.same(scale)) return;
        scale = next;
        handler.post(drawAnimatedFrame);
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

    /**
     * Режим «Сцена»: сцена id (ScenePresets.*) с настройками palette (ScenePresets.params);
     * palette null — обратно к видео.
     */
    void setScene(int id, float[] palette) {
        float[] copy = palette == null ? null : palette.clone();
        handler.post(() -> {
            if (id == sceneId && Arrays.equals(scenePalette, copy)) return;
            if (id != sceneId) {
                // Другая сцена — свой шейдер; прежний освобождаем.
                if (scene != null && egl != null) scene.release();
                scene = null;
                sceneId = id;
            }
            scenePalette = copy;
            draw();
        });
    }

    /** Часы сцены для источника (вперёд или назад). null — убрать. */
    void setSceneSource(int source, SceneSource clock) {
        handler.post(() -> {
            sceneSources[source] = clock;
            if (source == active) drawScene();
        });
    }

    /** Ход сцены изменился (пуск, пауза, перемотка) — новый кадр и, если движется, следующие. */
    void requestSceneFrame() {
        handler.post(this::drawScene);
    }

    private void drawScene() {
        if (scenePalette == null) return;
        drawAnimated();
    }

    /** Кадр и, если сцена или масштаб движутся, следующие — по кадрам экрана. */
    private void drawAnimated() {
        draw();
        if (needsFrames()) scheduleSceneFrame();
    }

    private boolean needsFrames() {
        if (scale.running(SystemClock.uptimeMillis())) return true;
        if (scenePalette == null) return false;
        SceneSource source = sceneSources[active];
        return source != null && source.animating();
    }

    private void scheduleSceneFrame() {
        if (frameScheduled) return;
        frameScheduled = true;
        if (choreographer == null) choreographer = Choreographer.getInstance();
        choreographer.postFrameCallback(sceneFrame);
        handler.postDelayed(sceneFrameFallback, 50);
    }

    private void sceneTick() {
        if (!frameScheduled) return;
        frameScheduled = false;
        choreographer.removeFrameCallback(sceneFrame);
        handler.removeCallbacks(sceneFrameFallback);
        drawAnimated();
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
            handler.removeCallbacks(sceneFrameFallback);
            if (choreographer != null) choreographer.removeFrameCallback(sceneFrame);
            frameScheduled = false;
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
        frameZoom = Math.max(1f, scale.at(SystemClock.uptimeMillis()) + zoom);
        if (scenePalette != null) {
            SceneSource source = sceneSources[active];
            if (source == null) source = sceneSources[SOURCE_FORWARD];
            if (source == null) return;
            if (scene == null) {
                try {
                    scene = sceneId == ScenePresets.ORIGINAL ? new GlassScene()
                            : sceneId == ScenePresets.RIBBONS ? new LightScene()
                            : sceneId == ScenePresets.SQUARES ? new SquaresScene()
                            : new ProceduralScene(ScenePresets.shader(sceneId));
                } catch (java.io.IOException e) {
                    Log.w(TAG, "scene shader failed", e);
                    ErrorListener listener = errorListener;
                    if (listener != null) listener.onError("сцена не запустилась: " + e.getMessage());
                    scenePalette = null;
                    return;
                }
            }
            deleteStill();
            computeCrop(GlassScene.FRAME_WIDTH, GlassScene.FRAME_HEIGHT);
            scene.draw(viewWidth, viewHeight, crop, source.progress(), scenePalette,
                    shiftX * SCENE_VIEW_PER_SHIFT,
                    shiftY * SCENE_VIEW_PER_SHIFT * GlassScene.FRAME_HEIGHT / GlassScene.FRAME_WIDTH);
            egl.swap(-1);
            return;
        }
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
        float visibleX = scaleX / frameZoom;
        float visibleY = scaleY / frameZoom;
        float marginX = (scaleX - visibleX) / 2f;
        float marginY = (scaleY - visibleY) / 2f;
        crop[0] = visibleX;
        crop[1] = visibleY;
        crop[2] = 0.5f - visibleX / 2f + Math.max(-marginX, Math.min(marginX, shiftX * scaleX));
        crop[3] = 0.5f - visibleY / 2f + Math.max(-marginY, Math.min(marginY, shiftY * scaleY));
    }
}
