package com.dead.unlockvideo;

import android.content.res.AssetManager;
import android.opengl.GLES20;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Данные света сцен «Ленты» и «Квадраты» (assets/scenes/*.bin, формат LSC2): 10 картинок-
 * базисов и их веса по кадрам ролика. Каждая картинка — гладкая текстура (3×3 слоя RGBA) и
 * таблицы вдоль кантов; базисы 0 и 1 — 16 бит (старший и младший байт), остальные — 8 бит.
 * Кадр в момент t — сумма базисов с весами; веса каналов текстур для шейдера считает weights.
 * Только на потоке рендера.
 */
final class LightData {
    /** Слоёв RGBA у гладкой части и у таблиц. */
    static final int LAYERS = 9;
    private static final int CHANNELS = LAYERS * 4;

    final int smoothTexture;
    final int atlasTexture;
    private final int bases;
    private final int frames;
    /** Веса базисов по кадрам: frames × bases. */
    private final float[] coef;
    /** Канал текстуры: базис, цвет (0..2), часть (0 — 8 бит, 1 — старший байт, 2 — младший). */
    private final int[] chanBasis = new int[CHANNELS];
    private final int[] chanColor = new int[CHANNELS];
    private final int[] chanPart = new int[CHANNELS];
    /** Шаг и ноль квантования: [базис·3 + цвет], у гладкой части и у таблиц. */
    private final float[] smoothStep;
    private final float[] smoothZero;
    private final float[] atlasStep;
    private final float[] atlasZero;
    /** Фон по базисам: [базис·3 + цвет]. */
    private final float[] backgroundBasis;

    private final float[] weight;
    /** Для шейдера: вклад слоя в цвет [3·слой + канал] (vec4), постоянные и фон. */
    final float[] smoothUniform = new float[LAYERS * 3 * 4];
    final float[] atlasUniform = new float[LAYERS * 3 * 4];
    final float[] smoothConst = new float[3];
    final float[] atlasConst = new float[3];
    final float[] background = new float[3];

    LightData(AssetManager assets, String path) throws IOException {
        int[] ids = new int[2];
        GLES20.glGenTextures(2, ids, 0);
        smoothTexture = ids[0];
        atlasTexture = ids[1];
        try (DataInputStream in = new DataInputStream(assets.open(path))) {
            magic(in, "LSC2");
            bases = int32(in);
            frames = int32(in);
            int channels = int32(in);
            int smoothWidth = int32(in);
            int smoothHeight = int32(in);
            int atlasWidth = int32(in);
            int atlasHeight = int32(in);
            int32(in);
            if (channels != CHANNELS || frames < 2 || bases < 1) {
                throw new IOException("данные сцены «Свет» другой версии");
            }
            coef = new float[frames * bases];
            for (int i = 0; i < coef.length; i++) coef[i] = float32(in);
            for (int ch = 0; ch < CHANNELS; ch++) {
                chanBasis[ch] = int32(in);
                chanColor[ch] = int32(in);
                chanPart[ch] = int32(in);
            }
            smoothStep = new float[bases * 3];
            smoothZero = new float[bases * 3];
            atlasStep = new float[bases * 3];
            atlasZero = new float[bases * 3];
            readQuant(in, smoothStep, smoothZero);
            readQuant(in, atlasStep, atlasZero);
            backgroundBasis = new float[bases * 3];
            for (int i = 0; i < backgroundBasis.length; i++) backgroundBasis[i] = float32(in);
            upload(smoothTexture, in, smoothWidth, smoothHeight);
            upload(atlasTexture, in, atlasWidth, atlasHeight);
        } catch (IOException | RuntimeException e) {
            release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        weight = new float[bases];
    }

    private void readQuant(DataInputStream in, float[] step, float[] zero) throws IOException {
        for (int i = 0; i < bases * 3; i++) {
            int k = int32(in);
            int c = int32(in);
            step[k * 3 + c] = float32(in);
            zero[k * 3 + c] = float32(in);
        }
    }

    /**
     * Веса каналов текстур для момента t (0..1): вес базиса по кадрам ролика (между кадрами —
     * линейно), умноженный на шаг квантования канала; нули квантования — в постоянные.
     */
    void weights(float t) {
        float x = Math.max(0f, Math.min(1f, t)) * (frames - 1);
        int i = Math.min(frames - 2, (int) x);
        float f = x - i;
        for (int k = 0; k < bases; k++) {
            float a = coef[i * bases + k];
            weight[k] = a + (coef[(i + 1) * bases + k] - a) * f;
        }
        for (int ch = 0; ch < CHANNELS; ch++) {
            int k = chanBasis[ch];
            int c = chanColor[ch];
            // Текстура отдаёт байт / 255; у старшего байта 16-битного значения — ещё × 256.
            float scale = chanPart[ch] == 1 ? 65280f : 255f;
            int index = (3 * (ch >> 2) + c) * 4 + (ch & 3);
            smoothUniform[index] = weight[k] * smoothStep[k * 3 + c] * scale;
            atlasUniform[index] = weight[k] * atlasStep[k * 3 + c] * scale;
        }
        for (int c = 0; c < 3; c++) {
            smoothConst[c] = 0f;
            atlasConst[c] = 0f;
            background[c] = 0f;
        }
        for (int k = 0; k < bases; k++) {
            for (int c = 0; c < 3; c++) {
                int j = k * 3 + c;
                smoothConst[c] -= weight[k] * smoothStep[j] * smoothZero[j];
                atlasConst[c] -= weight[k] * atlasStep[j] * atlasZero[j];
                background[c] += weight[k] * backgroundBasis[j];
            }
        }
    }

    void release() {
        GLES20.glDeleteTextures(2, new int[] {smoothTexture, atlasTexture}, 0);
    }

    /**
     * Текстура RGBA8 width×height из потока: в файле каждый байт — разность с тем же каналом
     * соседа слева (так данные хорошо сжимаются в APK).
     */
    static void upload(int texture, DataInputStream in, int width, int height)
            throws IOException {
        int row = width * 4;
        byte[] data = new byte[row * height];
        in.readFully(data);
        for (int y = 0; y < height; y++) {
            int o = y * row;
            for (int i = o + 4; i < o + row; i++) data[i] += data[i - 4];
        }
        ByteBuffer pixels = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        pixels.put(data).position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GlUtil.linearClamp();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    static void magic(DataInputStream in, String expected) throws IOException {
        byte[] m = new byte[4];
        in.readFully(m);
        if (!expected.equals(new String(m, StandardCharsets.US_ASCII))) {
            throw new IOException("не те данные сцены «Свет»");
        }
    }

    static int int32(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    static float float32(DataInputStream in) throws IOException {
        return Float.intBitsToFloat(int32(in));
    }
}
