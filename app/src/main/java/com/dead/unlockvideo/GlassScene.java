package com.dead.unlockvideo;

import android.opengl.GLES20;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Режим «Стекло»: светящаяся капсула и две стеклянные, которые съезжаются к ней. Рисуется
 * шейдером целиком — без видео, в разрешении экрана. Только на потоке рендера.
 */
final class GlassScene {
    /** Сцена построена на кадре этого размера и обрезается под экран так же, как видео. */
    static final int FRAME_WIDTH = 1120;
    static final int FRAME_HEIGHT = 2436;

    static final int PALETTE_AMBER = 0;
    static final int PALETTE_ICE = 1;
    static final int PALETTE_AMETHYST = 2;
    static final int PALETTE_EMERALD = 3;
    /** Оттенки: фон, свечение внутри стекла, ореол, подсветка углов фона. */
    private static final float[][] PALETTES = {
            {1.0f, 0.34f, 0.14f, 1.0f, 0.28f, 0.115f, 1.0f, 0.52f, 0.17f, 1.0f, 0.24f, 0.05f},
            {0.22f, 0.42f, 1.0f, 0.12f, 0.38f, 1.0f, 0.45f, 0.8f, 1.0f, 0.12f, 0.3f, 1.0f},
            {0.55f, 0.22f, 1.0f, 0.62f, 0.16f, 1.0f, 0.9f, 0.55f, 1.0f, 0.6f, 0.12f, 1.0f},
            {0.12f, 0.7f, 0.4f, 0.1f, 1.0f, 0.42f, 0.55f, 1.0f, 0.6f, 0.08f, 0.7f, 0.3f},
    };

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "}\n";
    private static final String FRAGMENT_SHADER =
            "precision highp float;\n"
            + "uniform vec2 uRes;\n"
            + "uniform vec4 uCrop;\n"
            + "uniform float uProgress;\n"
            + "uniform float uGlow;\n"
            + "uniform vec2 uTilt;\n"
            + "// 0 — фон, 1 — свечение внутри стекла (угли), 2 — ореол, 3 — подсветка углов фона\n"
            + "uniform vec3 uPal[4];\n"
            + "\n"
            + "const float ASPECT = 2.175;\n"
            + "const vec2 AXIS = vec2(0.6697, 0.7428);\n"
            + "const vec2 UL = vec2(-0.7428, 0.6697);\n"
            + "const vec2 LIGHT = vec2(0.24, -0.97);\n"
            + "const float HALF_LEN = 0.381;\n"
            + "const float RADIUS = 0.222;\n"
            + "const float TRAVEL = 0.385;\n"
            + "const vec2 MID = vec2(0.0007, 0.0073);\n"
            + "const vec2 TOP_END = vec2(0.0081, 0.5083);\n"
            + "const vec2 BOTTOM_END = vec2(-0.0062, -0.5008);\n"
            + "\n"
            + "float capsule(vec2 p, vec2 c, out vec2 n) {\n"
            + "  vec2 d = p - c;\n"
            + "  float h = clamp(dot(d, AXIS), -HALF_LEN, HALF_LEN);\n"
            + "  vec2 q = d - AXIS * h;\n"
            + "  float len = length(q);\n"
            + "  n = len > 1e-5 ? q / len : UL;\n"
            + "  return len - RADIUS;\n"
            + "}\n"
            + "\n"
            + "vec3 background(vec2 p) {\n"
            + "  float corner = exp(-length(p - vec2(-0.55, 1.15)) / 0.45) + 0.9 * exp(-length(p - vec2(0.55, -1.15)) / 0.45);\n"
            + "  return uPal[0] * 0.11 + uPal[3] * 0.3 * corner;\n"
            + "}\n"
            + "\n"
            + "// Свет «горячего» цвета: чем ярче, тем ближе к цвету ореола.\n"
            + "vec3 hot(float i) {\n"
            + "  return i * mix(uPal[1], uPal[2], smoothstep(0.8, 2.6, i));\n"
            + "}\n"
            + "\n"
            + "// Задний слой: фон, ореол вокруг светящейся капсулы и она сама. haloGain и emberGain —\n"
            + "// как стекло перед ними меняет ореол и свечение.\n"
            + "vec3 back(vec2 p, float pxw, float haloGain, float emberGain) {\n"
            + "  vec2 n;\n"
            + "  float d = capsule(p, MID, n);\n"
            + "  float glow = 0.9 + 0.25 * uGlow;\n"
            + "  float along = dot(n, AXIS);\n"
            + "  if (d > 0.0) {\n"
            + "    float lit = dot(n, LIGHT);\n"
            + "    float amp = mix(4.0, 2.4, smoothstep(0.6, 0.9, lit)) * (1.0 - 0.95 * smoothstep(0.2, 0.9, along));\n"
            + "    float width = mix(0.045, 0.075, smoothstep(0.0, 0.85, lit));\n"
            + "    float halo = glow * amp * exp(-d / width) * smoothstep(0.0, 0.012, d);\n"
            + "    return background(p) + uPal[2] * halo * haloGain;\n"
            + "  }\n"
            + "  float s = -d;\n"
            + "  float ul = clamp(0.5 + 0.5 * dot(n, UL), 0.0, 1.0);\n"
            + "  float tip = HALF_LEN + RADIUS - abs(dot(p - MID, AXIS));\n"
            + "  float ember = 0.27 + glow * (mix(1.1, 2.0, ul) * exp(-s / 0.043) + 2.6 * exp(-max(tip, 0.0) / 0.08));\n"
            + "  return hot(ember * emberGain) * (1.0 - 0.6 * exp(-s / (pxw * 2.0)));\n"
            + "}\n"
            + "\n"
            + "void main() {\n"
            + "  vec2 uv = gl_FragCoord.xy / uRes;\n"
            + "  vec2 f = uCrop.zw + uv * uCrop.xy;\n"
            + "  vec2 p = (f - 0.5) * vec2(1.0, ASPECT);\n"
            + "  float pxw = uCrop.x / uRes.x;\n"
            + "  float k = 1.0 - uProgress;\n"
            + "  vec2 top = TOP_END + AXIS * TRAVEL * k;\n"
            + "  vec2 bot = BOTTOM_END - AXIS * TRAVEL * k;\n"
            + "  vec2 n1;\n"
            + "  vec2 n2;\n"
            + "  float d1 = capsule(p, top, n1);\n"
            + "  float d2 = capsule(p, bot, n2);\n"
            + "  float d = d1;\n"
            + "  vec2 n = n1;\n"
            + "  if (d2 < d1) { d = d2; n = n2; }\n"
            + "  float od = max(d, 0.0);\n"
            + "  // Тёмная тонкая обводка снаружи стекла и мягкая тень.\n"
            + "  vec3 c = back(p, pxw, 1.0, 1.0) * (1.0 - 0.75 * exp(-od / (pxw * 1.5)) - 0.12 * exp(-od / 0.02));\n"
            + "  if (d < pxw) {\n"
            + "    // Стекло — «таблетка» с круглым профилем: лёгкое преломление, у кромки сильнее.\n"
            + "    float s = max(-d, 0.0);\n"
            + "    float x = clamp(1.0 - s / RADIUS, 0.0, 1.0);\n"
            + "    float slope = x / sqrt(1.0 - x * x + 0.04);\n"
            + "    // Кромка, обращённая к свету, работает как собирающая линза: берём то, что ближе к\n"
            + "    // середине, — яркий край светящейся капсулы растягивается. Теневая кромка почти не\n"
            + "    // преломляет и глушит ореол за собой.\n"
            + "    // Поле без скачка на оси капсулы: к середине стремится к нейтральному.\n"
            + "    float lit = smoothstep(-0.4, 0.6, dot(n, LIGHT) * (1.0 - s / RADIUS));\n"
            + "    vec2 q = p - n * (0.03 * slope * lit);\n"
            + "    vec3 glass = back(q, pxw, mix(0.4, 1.0, lit), 1.6) * (0.95 - 0.25 * smoothstep(0.7, 1.0, x));\n"
            + "    glass = glass * (0.7 + 0.3 * uPal[1]) + uPal[0] * 0.03;\n"
            + "    // Кромка ловит свет: яркая тонкая линия по всему краю.\n"
            + "    float facing = max(dot(n, -LIGHT), 0.0);\n"
            + "    // Кант: тонкая яркая линия, ярче там, где за кромкой светло.\n"
            + "    vec3 env = back(p + n * 0.03, pxw, 1.0, 1.0);\n"
            + "    float envLight = dot(env, vec3(0.5, 0.35, 0.15));\n"
            + "    glass += uPal[2] * exp(-s / (pxw * 2.0)) * (0.7 + 0.8 * facing + 1.6 * envLight);\n"
            + "    glass *= 1.0 - 0.3 * exp(-abs(s - pxw * 4.0) / (pxw * 2.0));\n"
            + "    c = mix(c, glass, smoothstep(pxw, -pxw, d));\n"
            + "  }\n"
            + "  gl_FragColor = vec4(1.0 - exp(-c), 1.0);\n"
            + "}\n";

