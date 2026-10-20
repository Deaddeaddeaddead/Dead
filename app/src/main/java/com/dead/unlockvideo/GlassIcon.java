package com.dead.unlockvideo;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

/**
 * Стеклянная иконка «как Liquid Glass»: полупрозрачная плитка со светлой кромкой и бликом, на
 * ней — силуэт значка приложения. Силуэт берётся из одноцветного слоя иконки, если он есть;
 * иначе — из переднего слоя адаптивной иконки, а у сплошных иконок — по отличию цвета от фона.
 * Так стеклянной становится иконка любого приложения, даже без поддержки тематических иконок.
 */
final class GlassIcon {
    /** Силуэт белый. */
    static final int STYLE_WHITE = 0;
    /** Силуэт в цветах самой иконки. */
    static final int STYLE_COLOR = 1;

    /** Сторона картинки, пикселей. */
    static final int SIZE = 256;
    /** Плитка внутри картинки (по краям — место под тень). */
    private static final float TILE_INSET = 0.07f;
    private static final float CORNER = 0.26f;
    /** Силуэт вписывается в такую долю плитки. */
    private static final float GLYPH = 0.56f;
    /** Адаптивная иконка: видимая часть — 72 из 108, форма лаунчера близка к такой. */
    private static final float ADAPTIVE_INSET = 18f / 108f;
    private static final float ADAPTIVE_CORNER = 0.3f;

    /** Силуэт, вписанный в плитку, и как он вписан: масштаб и прежний центр. */
    private static final class Fit {
        final Bitmap mask;
        final float scale;
        final float centerX;
        final float centerY;

        Fit(Bitmap mask, float scale, float centerX, float centerY) {
            this.mask = mask;
            this.scale = scale;
            this.centerX = centerX;
            this.centerY = centerY;
        }
    }

    private GlassIcon() {
    }

