package com.dead.unlockvideo;

/**
 * Цвета и настройки сцен «Ленты» и «Квадраты» — одним массивом для рендера (как палитра
 * стекла): какие данные света (снятые с бирюзового или фиолетового ролика), матрица
 * перекраски, наложение квадратов и у каждого квадрата — прозрачность, вид стекла, его сила и
 * глубина.
 *
 * Свои цвета — перекраска бирюзовых данных: их свет раскладывается на три цвета
 * (светло-бирюзовый, синий и оранжевый — с ним в ролике получается белый); бирюзовый и синий
 * заменяются своими, а третий подбирается так, чтобы белые блики остались белыми.
 */
final class LightColors {
    static final int PALETTE_TEAL = 0;
    static final int PALETTE_VIOLET = 1;
    static final int PALETTE_CUSTOM = 2;
    /** Данные света: assets/scenes/<сцена>_<имя>.bin. */
    static final String[] DATASETS = {"teal", "violet"};

    static final int GLASS_CLEAR = 0;
    static final int GLASS_MATTE = 1;
    static final int GLASS_REEDED = 2;
    static final int SQUARES = 3;

    /** Индексы в массиве настроек. */
    static final int P_DATASET = 0;
    /** Матрица перекраски 3×3 по столбцам. */
    static final int P_COLOR = 1;
    static final int P_OVERLAP = 10;
    /**
     * У квадрата j (сверху вниз), с P_SQUARES + SQUARE_SIZE · j: прозрачность 0..1, вид стекла,
     * сила 0..1, глубина 0..1, данные света и матрица перекраски (свой цвет квадрата или общий).
     */
    static final int P_SQUARES = 11;
    static final int SQUARE_SIZE = 14;
    static final int S_ALPHA = 0;
    static final int S_GLASS = 1;
    static final int S_STRENGTH = 2;
    static final int S_DEPTH = 3;
    static final int S_DATASET = 4;
    static final int S_COLOR = 5;
    static final int SIZE = P_SQUARES + SQUARE_SIZE * SQUARES;

    /** Свои цвета по умолчанию — светло-бирюзовый и синий бирюзового ролика. */
    static final int[] DEFAULT_COLORS = {0xff01e1ff, 0xff026fff};
    /** Цвета бирюзовых данных (столбцы): светло-бирюзовый, синий, оранжевый. */
    private static final float[][] ANCHORS = {
            {0.004f, 0.882f, 1f}, {0.009f, 0.434f, 1f}, {1f, 0.603f, 0.417f},
    };
    /** Сколько каждого из трёх цветов в белом. */
    private static final float[] WHITE = whiteWeights();
    private static final float[][] ANCHORS_INVERSE = invert(ANCHORS);

    /** Квадраты по умолчанию (сверху вниз): прозрачность, стекло, сила, глубина — в %. */
    static final int[] DEFAULT_ALPHA = {60, 65, 65};
    static final int[] DEFAULT_GLASS = {GLASS_CLEAR, GLASS_MATTE, GLASS_REEDED};
    static final int[] DEFAULT_STRENGTH = {0, 60, 50};
    static final int[] DEFAULT_DEPTH = {100, 50, 0};
    static final int DEFAULT_OVERLAP = 90;
    static final int MAX_OVERLAP = 98;

    static float[] params(Prefs cfg) {
        return params(cfg.lightPalette, cfg.lightColors, cfg.lightBrightness, cfg.squaresOverlap,
                cfg.squareAlpha, cfg.squareGlass, cfg.squareStrength, cfg.squareDepth,
                cfg.squareOwnColor, cfg.squareColors);
    }

    /**
     * palette — PALETTE_*, colors — два своих цвета (ARGB), brightness — яркость своих цветов,
     * %; остальное — настройки квадратов в % (glass — вид стекла), ownColor и squareColors —
     * у какого квадрата свой цвет и какой (два цвета, как colors).
     */
    static float[] params(int palette, int[] colors, int brightness, int overlap, int[] alpha,
            int[] glass, int[] strength, int[] depth, boolean[] ownColor, int[][] squareColors) {
        float[] p = new float[SIZE];
        p[P_DATASET] = palette == PALETTE_VIOLET ? 1 : 0;
        float[] m = palette == PALETTE_CUSTOM ? customMatrix(colors, brightness) : IDENTITY;
        System.arraycopy(m, 0, p, P_COLOR, 9);
        p[P_OVERLAP] = overlap / 100f;
        for (int j = 0; j < SQUARES; j++) {
            int base = P_SQUARES + SQUARE_SIZE * j;
            p[base + S_ALPHA] = alpha[j] / 100f;
            p[base + S_GLASS] = glass[j];
            p[base + S_STRENGTH] = strength[j] / 100f;
            p[base + S_DEPTH] = depth[j] / 100f;
            if (ownColor[j]) {
                // Свой цвет квадрата — перекраска бирюзовых данных, как «Свой» у всей сцены.
                p[base + S_DATASET] = 0;
                System.arraycopy(customMatrix(squareColors[j], brightness), 0, p,
                        base + S_COLOR, 9);
            } else {
                p[base + S_DATASET] = p[P_DATASET];
                System.arraycopy(p, P_COLOR, p, base + S_COLOR, 9);
            }
        }
        return p;
    }

