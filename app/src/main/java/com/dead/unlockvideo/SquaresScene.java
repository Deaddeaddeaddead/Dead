package com.dead.unlockvideo;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Сцена «Квадраты»: три стеклянных квадрата друг под другом, каждый наезжает на соседа.
 * Свет квадрата перенесён со света нижней фигуры роликов Light02/03 (одна модель на все три:
 * гладкая текстура холста квадрата и таблица канта по контуру — LightData). Квадраты рисуются
 * по очереди от дальнего к ближнему, каждый — поверх того, что уже нарисовано позади: сквозь
 * квадрат видно заднее, ослабленное прозрачностью, через чистое, матовое (размытое) или
 * рифлёное стекло. Глубина у каждого своя: дальний меньше, тусклее, при наклоне телефона
 * сдвигается слабее и лежит под ближними. Только на потоке рендера.
 */
final class SquaresScene implements Scene {
    private static final String TAG = "SquaresScene";
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";
    /** Сторона квадрата модели (пиксели кадра 1120×2436) — у самого ближнего. */
    private static final float SIZE = 920f;
    /** Насколько меньше самый дальний. */
    private static final float DEPTH_SCALE = 0.2f;
    /** Насколько тусклее самый дальний. */
    private static final float DEPTH_DIM = 0.25f;
    /** Сдвиг самого ближнего при параллаксе — доля сдвига точки зрения. */
    private static final float PARALLAX = 0.12f;
    private static final int SQUARES = LightColors.SQUARES;

    private final int layer;
    private final int blur;
    private final int layerPosition;
    private final int blurPosition;
    // Униформы прохода квадрата.
    private final int res;
    private final int crop;
    private final int smoothWeights;
    private final int atlasWeights;
    private final int smoothBias;
    private final int atlasBias;
    private final int background;
    private final int color;
    private final int hasPrev;
    private final int square;
    private final int glass;
    private final int dither;
    private final int[] layerSamplers = new int[5];
    // Униформы размытия.
    private final int blurSrc;
    private final int blurSrcSize;
    private final int blurDir;
    private final int blurRes;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private LightData data;
    private int dataset = -1;
    /**
     * Буферы: два во весь экран (что уже нарисовано — по очереди), два в 1/4 и два в 1/8
     * (размытое для матового стекла).
     */
    private final int[] frames = new int[6];
    private final int[] frameTextures = new int[6];
    private final int[] frameWidth = new int[6];
    private final int[] frameHeight = new int[6];
    private int buffersWidth;
    private int buffersHeight;

    // Раскладка на кадр: центр, масштаб, яркость; порядок — от дальнего.
    private final float[] centerX = new float[SQUARES];
    private final float[] centerY = new float[SQUARES];
    private final float[] scale = new float[SQUARES];
    private final float[] bright = new float[SQUARES];
    private final int[] order = new int[SQUARES];

    SquaresScene() throws IOException {
        AssetManager am = LightScene.assets();
        layer = GlUtil.program(VERTEX_SHADER, GlUtil.asset(am, "scenes/squares.frag"));
        int blurProgram;
        try {
            blurProgram = GlUtil.program(VERTEX_SHADER, GlUtil.asset(am, "scenes/blur.frag"));
        } catch (IOException e) {
            GLES20.glDeleteProgram(layer);
            throw e;
        }
        blur = blurProgram;
        layerPosition = GLES20.glGetAttribLocation(layer, "aPosition");
        blurPosition = GLES20.glGetAttribLocation(blur, "aPosition");
        res = GLES20.glGetUniformLocation(layer, "uRes");
        crop = GLES20.glGetUniformLocation(layer, "uCrop");
        smoothWeights = GLES20.glGetUniformLocation(layer, "uSm");
        atlasWeights = GLES20.glGetUniformLocation(layer, "uAt");
        smoothBias = GLES20.glGetUniformLocation(layer, "uBias");
        atlasBias = GLES20.glGetUniformLocation(layer, "uABias");
        background = GLES20.glGetUniformLocation(layer, "uBg");
        color = GLES20.glGetUniformLocation(layer, "uColor");
        hasPrev = GLES20.glGetUniformLocation(layer, "uHasPrev");
        square = GLES20.glGetUniformLocation(layer, "uSquare");
        glass = GLES20.glGetUniformLocation(layer, "uGlass");
        dither = GLES20.glGetUniformLocation(layer, "uDither");
        String[] names = {"uSmooth", "uAtlas", "uPrev", "uBlur1", "uBlur2"};
        for (int i = 0; i < names.length; i++) {
            layerSamplers[i] = GLES20.glGetUniformLocation(layer, names[i]);
        }
        blurSrc = GLES20.glGetUniformLocation(blur, "uSrc");
        blurSrcSize = GLES20.glGetUniformLocation(blur, "uSrcSize");
        blurDir = GLES20.glGetUniformLocation(blur, "uDir");
        blurRes = GLES20.glGetUniformLocation(blur, "uRes");
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    }