    /**
     * adaptive — для «адаптивной» иконки: картинка — слой 108×108, лаунчер сам вырезает из
     * середины 72×72 своей формой; стекло заполняет всё, кромка — по видимой части.
     */
    static Bitmap render(Drawable icon, int style, boolean adaptive) {
        int s = SIZE;
        Bitmap full = drawIcon(icon, s);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float inset = adaptive ? s * ADAPTIVE_INSET : s * TILE_INSET;
        RectF tile = new RectF(inset, inset, s - inset, s - inset);
        float r = tile.width() * (adaptive ? ADAPTIVE_CORNER : CORNER);
        Path shape = new Path();
        shape.addRoundRect(tile, r, r, Path.Direction.CW);
        Fit fit = glyphMask(icon, full, s, tile.width());

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new LinearGradient(0, tile.top, 0, tile.bottom,
                new int[] {Color.argb(92, 255, 255, 255), Color.argb(46, 255, 255, 255),
                        Color.argb(34, 255, 255, 255)},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        if (adaptive) {
            // Форму задаёт лаунчер — стекло до краёв слоя.
            c.drawRect(0, 0, s, s, body);
        } else {
            // Тень под плиткой — чтобы стекло читалось и на светлых обоях.
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(Color.argb(70, 0, 0, 0));
            shadow.setMaskFilter(new BlurMaskFilter(s * 0.035f, BlurMaskFilter.Blur.NORMAL));
            c.save();
            c.translate(0, s * 0.018f);
            c.drawPath(shape, shadow);
            c.restore();
            // Тень не должна просвечивать сквозь стекло.
            Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawPath(shape, clear);
            // Тело стекла: светлее сверху, прозрачнее снизу.
            c.drawPath(shape, body);
        }

        c.save();
        c.clipPath(shape);
        // Свет, преломлённый у края: светлая полоса вдоль кромки внутри.
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(s * 0.07f);
        edge.setMaskFilter(new BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL));
        edge.setShader(new LinearGradient(tile.left, tile.top, tile.right, tile.bottom,
                new int[] {Color.argb(120, 255, 255, 255), Color.argb(20, 255, 255, 255),
                        Color.argb(70, 255, 255, 255)},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(shape, edge);
        // Блик сверху.
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        float gx = tile.centerX() - tile.width() * 0.12f;
        float gy = tile.top + tile.height() * 0.1f;
        gloss.setShader(new RadialGradient(gx, gy, tile.width() * 0.62f,
                new int[] {Color.argb(85, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                null, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(tile.left - tile.width() * 0.2f, tile.top - tile.height() * 0.45f,
                tile.right + tile.width() * 0.1f, tile.top + tile.height() * 0.42f), gloss);
        c.restore();

        // Кромка: ярко сверху-слева, отражённый свет снизу-справа.
        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(s * 0.012f);
        rim.setShader(new LinearGradient(tile.left, tile.top, tile.right, tile.bottom,
                new int[] {Color.argb(240, 255, 255, 255), Color.argb(70, 255, 255, 255),
                        Color.argb(40, 255, 255, 255), Color.argb(170, 255, 255, 255)},
                new float[] {0f, 0.35f, 0.65f, 1f}, Shader.TileMode.CLAMP));
        RectF rimRect = new RectF(tile);
        rimRect.inset(s * 0.006f, s * 0.006f);
        c.drawRoundRect(rimRect, r, r, rim);

        if (fit != null) drawGlyph(c, fit, style == STYLE_COLOR ? full : null, tile);
        return out;
    }

    /** Иконка целиком, как её рисует лаунчер, в квадрате s×s. */
    private static Bitmap drawIcon(Drawable icon, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            drawLayer(c, a.getBackground(), s);
            drawLayer(c, a.getForeground(), s);
        } else {
            icon.setBounds(0, 0, s, s);
            icon.draw(c);
        }
        return b;
    }

    /** Слой адаптивной иконки: он 108 из 72 видимых — по краям выходит за квадрат. */
    private static void drawLayer(Canvas c, Drawable layer, int s) {
        if (layer == null) return;
        int e = s / 4;
        layer.setBounds(-e, -e, s + e, s + e);
        layer.draw(c);
    }

    /** Силуэт (альфа 0..255) в квадрате s×s, вписанный в плитку шириной tile; null — пусто. */
    private static Fit glyphMask(Drawable icon, Bitmap full, int s, float tile) {
        int[] alpha = null;
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            if (a.getMonochrome() != null) {
                alpha = layerAlpha(a.getMonochrome(), s);
            } else if (a.getForeground() != null) {
                int[] fg = layerAlpha(a.getForeground(), s);
                // Передний слой — значок на прозрачном: берём его как есть.
                if (coverage(fg, s) < 0.55f) alpha = fg;
            }
        }
        if (alpha == null) alpha = contrastMask(full, s);
        return fitMask(alpha, s, tile);
    }

    private static int[] layerAlpha(Drawable layer, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        drawLayer(new Canvas(b), layer, s);
        int[] px = new int[s * s];
        b.getPixels(px, 0, s, 0, 0, s, s);
        for (int i = 0; i < px.length; i++) px[i] = px[i] >>> 24;
        return px;
    }

    /** Доля непрозрачного в круге, который видно у иконки. */
    private static float coverage(int[] alpha, int s) {
        int inside = 0;
        int opaque = 0;
        float c = s / 2f;
        float r2 = (s * 0.45f) * (s * 0.45f);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float dx = x - c;
                float dy = y - c;
                if (dx * dx + dy * dy > r2) continue;
                inside++;
                if (alpha[y * s + x] > 128) opaque++;
            }
        }
        return inside == 0 ? 0f : opaque / (float) inside;
    }

    /** Сплошная иконка: силуэт — то, что заметно отличается по цвету от фона у края. */
    private static int[] contrastMask(Bitmap full, int s) {
        int[] px = new int[s * s];
        full.getPixels(px, 0, s, 0, 0, s, s);
        // Цвет фона — средний по кольцу у края видимой части.
        float c = s / 2f;
        double rr = 0;
        double gg = 0;
        double bb = 0;
        int n = 0;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float d = (float) Math.hypot(x - c, y - c) / s;
                int p = px[y * s + x];
                if (d < 0.40f || d > 0.46f || (p >>> 24) < 200) continue;
                rr += (p >> 16) & 255;
                gg += (p >> 8) & 255;
                bb += p & 255;
                n++;
            }
        }
        int[] alpha = new int[s * s];
        if (n == 0) {
            for (int i = 0; i < alpha.length; i++) alpha[i] = px[i] >>> 24;
            return alpha;
        }
        rr /= n;
        gg /= n;
        bb /= n;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            double dr = ((p >> 16) & 255) - rr;
            double dg = ((p >> 8) & 255) - gg;
            double db = (p & 255) - bb;
            double dist = Math.sqrt(dr * dr + dg * dg + db * db) / 255.0;
            double t = Math.max(0, Math.min(1, (dist - 0.08) / 0.17));
            alpha[i] = (int) Math.round(t * t * (3 - 2 * t) * (p >>> 24));
        }
        return alpha;
    }

    /** Силуэт сдвигается и масштабируется так, чтобы занять одну и ту же долю плитки. */
    private static Fit fitMask(int[] alpha, int s, float tile) {
        int minX = s;
        int minY = s;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                if (alpha[y * s + x] < 40) continue;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return null;
        int[] argb = new int[s * s];
        for (int i = 0; i < argb.length; i++) argb[i] = alpha[i] << 24 | 0xffffff;
        Bitmap src = Bitmap.createBitmap(argb, s, s, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        float w = maxX - minX + 1;
        float h = maxY - minY + 1;
        float k = tile * GLYPH / Math.max(w, h);
        Canvas c = new Canvas(out);
        c.translate(s / 2f, s / 2f);
        c.scale(k, k);
        c.translate(-(minX + w / 2f), -(minY + h / 2f));
        c.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return new Fit(out, k, minX + w / 2f, minY + h / 2f);
    }

    private static void drawGlyph(Canvas c, Fit fit, Bitmap colors, RectF tile) {
        Bitmap mask = fit.mask;
        int s = mask.getWidth();
        // Мягкая тень под силуэтом — он как бы лежит в стекле.
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        shadow.setColorFilter(new android.graphics.PorterDuffColorFilter(Color.argb(90, 0, 0, 0),
                PorterDuff.Mode.SRC_IN));
        shadow.setMaskFilter(new BlurMaskFilter(s * 0.02f, BlurMaskFilter.Blur.NORMAL));
        c.drawBitmap(mask, 0, s * 0.012f, shadow);

        Bitmap glyph = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(glyph);
        g.drawBitmap(mask, 0, 0, null);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        fill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        if (colors != null) {
            g.save();
            g.translate(s / 2f, s / 2f);
            // Цветная иконка — сдвинута и увеличена так же, как силуэт.
            g.scale(fit.scale, fit.scale);
            g.translate(-fit.centerX, -fit.centerY);
            g.drawBitmap(colors, 0, 0, fill);
            g.restore();
        } else {
            fill.setShader(new LinearGradient(0, tile.top, 0, tile.bottom,
                    new int[] {Color.argb(250, 255, 255, 255), Color.argb(225, 236, 242, 255)},
                    null, Shader.TileMode.CLAMP));
            g.drawRect(0, 0, s, s, fill);
        }
        // Глянец на силуэте сверху.
        Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        shine.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        shine.setShader(new LinearGradient(0, tile.top, 0, tile.centerY(),
                Color.argb(colors != null ? 70 : 0, 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP));
        g.drawRect(0, 0, s, s, shine);
        c.drawBitmap(glyph, 0, 0, null);
    }
}
