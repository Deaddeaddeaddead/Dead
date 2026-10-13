package com.dead.unlockvideo;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** EGL-окружение и отрисовка кадра видео (внешней текстуры). */
final class GlUtil {
    private GlUtil() {
    }

    /** EGL-контекст, рисующий в заданную поверхность. Живёт на одном потоке. */
    static final class Egl {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;

        Egl(Surface target, boolean recordable) throws IOException {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IOException("EGL недоступен");
            }
            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    // Без recordable список атрибутов обрывается здесь.
                    recordable ? EGL_RECORDABLE_ANDROID : EGL14.EGL_NONE, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) {
                throw new IOException("нет подходящей конфигурации EGL");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                throw new IOException("не удалось создать контекст EGL");
            }
            surface = EGL14.eglCreateWindowSurface(display, configs[0], target,
                    new int[] {EGL14.EGL_NONE}, 0);
            if (surface == null || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                EGL14.eglDestroyContext(display, context);
                throw new IOException("не удалось подготовить поверхность EGL");
            }
        }

        /** presentationTimeNs < 0 — без метки времени (для экрана). */
        void swap(long presentationTimeNs) {
            if (presentationTimeNs >= 0) {
                EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs);
            }
            EGL14.eglSwapBuffers(display, surface);
        }

        void release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            // eglTerminate не вызываем: дисплей общий на весь процесс.
        }
    }

    /**
     * Рисует внешнюю текстуру на весь вьюпорт. scaleX/scaleY — какая доля кадра видна
     * (меньше 1 — кадр обрезается по краям и увеличивается), центр остаётся на месте.
     */
    static final class FrameDrawer {
        private static final String VERTEX_SHADER =
                "uniform mat4 uTexMatrix;\n"
                + "uniform vec4 uCrop;\n"
                + "attribute vec4 aPosition;\n"
                + "attribute vec2 aTexCoord;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main() {\n"
                + "  gl_Position = aPosition;\n"
                + "  vec2 tc = aTexCoord * uCrop.xy + uCrop.zw;\n"
                + "  vTexCoord = (uTexMatrix * vec4(tc, 0.0, 1.0)).xy;\n"
                + "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "varying vec2 vTexCoord;\n"
                + "uniform samplerExternalOES sTexture;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
                + "}\n";

        final int textureId;
        private final int program;
        private final int positionHandle;
        private final int texCoordHandle;
        private final int matrixHandle;
        private final int cropHandle;
        private final FloatBuffer positions = floats(-1, -1, 1, -1, -1, 1, 1, 1);
        private final FloatBuffer texCoords = floats(0, 0, 1, 0, 0, 1, 1, 1);

        FrameDrawer() throws IOException {
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER));
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] != GLES20.GL_TRUE) throw new IOException("шейдер не собрался");
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            matrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
            cropHandle = GLES20.glGetUniformLocation(program, "uCrop");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        void clear(int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        void draw(float[] texMatrix, int width, int height, float scaleX, float scaleY) {
            clear(width, height);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform4f(cropHandle, scaleX, scaleY,
                    0.5f - scaleX / 2f, 0.5f - scaleY / 2f);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positions);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        private static int shader(int type, String source) throws IOException {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) throw new IOException("шейдер не скомпилировался");
            return shader;
        }

        private static FloatBuffer floats(float... values) {
            FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(values).position(0);
            return buffer;
        }
    }
}