    /**
     * Где квадраты в кадре: сверху вниз, следующий наезжает на предыдущий на долю overlap его
     * стороны; дальние меньше; всё по центру кадра. Сдвиг точки зрения (view, ширина кадра —
     * 1) сдвигает ближние сильнее дальних.
     */
    private void layout(float[] p, float viewX, float viewY) {
        float overlap = p[LightColors.P_OVERLAP];
        float[] size = new float[SQUARES];
        float[] top = new float[SQUARES];
        for (int j = 0; j < SQUARES; j++) {
            float depth = depth(p, j);
            size[j] = SIZE * (1f - DEPTH_SCALE * depth);
            if (j > 0) top[j] = top[j - 1] + (1f - overlap) * size[j - 1];
        }
        float total = top[SQUARES - 1] + size[SQUARES - 1];
        float offset = (GlassScene.FRAME_HEIGHT - total) / 2f;
        for (int j = 0; j < SQUARES; j++) {
            float near = (1f - depth(p, j)) * PARALLAX * GlassScene.FRAME_WIDTH;
            centerX[j] = GlassScene.FRAME_WIDTH / 2f - viewX * near;
            // viewY — вверх, кадр — вниз.
            centerY[j] = offset + top[j] + size[j] / 2f + viewY * near;
            scale[j] = size[j] / SIZE;
            bright[j] = 1f - DEPTH_DIM * depth(p, j);
            order[j] = j;
        }
        // От дальнего к ближнему; при равной глубине — сверху вниз (нижний поверх).
        for (int a = 1; a < SQUARES; a++) {
            for (int b = a; b > 0; b--) {
                int x = order[b - 1];
                int y = order[b];
                if (depth(p, x) < depth(p, y)) {
                    order[b - 1] = y;
                    order[b] = x;
                }
            }
        }
    }

    private static float depth(float[] p, int j) {
        return p[LightColors.P_SQUARES + 4 * j + 3];
    }

