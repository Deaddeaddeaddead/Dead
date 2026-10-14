package com.dead.unlockvideo;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Сцены «Свет» — копии роликов со спиралями (бирюзовый и фиолетовый свет на одних и тех же
 * фигурах). Сняты с роликов: кадр — сумма 10 картинок-базисов с весами по времени ролика
 * (первый и последний кадр почти точные — их видно дольше всего). Каждая картинка — гладкая
 * текстура в 1/4 кадра плюс таблицы вдоль кантов фигур: по длине канта и по расстоянию до
 * него, с узлами гуще у самой кромки, — поэтому кромки резкие в любом разрешении. Канты
 * лежат полями расстояния и длины (общие для обеих сцен). Всё подобрано МНК по всем
 * пикселям ролика. Данные — assets/scenes/light_*.bin. Только на потоке рендера.
 */
final class LightScene implements Scene {
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";
    /** Слоёв RGBA у гладкой части и у таблиц (по 4 канала: базисы × R, G, B, младшие байты). */
    private static final int LAYERS = 9;
    private static final int CHANNELS = LAYERS * 4;
    private static AssetManager assets;

    private final int program;
    private final int position;
    private final int res;
    private final int crop;
    private final int smoothWeights;
    private final int atlasWeights;
    private final int smoothBias;
    private final int atlasBias;
    /** Поля кантов, где они есть, гладкая часть, таблицы. */
    private final int[] textures = new int[4];
    private final int[] samplers = new int[4];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** Веса базисов по кадрам ролика: frames × bases. */
    private final int bases;
    private final int frames;
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

    private final float[] weight;
    private final float[] smoothUniform = new float[LAYERS * 3 * 4];
    private final float[] atlasUniform = new float[LAYERS * 3 * 4];
    private final float[] smoothConst = new float[3];
    private final float[] atlasConst = new float[3];

    /** Запоминает, откуда читать данные сцен, — один раз на процесс, до первой сцены. */
    static void load(Context context) {
        synchronized (LightScene.class) {
            if (assets == null) assets = context.getApplicationContext().getAssets();
        }
    }

    /** name — сцена в assets/scenes/light_<name>.bin (ScenePresets.light). */
    LightScene(String name) throws IOException {
        AssetManager am;
        synchronized (LightScene.class) {
            am = assets;
        }
        if (am == null || name == null) throw new IOException("нет данных сцены «Свет»");
        String fragment;
        try (InputStream in = am.open("scenes/light.frag")) {
            fragment = new String(readAll(in), StandardCharsets.UTF_8);
        }
        program = GlUtil.program(VERTEX_SHADER, fragment);
        position = GLES20.glGetAttribLocation(program, "aPosition");
        res = GLES20.glGetUniformLocation(program, "uRes");
        crop = GLES20.glGetUniformLocation(program, "uCrop");
        smoothWeights = GLES20.glGetUniformLocation(program, "uSm");
        atlasWeights = GLES20.glGetUniformLocation(program, "uAt");
        smoothBias = GLES20.glGetUniformLocation(program, "uBias");
        atlasBias = GLES20.glGetUniformLocation(program, "uABias");
        String[] names = {"uField", "uMask", "uSmooth", "uAtlas"};
        for (int i = 0; i < names.length; i++) {
            samplers[i] = GLES20.glGetUniformLocation(program, names[i]);
        }
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        GLES20.glGenTextures(textures.length, textures, 0);
        try {
            try (DataInputStream in = new DataInputStream(am.open("scenes/light_geom.bin"))) {
                magic(in, "LGE1");
                int fieldWidth = int32(in);
                int fieldHeight = int32(in);
                int maskWidth = int32(in);
                int maskHeight = int32(in);
                upload(textures[0], in, fieldWidth, fieldHeight);
                upload(textures[1], in, maskWidth, maskHeight);
            }
            try (DataInputStream in = new DataInputStream(
                    am.open("scenes/light_" + name + ".bin"))) {
                magic(in, "LSC1");
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
                upload(textures[2], in, smoothWidth, smoothHeight);
                upload(textures[3], in, atlasWidth, atlasHeight);
            }
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
     * Текстура RGBA8 width×height из потока: в файле каждый байт — разность с тем же каналом
     * соседа слева (так данные хорошо сжимаются в APK).
     */
    private static void upload(int texture, DataInputStream in, int width, int height)
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
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private static void magic(DataInputStream in, String expected) throws IOException {
        byte[] m = new byte[4];
        in.readFully(m);
        if (!expected.equals(new String(m, StandardCharsets.US_ASCII))) {
            throw new IOException("не те данные сцены «Свет»");
        }
    }

    private static int int32(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static float float32(DataInputStream in) throws IOException {
        return Float.intBitsToFloat(int32(in));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /**
     * Веса каналов текстур для момента t (0..1): вес базиса по кадрам ролика (между кадрами —
     * линейно), умноженный на шаг квантования канала; нули квантования — в постоянные.
     */
    private void weights(float t) {
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
        }
        for (int k = 0; k < bases; k++) {
            for (int c = 0; c < 3; c++) {
                smoothConst[c] -= weight[k] * smoothStep[k * 3 + c] * smoothZero[k * 3 + c];
                atlasConst[c] -= weight[k] * atlasStep[k * 3 + c] * atlasZero[k * 3 + c];
            }
        }
    }

    /**
     * t — момент ролика (0..1). cropBox — видимая часть кадра (как у видео). Палитра и сдвиг
     * точки зрения не используются: цвета — как в ролике, параллакс — сдвигом кадра.
     */
    @Override
    public void draw(int width, int height, float[] cropBox, float t, float[] pal, float viewX,
            float viewY) {
        weights(t);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(res, width, height);
        GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
        GLES20.glUniform4fv(smoothWeights, LAYERS * 3, smoothUniform, 0);
        GLES20.glUniform4fv(atlasWeights, LAYERS * 3, atlasUniform, 0);
        GLES20.glUniform3fv(smoothBias, 1, smoothConst, 0);
        GLES20.glUniform3fv(atlasBias, 1, atlasConst, 0);
        for (int i = 0; i < textures.length; i++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
            GLES20.glUniform1i(samplers[i], i);
        }
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        for (int i = textures.length - 1; i >= 0; i--) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }
    }

    @Override
    public void release() {
        GLES20.glDeleteTextures(textures.length, textures, 0);
        GLES20.glDeleteProgram(program);
    }
}
