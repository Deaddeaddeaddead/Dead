package com.dead.unlockvideo;

import android.content.Context;
import android.opengl.GLES20;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Режим «Стекло»: светящаяся капсула и две стеклянные, которые съезжаются к ней. Рисуется
 * шейдером целиком — без видео, в разрешении экрана. Только на потоке рендера.
 * Геометрия, ход капсул и свет сняты с исходного ролика: ореол и свечение капсулы по её
 * контуру и сеткой внутри, то, как они видны сквозь стекло, полоса у кромки стёкол и их
 * свечение лежат таблицей в assets/glass_light.bin (по времени ролика). Стекло — толстая
 * выпуклая «таблетка»: преломление и полное отражение у кромки считаются в шейдере
 * (параметры подобраны по кадрам), поэтому кусок под стеклом отрывается от кромки, как в
 * ролике. Картинка гладкая и чёткая в любом разрешении и перекрашивается в палитру.
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
            {1.0f, 0.34f, 0.14f, 1.0f, 0.29f, 0.085f, 1.0f, 0.52f, 0.17f, 1.0f, 0.24f, 0.05f},
            {0.22f, 0.42f, 1.0f, 0.12f, 0.38f, 1.0f, 0.45f, 0.8f, 1.0f, 0.12f, 0.3f, 1.0f},
            {0.55f, 0.22f, 1.0f, 0.62f, 0.16f, 1.0f, 0.9f, 0.55f, 1.0f, 0.6f, 0.12f, 1.0f},
            {0.12f, 0.7f, 0.4f, 0.1f, 1.0f, 0.42f, 0.55f, 1.0f, 0.6f, 0.08f, 0.7f, 0.3f},
    };

    /** Таблица света: 64 × 512 RGBA. */
    private static final String LIGHT_ASSET = "glass_light.bin";
    private static final int LIGHT_WIDTH = 64;
    private static final int LIGHT_HEIGHT = 512;
    private static volatile byte[] light;

    /**
     * Положение капсул по времени ролика: 25 точек на равных долях времени. Ролик начинается
     * уже в движении и плавно останавливается к 0,87 — так же и здесь.
     */
    private static final float[] MOTION = {
            0.0000f, 0.0569f, 0.1140f, 0.1756f, 0.2354f, 0.2955f, 0.3577f, 0.4199f, 0.4813f,
            0.5408f, 0.5988f, 0.6546f, 0.7089f, 0.7620f, 0.8085f, 0.8499f, 0.8877f, 0.9213f,
            0.9510f, 0.9724f, 0.9874f, 1.0000f, 1.0000f, 1.0000f, 1.0000f,
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
            + "// Положение стеклянных капсул (0..1).\n"
            + "uniform float uProgress;\n"
            + "// Время ролика (0..1) — строка таблицы света.\n"
            + "uniform float uTime;\n"
            + "// Свет оригинала, снятый с видео, 64×512 (значения — квадрат × 12), блоки по 32 строки\n"
            + "// (время): 0, 1 — ореол светящейся капсулы в 8 узлах расстояния по её контуру; 2, 3 —\n"
            + "// свечение внутри неё в 8 узлах глубины; 4 — глубинное свечение вдоль оси (R — линия у\n"
            + "// верхнего левого края, G — ось, B — у нижнего правого; не используется); 5 — кант стёкол\n"
            + "// по их контуру (R — верхнее, G — нижнее); 6, 7 и 8, 9 — ореол и свечение, видимые сквозь\n"
            + "// стекло (в координатах под стеклом, до преломления — см. glassShift); 10, 11 — сетка свечения\n"
            + "// внутри капсулы (см. gridLight).\n"
            + "uniform sampler2D uLight;\n"
            + "// 0 — фон, 1 — свечение внутри (угли), 2 — ореол, 3 — подсветка углов фона\n"
            + "uniform vec3 uPal[4];\n"
            + "\n"
            + "const float ASPECT = 2.175;\n"
            + "const float PI = 3.14159265;\n"
            + "// Геометрия снята с видео: общая ось, светящаяся капсула, стёкла (шире и короче её).\n"
            + "const vec2 AXIS = vec2(0.6451, 0.7641);\n"
            + "const vec2 UL = vec2(-0.7641, 0.6451);\n"
            + "const float HALF_LEN = 0.381;\n"
            + "const float RADIUS = 0.222;\n"
            + "const vec2 MID = vec2(0.0026, 0.0056);\n"
            + "const float GLASS_HALF_LEN = 0.377;\n"
            + "const float GLASS_RADIUS = 0.227;\n"
            + "const float TRAVEL = 0.385;\n"
            + "const vec2 TOP_END = vec2(-0.0005, 0.5161);\n"
            + "const vec2 BOTTOM_END = vec2(0.0023, -0.5085);\n"
            + "// Сквозь стекло фон чуть темнее, глубина светящейся капсулы — ярче.\n"
            + "const float BG_GAIN = 0.85;\n"
            + "const float DEEP_GAIN = 1.4;\n"
            + "\n"
            + "float capsule(vec2 p, vec2 c, float halfLen, float radius, out vec2 n) {\n"
            + "  vec2 d = p - c;\n"
            + "  float h = clamp(dot(d, AXIS), -halfLen, halfLen);\n"
            + "  vec2 q = d - AXIS * h;\n"
            + "  float len = length(q);\n"
            + "  n = len > 1e-5 ? q / len : UL;\n"
            + "  return len - radius;\n"
            + "}\n"
            + "\n"
            + "// Точка контура (0..1) для точки (x вдоль оси, y поперёк от центра капсулы): от нижнего края\n"
            + "// верхнего правого торца против часовой — торец, верхний левый край, нижний левый торец,\n"
            + "// нижний правый край.\n"
            + "float perim(float x, float y, float halfLen, float radius) {\n"
            + "  float t;\n"
            + "  if (x > halfLen) {\n"
            + "    t = (atan(y, x - halfLen) + 0.5 * PI) * radius;\n"
            + "  } else if (x < -halfLen) {\n"
            + "    float ph = atan(y, x + halfLen);\n"
            + "    if (ph < 0.0) ph += 2.0 * PI;\n"
            + "    t = PI * radius + 2.0 * halfLen + (ph - 0.5 * PI) * radius;\n"
            + "  } else if (y >= 0.0) {\n"
            + "    t = PI * radius + (halfLen - x);\n"
            + "  } else {\n"
            + "    t = 2.0 * PI * radius + 2.0 * halfLen + (x + halfLen);\n"
            + "  }\n"
            + "  return t / (4.0 * halfLen + 2.0 * PI * radius);\n"
            + "}\n"
            + "\n"
            + "vec4 light(float u, float block) {\n"
            + "  vec4 v = texture2D(uLight, vec2(u, (block * 32.0 + uTime * 31.0 + 0.5) / 512.0));\n"
            + "  return v * v * 12.0;\n"
            + "}\n"
            + "\n"
            + "// Узлы расстояния от края: 0.0015 + 0.004 k + 0.0025 k², k = 0..7.\n"
            + "float knot(float d) {\n"
            + "  return (sqrt(0.000016 + 0.01 * max(d - 0.0015, 0.0)) - 0.004) / 0.005;\n"
            + "}\n"
            + "\n"
            + "float bspline(float t) {\n"
            + "  t = abs(t);\n"
            + "  if (t < 1.0) return (4.0 - 6.0 * t * t + 3.0 * t * t * t) / 6.0;\n"
            + "  if (t < 2.0) return (2.0 - t) * (2.0 - t) * (2.0 - t) / 6.0;\n"
            + "  return 0.0;\n"
            + "}\n"
            + "\n"
            + "// Профиль по 8 узлам в точке контура s — кубический B-сплайн: гладко, без колец и изломов.\n"
            + "float profile(float s, float block, float d) {\n"
            + "  float k = min(knot(d), 7.0);\n"
            + "  vec4 w0 = vec4(bspline(k), bspline(k - 1.0), bspline(k - 2.0), bspline(k - 3.0));\n"
            + "  vec4 w1 = vec4(bspline(k - 4.0), bspline(k - 5.0), bspline(k - 6.0), bspline(k - 7.0));\n"
            + "  float v = dot(w0, light(s, block)) + dot(w1, light(s, block + 1.0));\n"
            + "  return v / (dot(w0, vec4(1.0)) + dot(w1, vec4(1.0)));\n"
            + "}\n"
            + "\n"
            + "// Свечение внутри капсулы по сетке: 64 точки вдоль оси (линейно) × 8 линий поперёк\n"
            + "// (y = −0.21 … 0.21 через 0.06, кубический B-сплайн) — гладко, без полос и полок.\n"
            + "float gridLight(float x, float y) {\n"
            + "  float u = clamp((x / (HALF_LEN + RADIUS) * 0.5 + 0.5) * 64.0, 0.5, 63.5) / 64.0;\n"
            + "  float k = clamp((y + 0.21) / 0.06, 0.0, 7.0);\n"
            + "  vec4 w0 = vec4(bspline(k), bspline(k - 1.0), bspline(k - 2.0), bspline(k - 3.0));\n"
            + "  vec4 w1 = vec4(bspline(k - 4.0), bspline(k - 5.0), bspline(k - 6.0), bspline(k - 7.0));\n"
            + "  float v = dot(w0, light(u, 10.0)) + dot(w1, light(u, 11.0));\n"
            + "  return v / (dot(w0, vec4(1.0)) + dot(w1, vec4(1.0)));\n"
            + "}\n"
            + "\n"
            + "vec3 background(vec2 p) {\n"
            + "  float corner = exp(-length(p - vec2(-0.55, 1.15)) / 0.45) + 0.9 * exp(-length(p - vec2(0.55, -1.15)) / 0.45);\n"
            + "  return uPal[0] * 0.11 + uPal[3] * 0.3 * corner;\n"
            + "}\n"
            + "\n"
            + "// Стекло — толстая «таблетка» с выпуклым верхом (эллипс высотой 0.1075 при полуширине\n"
            + "// 0.227, показатель преломления 1.72, до светящегося слоя ещё 0.015): луч, падающий в точку\n"
            + "// на расстоянии r от оси, преломляется и уходит к оси на glassShift(r). В середине это\n"
            + "// ровное увеличение, к кромке сдвиг резко растёт, а в полосе ~0.004 у самой кромки луч\n"
            + "// отражается внутри стекла целиком — сквозь неё ничего не видно. Поэтому кусок под стеклом\n"
            + "// отрывается от кромки и заканчивается скруглённо. Параметры подобраны по кадрам ролика.\n"
            + "const float GLASS_HEIGHT = 0.1075;\n"
            + "const float GLASS_IOR = 1.72;\n"
            + "const float GLASS_GAP = 0.015;\n"
            + "\n"
            + "float glassShift(float r) {\n"
            + "  float x = min(r / GLASS_RADIUS, 0.999);\n"
            + "  float sq = sqrt(1.0 - x * x);\n"
            + "  float z = GLASS_HEIGHT * sq;\n"
            + "  vec2 n = normalize(vec2(GLASS_HEIGHT * x / (GLASS_RADIUS * sq), 1.0));\n"
            + "  float eta = 1.0 / GLASS_IOR;\n"
            + "  float a = eta * n.y - sqrt(max(1.0 - eta * eta * (1.0 - n.y * n.y), 0.0));\n"
            + "  vec2 t = vec2(a * n.x, -eta + a * n.y);\n"
            + "  float sinOut = min(abs(t.x) / length(t) * GLASS_IOR, 0.9999);\n"
            + "  return z * abs(t.x / t.y) + min(GLASS_GAP * sinOut / sqrt(1.0 - sinOut * sinOut), 0.06);\n"
            + "}\n"
            + "\n"
            + "// Задний слой: фон, ореол вокруг светящейся капсулы и она сама — со светом оригинала;\n"
            + "// сквозь стекло (glass = 1) — свет, каким он виден через стекло в оригинале.\n"
            + "vec3 back(vec2 p, float pxw, float glass) {\n"
            + "  vec2 n;\n"
            + "  float d = capsule(p, MID, HALF_LEN, RADIUS, n);\n"
            + "  vec2 rel = p - MID;\n"
            + "  float x = dot(rel, AXIS);\n"
            + "  float y = dot(rel, UL);\n"
            + "  float s = perim(x, y, HALF_LEN, RADIUS);\n"
            + "  vec3 outside = vec3(0.0);\n"
            + "  vec3 inside = vec3(0.0);\n"
            + "  if (d > -pxw) {\n"
            + "    float od = max(d, 0.0);\n"
            + "    // Ореол сквозь стекло чуть тусклее, чем по таблице (поправка по средней яркости).\n"
            + "    float halo = profile(s, glass * 6.0, od) * exp(-max(od - 0.152, 0.0) / 0.08) * mix(1.0, 0.88, glass);\n"
            + "    outside = background(p) * mix(1.0, BG_GAIN, glass) + uPal[2] * halo;\n"
            + "  }\n"
            + "  if (d < pxw) {\n"
            + "    float dep = max(-d, 0.0);\n"
            + "    float deep = gridLight(x, y) * mix(1.0, DEEP_GAIN, glass);\n"
            + "    // От края — профиль по контуру, глубже — сетка; сквозь стекло таблица заходит глубже.\n"
            + "    float glow = mix(profile(s, 2.0 + glass * 6.0, dep), deep,\n"
            + "        smoothstep(mix(0.06, 0.13, glass), mix(0.12, 0.16, glass), dep));\n"
            + "    // Сквозь стекло чуть внутри видимого края — яркая линия.\n"
            + "    float t = (dep - 0.004) / 0.0022;\n"
            + "    glow *= 1.0 + 0.35 * glass * exp(-t * t);\n"
            + "    inside = uPal[1] * glow;\n"
            + "  }\n"
            + "  // Сквозь стекло по самому видимому краю — тёмная линия: кусок под стеклом читается\n"
            + "  // отдельной фигурой (как в ролике).\n"
            + "  float e = (d - 0.0003) / 0.0014;\n"
            + "  return mix(outside, inside, smoothstep(pxw, -pxw, d)) * (1.0 - 0.7 * glass * exp(-e * e));\n"
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
            + "  vec2 n;\n"
            + "  float d1 = capsule(p, top, GLASS_HALF_LEN, GLASS_RADIUS, n);\n"
            + "  float d2 = capsule(p, bot, GLASS_HALF_LEN, GLASS_RADIUS, n);\n"
            + "  bool isTop = d1 < d2;\n"
            + "  float d = isTop ? d1 : d2;\n"
            + "  vec3 c = vec3(0.0);\n"
            + "  if (d > -pxw) {\n"
            + "    // Едва заметная тёмная линия снаружи по кромке стекла.\n"
            + "    float od = max(d, 0.0);\n"
            + "    c = back(p, pxw, 0.0) * (1.0 - 0.2 * exp(-od * od / 0.00000225));\n"
            + "  }\n"
            + "  if (d < pxw) {\n"
            + "    float s = max(-d, 0.0);\n"
            + "    vec2 c0 = isTop ? top : bot;\n"
            + "    vec2 axisPoint = c0 + AXIS * clamp(dot(p - c0, AXIS), -GLASS_HALF_LEN, GLASS_HALF_LEN);\n"
            + "    float dp = length(p - axisPoint);\n"
            + "    vec2 q = axisPoint + (p - axisPoint) * (max(dp - glassShift(dp), 0.0) / max(dp, 1e-5));\n"
            + "    // Полоса полного отражения у кромки: её яркость по контуру снята с видео (над тёмным\n"
            + "    // фоном она светится — это кант, над светящейся капсулой темнее того, что за ней).\n"
            + "    // Свет, идущий по стеклу, выходит и дальше от кромки: быстро гаснущая часть и широкое\n"
            + "    // свечение — так загораются дальние торцы. Берётся только та доля полосы, которую не\n"
            + "    // объясняет видимое сквозь стекло; самое широкое свечение (гаснет за 0.04) — отдельно,\n"
            + "    // по контуру из видео.\n"
            + "    vec2 rel = p - c0;\n"
            + "    vec4 r = light(perim(dot(rel, AXIS), dot(rel, UL), GLASS_HALF_LEN, GLASS_RADIUS), 5.0);\n"
            + "    float bandV = isTop ? r.r : r.g;\n"
            + "    float tir = 1.0 - smoothstep(0.0030, 0.0046, s);\n"
            + "    vec3 content = back(q, pxw, 1.0);\n"
            + "    const vec3 LUMA = vec3(0.3, 0.59, 0.11);\n"
            + "    float glowV = max(bandV - 0.6 * dot(content, LUMA) / dot(uPal[1], LUMA), 0.0);\n"
            + "    float sT = max(s - 0.004, 0.0);\n"
            + "    float broadV = isTop ? r.b : r.a;\n"
            + "    vec3 glass = mix(content, uPal[1] * bandV, tir) + uPal[1] * (1.0 - tir)\n"
            + "        * (glowV * (0.51 * exp(-sT / 0.004) + 0.35 * exp(-sT / 0.016)) + broadV * exp(-sT / 0.04))\n"
            + "        * (1.0 - smoothstep(0.12, 0.2, s));\n"
            + "    c = mix(c, glass, smoothstep(pxw, -pxw, d));\n"
            + "  }\n"
            + "  gl_FragColor = vec4(1.0 - exp(-c), 1.0);\n"
            + "}\n";

    private final int program;
    private final int position;
    private final int res;
    private final int crop;
    private final int progress;
    private final int time;
    private final int lightUniform;
    private final int palette;
    private final int lightTexture;
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();

    /** Читает таблицу света из assets — один раз на процесс, до первого GlassScene. */
    static void load(Context context) {
        if (light != null) return;
        synchronized (GlassScene.class) {
            if (light != null) return;
            try (InputStream in = context.getApplicationContext().getAssets().open(LIGHT_ASSET)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(LIGHT_WIDTH * LIGHT_HEIGHT * 4);
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                byte[] data = out.toByteArray();
                if (data.length == LIGHT_WIDTH * LIGHT_HEIGHT * 4) light = data;
            } catch (IOException ignored) {
                // Без таблицы сцена не создастся и сообщит об ошибке.
            }
        }
    }

    GlassScene() throws IOException {
        byte[] data = light;
        if (data == null) throw new IOException("нет таблицы света " + LIGHT_ASSET);
        program = GlUtil.program(VERTEX_SHADER, FRAGMENT_SHADER);
        position = GLES20.glGetAttribLocation(program, "aPosition");
        res = GLES20.glGetUniformLocation(program, "uRes");
        crop = GLES20.glGetUniformLocation(program, "uCrop");
        progress = GLES20.glGetUniformLocation(program, "uProgress");
        time = GLES20.glGetUniformLocation(program, "uTime");
        lightUniform = GLES20.glGetUniformLocation(program, "uLight");
        palette = GLES20.glGetUniformLocation(program, "uPal");
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        lightTexture = id[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // По ширине — точки замкнутого контура.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        ByteBuffer pixels = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        pixels.put(data).position(0);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, LIGHT_WIDTH, LIGHT_HEIGHT, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /** Положение капсул в момент t (доля ролика, 0..1). */
    static float motion(float t) {
        if (t <= 0f) return MOTION[0];
        if (t >= 1f) return MOTION[MOTION.length - 1];
        float x = t * (MOTION.length - 1);
        int i = (int) x;
        return MOTION[i] + (MOTION[i + 1] - MOTION[i]) * (x - i);
    }

    /**
     * t — момент ролика (0..1). cropBox — видимая часть кадра сцены (как у видео): доля по X
     * и Y, левый нижний угол.
     */
    void draw(int width, int height, float[] cropBox, float t, int paletteIndex) {
        float clamped = Math.max(0f, Math.min(1f, t));
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(program);
        GLES20.glUniform2f(res, width, height);
        GLES20.glUniform4f(crop, cropBox[0], cropBox[1], cropBox[2], cropBox[3]);
        GLES20.glUniform1f(progress, motion(clamped));
        GLES20.glUniform1f(time, clamped);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lightTexture);
        GLES20.glUniform1i(lightUniform, 0);
        float[] pal = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, paletteIndex))];
        GLES20.glUniform3fv(palette, 4, pal, 0);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
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