    @Override
    public void draw(int width, int height, float[] cropBox, float t, float[] pal, float viewX,
            float viewY) {
        int wanted = (int) pal[LightColors.P_DATASET];
        if (wanted != dataset) {
            if (data != null) data.release();
            data = null;
            dataset = wanted;
            try {
                data = new LightData(LightScene.assets(), "scenes/squares_"
                        + LightColors.DATASETS[wanted] + ".bin");
            } catch (IOException e) {
                Log.w(TAG, "squares data failed", e);
            }
        }
        if (data == null) return;
        data.weights(t);
        buffers(width, height);
        layout(pal, viewX, viewY);
        int prev = -1;
        for (int i = 0; i < SQUARES; i++) {
            int j = order[i];
            int base = LightColors.P_SQUARES + 4 * j;
            boolean last = i == SQUARES - 1;
            boolean blurred = prev >= 0 && pal[base + 1] > 0.5f && pal[base + 2] > 0f;
            if (blurred) blurBehind(prev);
            int target = last ? -1 : i % 2;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target < 0 ? 0 : frames[target]);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(layer);
            GLES20.glUniform2f(res, width, height);
            GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
            GLES20.glUniform4fv(smoothWeights, LightData.LAYERS * 3, data.smoothUniform, 0);
            GLES20.glUniform4fv(atlasWeights, LightData.LAYERS * 3, data.atlasUniform, 0);
            GLES20.glUniform3fv(smoothBias, 1, data.smoothConst, 0);
            GLES20.glUniform3fv(atlasBias, 1, data.atlasConst, 0);
            GLES20.glUniform3fv(background, 1, data.background, 0);
            GLES20.glUniformMatrix3fv(color, 1, false, pal, LightColors.P_COLOR);
            GLES20.glUniform1f(hasPrev, prev >= 0 ? 1f : 0f);
            GLES20.glUniform3f(square, centerX[j], centerY[j], scale[j]);
            GLES20.glUniform4f(glass, pal[base], pal[base + 1], pal[base + 2], bright[j]);
            GLES20.glUniform1f(dither, last ? 1f : 0f);
            int[] textures = {data.smoothTexture, data.atlasTexture,
                    prev >= 0 ? frameTextures[prev] : data.smoothTexture,
                    frameTextures[2], frameTextures[4]};
            for (int k = 0; k < textures.length; k++) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + k);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[k]);
                GLES20.glUniform1i(layerSamplers[k], k);
            }
            quad(layerPosition);
            prev = target;
        }
        for (int k = 4; k >= 0; k--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + k);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * Размытие того, что позади (буфер source): в 1/4 и гаусс — слабое (буфер 2), из него в
     * 1/8 и гаусс — сильное (буфер 4).
     */
    private void blurBehind(int source) {
        GLES20.glUseProgram(blur);
        blurPass(source, 2, 0, 0);
        blurPass(2, 3, 1, 0);
        blurPass(3, 2, 0, 1);
        blurPass(2, 4, 0, 0);
        blurPass(4, 5, 1, 0);
        blurPass(5, 4, 0, 1);
    }

    private void blurPass(int source, int target, int dirX, int dirY) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frames[target]);
        GLES20.glViewport(0, 0, frameWidth[target], frameHeight[target]);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[source]);
        GLES20.glUniform1i(blurSrc, 0);
        GLES20.glUniform2f(blurSrcSize, frameWidth[source], frameHeight[source]);
        GLES20.glUniform2f(blurDir, dirX, dirY);
        GLES20.glUniform2f(blurRes, frameWidth[target], frameHeight[target]);
        quad(blurPosition);
    }

    private void quad(int position) {
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Буферы под размер экрана — пересоздаются, когда он меняется. */
    private void buffers(int width, int height) {
        if (width == buffersWidth && height == buffersHeight) return;
        deleteBuffers();
        buffersWidth = width;
        buffersHeight = height;
        GLES20.glGenFramebuffers(frames.length, frames, 0);
        GLES20.glGenTextures(frameTextures.length, frameTextures, 0);
        for (int i = 0; i < frames.length; i++) {
            int div = i < 2 ? 1 : i < 4 ? 4 : 8;
            frameWidth[i] = Math.max(1, (width + div - 1) / div);
            frameHeight[i] = Math.max(1, (height + div - 1) / div);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextures[i]);
            GlUtil.linearClamp();
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth[i],
                    frameHeight[i], 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frames[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, frameTextures[i], 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "framebuffer " + i + " incomplete: 0x" + Integer.toHexString(status));
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void deleteBuffers() {
        if (buffersWidth == 0) return;
        GLES20.glDeleteFramebuffers(frames.length, frames, 0);
        GLES20.glDeleteTextures(frameTextures.length, frameTextures, 0);
        buffersWidth = 0;
        buffersHeight = 0;
    }

    @Override
    public void release() {
        deleteBuffers();
        if (data != null) data.release();
        data = null;
        GLES20.glDeleteProgram(layer);
        GLES20.glDeleteProgram(blur);
    }
}
