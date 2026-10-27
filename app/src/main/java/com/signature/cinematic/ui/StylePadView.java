package com.signature.cinematic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Control Pad 2D de Photographic Styles 3 (iPhone 18 Pro / iOS 27):
 * eje vertical = Tono (-1..+1, sombras y contraste de escena con luminancia de piel anclada),
 * eje horizontal = Color (-1..+1, croma y calidez).
 */
public final class StylePadView extends View {
    public interface Listener {
        void onChanged(float tone, float color);
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puck = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puckRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float tone;
    private float color;
    private Listener listener;

    public StylePadView(Context context) {
        super(context);
        border.setStyle(Paint.Style.STROKE);
        grid.setStyle(Paint.Style.STROKE);
        puckRing.setStyle(Paint.Style.STROKE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setValues(float t, float c) {
        tone = Math.max(-1f, Math.min(1f, t));
        color = Math.max(-1f, Math.min(1f, c));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        float r = 14f * d;
        rect.set(d, d, w - d, h - d);

        bg.setShader(new LinearGradient(0, 0, w, h,
                Color.argb(130, 42, 48, 62), Color.argb(145, 68, 46, 32), Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, r, r, bg);

        border.setStrokeWidth(1f * d);
        border.setColor(Color.argb(90, 255, 255, 255));
        c.drawRoundRect(rect, r, r, border);

        // Retícula de puntos 5×5 + ejes centrales
        grid.setStrokeWidth(0.8f * d);
        grid.setColor(Color.argb(45, 255, 255, 255));
        c.drawLine(w / 2f, 8f * d, w / 2f, h - 8f * d, grid);
        c.drawLine(8f * d, h / 2f, w - 8f * d, h / 2f, grid);

        puck.setColor(Color.argb(95, 255, 255, 255));
        for (int gy = 1; gy <= 3; gy++) {
            for (int gx = 1; gx <= 3; gx++) {
                c.drawCircle(w * gx / 4f, h * gy / 4f, 1.5f * d, puck);
            }
        }

        // Cursor ("puck") en (color, -tone)
        float pad = 12f * d;
        float px = pad + ((color + 1f) * 0.5f) * (w - 2f * pad);
        float py = pad + ((1f - tone) * 0.5f) * (h - 2f * pad);
        puck.setColor(IconView.YELLOW);
        c.drawCircle(px, py, 6.5f * d, puck);
        puckRing.setStrokeWidth(1.8f * d);
        puckRing.setColor(Color.WHITE);
        c.drawCircle(px, py, 9.5f * d, puckRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getAction();
        if (a == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            float d = getResources().getDisplayMetrics().density;
            float pad = 12f * d;
            float nx = (e.getX() - pad) / Math.max(1f, getWidth() - 2f * pad);
            float ny = (e.getY() - pad) / Math.max(1f, getHeight() - 2f * pad);
            float newColor = Math.max(-1f, Math.min(1f, nx * 2f - 1f));
            float newTone = Math.max(-1f, Math.min(1f, 1f - ny * 2f));
            if (Math.abs(newColor) < 0.06f && Math.abs(newTone) < 0.06f) {
                if (Math.abs(color) >= 0.06f || Math.abs(tone) >= 0.06f) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                newColor = 0f;
                newTone = 0f;
            }
            color = newColor;
            tone = newTone;
            invalidate();
            if (listener != null) {
                listener.onChanged(tone, color);
            }
            return true;
        }
        return super.onTouchEvent(e);
    }
}
