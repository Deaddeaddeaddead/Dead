package com.dead.unlockvideo;

import android.content.Context;
import android.opengl.GLES20;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Сцена с расчётным светом (ScenePresets): шейдер из assets/scenes, формы и их ход при
 * разблокировке — из пресета. Положение форм на кадр считается здесь, шейдер только рисует.
 */
final class ProceduralScene implements Scene {
    private static final String[] SHADERS = {"glass", "waves", "aurora"};
    private static final int MAX_LIGHTS = 4;
    private static final int MAX_GLASS = 6;
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";
    private static final Map<String, String> sources = new HashMap<>();

    private final ScenePresets.Preset preset;
    private final int program;
    private final int position;
    private final int res;
    private final int crop;
    private final int progress;
    private final int palette;
    private final int glassColor;
    private final int glassTint;
    private final int view;
    private final int lightCount;
    private final int lightA;
    private final int lightB;
    private final int lightC;
    private final int glassCount;
    private final int glassA;
    private final int glassB;
    private final int glassC;
    private final float[] lights = new float[MAX_LIGHTS * 4 * 3];
    private final float[] glass = new float[MAX_GLASS * 4 * 3];
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** Читает шейдеры сцен из assets — один раз на процесс, до первой сцены. */
    static void load(Context context) {
        synchronized (sources) {
            if (!sources.isEmpty()) return;
            for (String name : SHADERS) {
                try (InputStream in = context.getApplicationContext().getAssets()
                        .open("scenes/" + name + ".frag")) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                    sources.put(name, out.toString(StandardCharsets.UTF_8.name()));
                } catch (IOException ignored) {
                    // Без шейдера сцена не создастся и сообщит об ошибке.
                }
            }
        }
    }

    ProceduralScene(ScenePresets.Preset preset) throws IOException {
        this.preset = preset;
        String source;
        synchronized (sources) {
            source = sources.get(preset.shader);
        }
        if (source == null) throw new IOException("нет шейдера сцены " + preset.shader);
        program = GlUtil.program(VERTEX_SHADER, source);
        position = GLES20.glGetAttribLocation(program, "aPosition");
        res = GLES20.glGetUniformLocation(program, "uRes");
        crop = GLES20.glGetUniformLocation(program, "uCrop");
        progress = GLES20.glGetUniformLocation(program, "uProgress");
        palette = GLES20.glGetUniformLocation(program, "uPal");
        glassColor = GLES20.glGetUniformLocation(program, "uGlass");
        glassTint = GLES20.glGetUniformLocation(program, "uTint");
        view = GLES20.glGetUniformLocation(program, "uView");
        lightCount = GLES20.glGetUniformLocation(program, "uLightCount");
        lightA = GLES20.glGetUniformLocation(program, "uLightA");
        lightB = GLES20.glGetUniformLocation(program, "uLightB");
        lightC = GLES20.glGetUniformLocation(program, "uLightC");
        glassCount = GLES20.glGetUniformLocation(program, "uGlassCount");
        glassA = GLES20.glGetUniformLocation(program, "uGlassA");
        glassB = GLES20.glGetUniformLocation(program, "uGlassB");
        glassC = GLES20.glGetUniformLocation(program, "uGlassC");
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    }

    @Override
    public void draw(int width, int height, float[] cropBox, float t, float[] pal, float viewX,
            float viewY) {
        float clamped = Math.max(0f, Math.min(1f, t));
        float rest = 1f - clamped;
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(res, width, height);
        GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
        GLES20.glUniform1f(progress, 1f - rest * rest * rest);
        GLES20.glUniform3fv(palette, 4, pal, 0);
        GLES20.glUniform3fv(glassColor, 2, pal, 12);
        GLES20.glUniform2f(glassTint, pal[18], pal[19]);
        GLES20.glUniform2f(view, viewX, viewY);
        if (lightCount >= 0) {
            int n = Math.min(MAX_LIGHTS, preset.lights.length);
            for (int i = 0; i < n; i++) {
                ScenePresets.Shape s = preset.lights[i];
                float e = s.progress(clamped);
                put(lights, i, 0, lerp(s.fromX, s.toX, e), lerp(s.fromY, s.toY, e),
                        lerp(s.fromAngle, s.toAngle, e), s.extra[0]);
                put(lights, i, 1, s.halfX, s.halfY, s.round, 0f);
                put(lights, i, 2, s.extra[1], s.extra[2], 0f, 0f);
            }
            GLES20.glUniform1i(lightCount, n);
            GLES20.glUniform4fv(lightA, MAX_LIGHTS, lights, 0);
            GLES20.glUniform4fv(lightB, MAX_LIGHTS, lights, MAX_LIGHTS * 4);
            GLES20.glUniform4fv(lightC, MAX_LIGHTS, lights, MAX_LIGHTS * 8);
        }
        if (glassCount >= 0) {
            int n = Math.min(MAX_GLASS, preset.glass.length);
            for (int i = 0; i < n; i++) {
                ScenePresets.Shape s = preset.glass[i];
                float e = s.progress(clamped);
                float x = lerp(s.fromX, s.toX, e);
                float y = lerp(s.fromY, s.toY, e);
                put(glass, i, 0, x, y, lerp(s.fromAngle, s.toAngle, e), 0f);
                put(glass, i, 1, s.halfX, s.halfY, s.round, s.extra[0]);
                put(glass, i, 2, s.extra[2], s.extra[1], s.extra[3],
                        lightUnder(x, y, clamped, pal));
            }
            GLES20.glUniform1i(glassCount, n);
            GLES20.glUniform4fv(glassA, MAX_GLASS, glass, 0);
            GLES20.glUniform4fv(glassB, MAX_GLASS, glass, MAX_GLASS * 4);
            GLES20.glUniform4fv(glassC, MAX_GLASS, glass, MAX_GLASS * 8);
        }
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    @Override
    public void release() {
        GLES20.glDeleteProgram(program);
    }

    /** Блок k (A, B, C) формы i: блоки лежат подряд по MAX формам. */
    private static void put(float[] dst, int i, int k, float a, float b, float c, float d) {
        int max = dst.length / 12;
        int o = (k * max + i) * 4;
        dst[o] = a;
        dst[o + 1] = b;
        dst[o + 2] = c;
        dst[o + 3] = d;
    }

    private static float lerp(float a, float b, float e) {
        return a + (b - a) * e;
    }

    /**
     * Сколько света под стеклом (в центре стекла), в долях свечения: от него светится кант.
     * Упрощённый расчёт того же слоя, что в шейдере: ореол и свечение форм.
     */
    private float lightUnder(float x, float y, float t, float[] pal) {
        float lumaGlow = Math.max(1e-4f, 0.3f * pal[3] + 0.59f * pal[4] + 0.11f * pal[5]);
        float lumaHalo = 0.3f * pal[6] + 0.59f * pal[7] + 0.11f * pal[8];
        float halo = 0f;
        float inside = 0f;
        for (int i = 0; i < Math.min(MAX_LIGHTS, preset.lights.length); i++) {
            ScenePresets.Shape s = preset.lights[i];
            float e = s.progress(t);
            float dx = x - lerp(s.fromX, s.toX, e);
            float dy = y - lerp(s.fromY, s.toY, e);
            float a = lerp(s.fromAngle, s.toAngle, e);
            float c = (float) Math.cos(a);
            float sn = (float) Math.sin(a);
            float qx = Math.abs(c * dx + sn * dy) - (s.halfX - s.round);
            float qy = Math.abs(-sn * dx + c * dy) - (s.halfY - s.round);
            float d = (float) Math.hypot(Math.max(qx, 0f), Math.max(qy, 0f))
                    + Math.min(Math.max(qx, qy), 0f) - s.round;
            if (d > 0f) {
                float u = d / 0.085f;
                halo += s.extra[0] * (0.795f * (float) Math.exp(-u * u)
                        + 0.734f * (float) Math.exp(-d / 0.15f));
            } else {
                float inr = Math.min(s.halfX, s.halfY);
                float dep = -d;
                inside = Math.max(inside, s.extra[0] * (0.636f + 6.013f
                        * ((float) Math.exp(-dep / 0.0375f)
                        + (float) Math.exp(-Math.max(2f * inr - dep, 0f) / 0.0375f))
                        - 2.292f * (float) Math.exp(-dep / 0.009f)));
            }
        }
        return (lumaHalo * halo) / lumaGlow + inside;
    }
}
