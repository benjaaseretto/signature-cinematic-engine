package com.signature.cinematic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * Insignia "LIVE" amarilla centrada en la parte superior del visor en modo Foto.
 * Al tocarla alterna el estado activo/desactivado (con línea diagonal cuando está en OFF).
 */
public final class LiveBadgeView extends View {
    public interface Listener {
        void onLiveToggled(boolean active);
    }

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean active = true;
    private Listener listener;

    public LiveBadgeView(Context context) {
        super(context);
        border.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        text.setLetterSpacing(0.06f);
        setContentDescription("Live");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setActive(boolean a) {
        active = a;
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = h / 2f;
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d);
        if (active) {
            bg.setColor(IconView.YELLOW);
            c.drawRoundRect(rect, r, r, bg);
        } else {
            bg.setColor(Color.argb(145, 28, 28, 32));
            c.drawRoundRect(rect, r, r, bg);
            border.setStrokeWidth(0.9f * d);
            border.setColor(Color.argb(110, 255, 255, 255));
            c.drawRoundRect(rect, r, r, border);
        }

        int fg = active ? Color.BLACK : Color.WHITE;
        ring.setColor(fg);
        ring.setStrokeWidth(1.2f * d);
        float icx = 12f * d;
        float icy = h / 2f;
        c.drawCircle(icx, icy, 4.6f * d, ring);
        c.drawCircle(icx, icy, 2.0f * d, ring);
        if (!active) {
            c.drawLine(icx - 5.2f * d, icy - 5.2f * d, icx + 5.2f * d, icy + 5.2f * d, ring);
        }

        text.setColor(fg);
        text.setTextSize(10.5f * d);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText("LIVE", 21f * d, icy - (fm.ascent + fm.descent) / 2f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            active = !active;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
            if (listener != null) {
                listener.onLiveToggled(active);
            }
            return true;
        }
        return e.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(e);
    }
}
