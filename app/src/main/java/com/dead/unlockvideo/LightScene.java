package com.dead.unlockvideo;

import android.content.Context;
import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Сцена «Ленты» — копия роликов Light02/03 (спирали). Кадр — сумма 10 картинок-базисов с
 * весами по времени ролика (первый и последний кадр почти точные — их видно дольше всего).
 * Каждая картинка — гладкая текстура в 1/4 кадра плюс таблицы вдоль кантов лент: по длине
 * канта и по расстоянию до него, с узлами гуще у самой кромки, — поэтому кромки резкие в
 * любом разрешении. Канты лежат полями расстояния и длины (assets/scenes/ribbons_geom.bin),
 * свет — данными бирюзового или фиолетового ролика (LightData), свои цвета — перекраской
 * (LightColors). Всё подобрано МНК по всем пикселям роликов. Только на потоке рендера.
 */
final class LightScene implements Scene {
    private static final String TAG = "LightScene";
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";
    private static AssetManager assets;

    private final int program;
    private final int position;
    private final int res;
    private final int crop;
    private final int smoothWeights;
    private final int atlasWeights;
    private final int smoothBias;
    private final int atlasBias;
    private final int color;
    /** Поля кантов и где они есть. */
    private final int[] geometry = new int[2];
    private final int[] samplers = new int[4];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private LightData data;
    private int dataset = -1;

    /** Запоминает, откуда читать данные сцен, — один раз на процесс, до первой сцены. */
    static void load(Context context) {
        synchronized (LightScene.class) {
            if (assets == null) assets = context.getApplicationContext().getAssets();
        }
    }

    static AssetManager assets() throws IOException {
        synchronized (LightScene.class) {
            if (assets == null) throw new IOException("нет данных сцены «Свет»");
            return assets;
        }
    }

    LightScene() throws IOException {
        AssetManager am = assets();
        program = GlUtil.program(VERTEX_SHADER, GlUtil.asset(am, "scenes/ribbons.frag"));
        position = GLES20.glGetAttribLocation(program, "aPosition");
        res = GLES20.glGetUniformLocation(program, "uRes");
        crop = GLES20.glGetUniformLocation(program, "uCrop");
        smoothWeights = GLES20.glGetUniformLocation(program, "uSm");
        atlasWeights = GLES20.glGetUniformLocation(program, "uAt");
        smoothBias = GLES20.glGetUniformLocation(program, "uBias");
        atlasBias = GLES20.glGetUniformLocation(program, "uABias");
        color = GLES20.glGetUniformLocation(program, "uColor");
        String[] names = {"uField", "uMask", "uSmooth", "uAtlas"};
        for (int i = 0; i < names.length; i++) {
            samplers[i] = GLES20.glGetUniformLocation(program, names[i]);
        }
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        GLES20.glGenTextures(geometry.length, geometry, 0);
        try (DataInputStream in = new DataInputStream(am.open("scenes/ribbons_geom.bin"))) {
            LightData.magic(in, "LGE1");
            int fieldWidth = LightData.int32(in);
            int fieldHeight = LightData.int32(in);
            int maskWidth = LightData.int32(in);
            int maskHeight = LightData.int32(in);
            LightData.upload(geometry[0], in, fieldWidth, fieldHeight);
            LightData.upload(geometry[1], in, maskWidth, maskHeight);
        } catch (IOException | RuntimeException e) {
            release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /**
     * t — момент ролика (0..1). cropBox — видимая часть кадра (как у видео). pal — настройки
     * LightColors (данные света и перекраска). Параллакс — сдвигом кадра.
     */
    @Override
    public void draw(int width, int height, float[] cropBox, float t, float[] pal, float viewX,
            float viewY) {
        int wanted = (int) pal[LightColors.P_DATASET];
        if (wanted != dataset) {
            if (data != null) data.release();
            data = null;
            dataset = wanted;
            try {
                data = new LightData(assets(), "scenes/ribbons_"
                        + LightColors.DATASETS[wanted] + ".bin");
            } catch (IOException e) {
                Log.w(TAG, "ribbons data failed", e);
            }
        }
        if (data == null) return;
        data.weights(t);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(res, width, height);
        GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
        GLES20.glUniform4fv(smoothWeights, LightData.LAYERS * 3, data.smoothUniform, 0);
        GLES20.glUniform4fv(atlasWeights, LightData.LAYERS * 3, data.atlasUniform, 0);
        GLES20.glUniform3fv(smoothBias, 1, data.smoothConst, 0);
        GLES20.glUniform3fv(atlasBias, 1, data.atlasConst, 0);
        GLES20.glUniformMatrix3fv(color, 1, false, pal, LightColors.P_COLOR);
        int[] textures = {geometry[0], geometry[1], data.smoothTexture, data.atlasTexture};
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
        if (data != null) data.release();
        data = null;
        GLES20.glDeleteTextures(geometry.length, geometry, 0);
        GLES20.glDeleteProgram(program);
    }
}