    private final int program;
    private final int position;
    private final int res;
    private final int crop;
    private final int progress;
    private final int glow;
    private final int tilt;
    private final int palette;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    GlassScene() throws IOException {
        program = GlUtil.program(VERTEX_SHADER, FRAGMENT_SHADER);
        position = GLES20.glGetAttribLocation(program, "aPosition");
        res = GLES20.glGetUniformLocation(program, "uRes");
        crop = GLES20.glGetUniformLocation(program, "uCrop");
        progress = GLES20.glGetUniformLocation(program, "uProgress");
        glow = GLES20.glGetUniformLocation(program, "uGlow");
        tilt = GLES20.glGetUniformLocation(program, "uTilt");
        palette = GLES20.glGetUniformLocation(program, "uPal");
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    }

    /**
     * Ход анимации t (0..1) в положение капсул — кривая, снятая с исходного видео:
     * cubic-bezier(0.44, 0, 0.54, 1), без рывка в начале и в конце.
     */
    static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float lo = 0f;
        float hi = 1f;
        float s = t;
        for (int i = 0; i < 20; i++) {
            s = (lo + hi) / 2f;
            float x = 3f * (1f - s) * (1f - s) * s * 0.44f + 3f * (1f - s) * s * s * 0.54f + s * s * s;
            if (x < t) lo = s; else hi = s;
        }
        return 3f * (1f - s) * s * s + s * s * s;
    }

    /** cropBox — видимая часть кадра сцены (как у видео): доля по X и Y, левый нижний угол. */
    void draw(int width, int height, float[] cropBox, float t, int paletteIndex) {
        float eased = ease(t);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(res, width, height);
        GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
        GLES20.glUniform1f(progress, eased);
        // Свет чуть ярче, пока капсулы движутся.
        GLES20.glUniform1f(glow, 0.6f * (float) Math.sin(Math.PI * eased));
        GLES20.glUniform2f(tilt, 0f, 0f);
        float[] pal = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, paletteIndex))];
        GLES20.glUniform3fv(palette, 4, pal, 0);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /** Основные цвета палитры для системы (часы, палитра One UI): ореол, свечение, фон. */
    static int[] colors(int paletteIndex) {
        float[] pal = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, paletteIndex))];
        return new int[] {
                tone(pal, 6, 1.6f), tone(pal, 3, 0.9f), tone(pal, 0, 0.2f)
        };
    }

    private static int tone(float[] pal, int offset, float intensity) {
        int r = Math.round(255f * (1f - (float) Math.exp(-pal[offset] * intensity)));
        int g = Math.round(255f * (1f - (float) Math.exp(-pal[offset + 1] * intensity)));
        int b = Math.round(255f * (1f - (float) Math.exp(-pal[offset + 2] * intensity)));
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
