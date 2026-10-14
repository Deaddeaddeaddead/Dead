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
 * Сцена со своим шейдером из assets/scenes (ScenePresets): общие для всех униформы —
 * размер, обрезка, ход анимации, палитра и параллакс; остальное шейдер рисует сам.
 */
final class ProceduralScene implements Scene {
    private static final String[] SHADERS = {"waves"};
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";
    private static final Map<String, String> sources = new HashMap<>();

    private final int program;
    private final int position;
    private final int res;
    private final int crop;
    private final int progress;
    private final int palette;
    private final int glassColor;
    private final int glassTint;
    private final int view;
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

    ProceduralScene(String shader) throws IOException {
        String source;
        synchronized (sources) {
            source = shader == null ? null : sources.get(shader);
        }
        if (source == null) throw new IOException("нет шейдера сцены " + shader);
        program = GlUtil.program(VERTEX_SHADER, source);
        position = GLES20.glGetAttribLocation(program, "aPosition");
        res = GLES20.glGetUniformLocation(program, "uRes");
        crop = GLES20.glGetUniformLocation(program, "uCrop");
        progress = GLES20.glGetUniformLocation(program, "uProgress");
        palette = GLES20.glGetUniformLocation(program, "uPal");
        glassColor = GLES20.glGetUniformLocation(program, "uGlass");
        glassTint = GLES20.glGetUniformLocation(program, "uTint");
        view = GLES20.glGetUniformLocation(program, "uView");
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    }

    @Override
    public void draw(int width, int height, float[] cropBox, float t, float[] pal, float viewX,
            float viewY) {
        // Мягкий старт и остановка — уже в t (SceneClip).
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(res, width, height);
        GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
        GLES20.glUniform1f(progress, Math.max(0f, Math.min(1f, t)));
        GLES20.glUniform3fv(palette, 4, pal, 0);
        GLES20.glUniform3fv(glassColor, 2, pal, 12);
        GLES20.glUniform2f(glassTint, pal[18], pal[19]);
        GLES20.glUniform2f(view, viewX, viewY);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    @Override
    public void release() {
        GLES20.glDeleteProgram(program);
    }
}
