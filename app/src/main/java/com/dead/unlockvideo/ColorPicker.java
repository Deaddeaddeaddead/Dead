package com.dead.unlockvideo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * Пикер цвета: квадрат «насыщенность × яркость» для текущего оттенка и полоса оттенков под
 * ним. Тянуть можно по обоим; цвет сообщается слушателю на каждом шаге.
 */
final class ColorPicker extends View {
    interface OnColor {
        void onColor(int color);
    }

    private static final int[] HUES = {
            0xffff0000, 0xffffff00, 0xff00ff00, 0xff00ffff, 0xff0000ff, 0xffff00ff, 0xffff0000,
    };

    /** Оттенок (градусы), насыщенность, яркость. */
    private final float[] hsv = {0f, 1f, 1f};
    private final float[] pureHue = {0f, 1f, 1f};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF square = new RectF();
    private final RectF hueBar = new RectF();
    private final float density;
    private Shader valueShader;
    private Shader hueShader;
    private Shader saturationShader;
    private float saturationShaderHue = -1f;
    /** Что тянут: 0 — ничего, 1 — квадрат, 2 — полосу оттенков. */
    private int target;
    private OnColor listener;

    ColorPicker(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
    }

    void setOnColor(OnColor listener) {
        this.listener = listener;
    }

    int getColor() {
        return Color.HSVToColor(hsv);
    }

    /** Цвет снаружи (без слушателя). У серого оттенок прежний — чтобы не прыгал к красному. */
    void setColor(int color) {
        float hue = hsv[0];
        Color.colorToHSV(color, hsv);
        if (hsv[1] == 0f || hsv[2] == 0f) hsv[0] = hue;
        invalidate();
    }

    private float dp(float value) {
        return value * density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.round(width * 0.62f + dp(56)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float pad = dp(12);
        float bar = dp(26);
        square.set(pad, pad, w - pad, h - pad - bar - dp(18));
        hueBar.set(pad, h - pad - bar, w - pad, h - pad);
        valueShader = new LinearGradient(0, square.top, 0, square.bottom,
                0x00000000, 0xff000000, Shader.TileMode.CLAMP);
        hueShader = new LinearGradient(hueBar.left, 0, hueBar.right, 0, HUES, null,
                Shader.TileMode.CLAMP);
        saturationShaderHue = -1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float corner = dp(14);
        pureHue[0] = hsv[0];
        int pure = Color.HSVToColor(pureHue);
        if (saturationShaderHue != hsv[0]) {
            saturationShader = new LinearGradient(square.left, 0, square.right, 0,
                    0xffffffff, pure, Shader.TileMode.CLAMP);
            saturationShaderHue = hsv[0];
        }
        // Слева направо — от белого к чистому оттенку, сверху вниз — к чёрному.
        fill.setShader(saturationShader);
        canvas.drawRoundRect(square, corner, corner, fill);
        fill.setShader(valueShader);
        canvas.drawRoundRect(square, corner, corner, fill);
        fill.setShader(hueShader);
        float barRadius = hueBar.height() / 2f;
        canvas.drawRoundRect(hueBar, barRadius, barRadius, fill);
        fill.setShader(null);

        marker(canvas, square.left + hsv[1] * square.width(),
                square.top + (1f - hsv[2]) * square.height(), getColor());
        marker(canvas, hueBar.left + hsv[0] / 360f * hueBar.width(), hueBar.centerY(), pure);
    }

    private void marker(Canvas canvas, float x, float y, int color) {
        float radius = dp(11);
        fill.setColor(color);
        canvas.drawCircle(x, y, radius, fill);
        ring.setStrokeWidth(dp(3));
        ring.setColor(Color.WHITE);
        canvas.drawCircle(x, y, radius, ring);
        ring.setStrokeWidth(dp(1));
        ring.setColor(0x66000000);
        canvas.drawCircle(x, y, radius + dp(2), ring);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                target = event.getY() < (square.bottom + hueBar.top) / 2f ? 1 : 2;
                // Внутри прокрутки тянуть по пикеру, а не листать.
                getParent().requestDisallowInterceptTouchEvent(true);
                track(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                track(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                track(event.getX(), event.getY());
                target = 0;
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                target = 0;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void track(float x, float y) {
        if (target == 1) {
            hsv[1] = clamp((x - square.left) / square.width());
            hsv[2] = 1f - clamp((y - square.top) / square.height());
        } else if (target == 2) {
            hsv[0] = Math.min(359.9f, 360f * clamp((x - hueBar.left) / hueBar.width()));
        } else {
            return;
        }
        invalidate();
        if (listener != null) listener.onColor(getColor());
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