    private static final float[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    /**
     * Матрица перекраски (по столбцам): светло-бирюзовый -> свой цвет 1, синий -> свой цвет 2,
     * оранжевый — чтобы белый остался белым. Оттенок и насыщенность своего цвета берутся как есть, а
     * светлота подогнана к заменяемому цвету и умножена на яркость (V) и общую яркость.
     */
    static float[] customMatrix(int[] colors, int brightness) {
        float b = brightness / 100f;
        float[][] target = new float[3][];
        float[] hsv = new float[3];
        for (int i = 0; i < 2; i++) {
            GlassScene.toHsv(colors[i], hsv);
            float[] rgb = GlassScene.hsvToRgb(hsv[0], hsv[1]);
            float k = hsv[2] * b * (float) Math.sqrt(luma(ANCHORS[i]) / Math.max(0.05f, luma(rgb)));
            target[i] = new float[] {rgb[0] * k, rgb[1] * k, rgb[2] * k};
        }
        target[2] = new float[3];
        for (int c = 0; c < 3; c++) {
            float rest = b - WHITE[0] * target[0][c] - WHITE[1] * target[1][c];
            target[2][c] = Math.max(0f, rest / WHITE[2]);
        }
        // M = T · A⁻¹, по столбцам: M[col][row].
        float[] m = new float[9];
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                float sum = 0f;
                for (int i = 0; i < 3; i++) sum += target[i][row] * ANCHORS_INVERSE[i][col];
                m[col * 3 + row] = sum;
            }
        }
        return m;
    }

    /** Основные цвета для системы (часы, палитра One UI): свечение, свет на фигуре, фон. */
    static int[] systemColors(float[] p) {
        boolean teal = p[P_DATASET] < 0.5f;
        float[] glow = teal ? ANCHORS[0] : new float[] {0.33f, 0.43f, 0.71f};
        float[] light = teal ? ANCHORS[1] : new float[] {0.76f, 0.46f, 1f};
        return new int[] {
                tone(p, glow, 1f), tone(p, light, 1f), tone(p, glow, 0.08f)
        };
    }

    private static int tone(float[] p, float[] color, float scale) {
        int rgb = 0;
        for (int row = 0; row < 3; row++) {
            float v = 0f;
            for (int col = 0; col < 3; col++) v += p[P_COLOR + col * 3 + row] * color[col];
            rgb = (rgb << 8) | Math.round(255f * Math.max(0f, Math.min(1f, v * scale)));
        }
        return 0xff000000 | rgb;
    }

    private static float luma(float[] c) {
        return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2];
    }

    private static float[] whiteWeights() {
        float[][] inv = invert(ANCHORS);
        float[] w = new float[3];
        for (int i = 0; i < 3; i++) w[i] = inv[i][0] + inv[i][1] + inv[i][2];
        return w;
    }

    /** Обратная к матрице со столбцами a[0], a[1], a[2]: inv[i][c] — вес канала c в цвете i. */
    private static float[][] invert(float[][] a) {
        // m[row][col] = a[col][row]
        double m00 = a[0][0], m01 = a[1][0], m02 = a[2][0];
        double m10 = a[0][1], m11 = a[1][1], m12 = a[2][1];
        double m20 = a[0][2], m21 = a[1][2], m22 = a[2][2];
        double det = m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20)
                + m02 * (m10 * m21 - m11 * m20);
        double[][] r = {
                {(m11 * m22 - m12 * m21) / det, (m02 * m21 - m01 * m22) / det,
                        (m01 * m12 - m02 * m11) / det},
                {(m12 * m20 - m10 * m22) / det, (m00 * m22 - m02 * m20) / det,
                        (m02 * m10 - m00 * m12) / det},
                {(m10 * m21 - m11 * m20) / det, (m01 * m20 - m00 * m21) / det,
                        (m00 * m11 - m01 * m10) / det},
        };
        float[][] f = new float[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) f[i][j] = (float) r[i][j];
        }
        return f;
    }

    private LightColors() {
    }
}
