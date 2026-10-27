package com.signature.cinematic.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Selector de modos Liquid Glass:
 * pista ancha que deja visibles los modos adyacentes, cápsula iluminada sobre el modo activo,
 * soporte de toque directo o deslizamiento horizontal, y apertura de opciones al tocar el modo activo.
 */
public final class ModeStrip extends View {
    public interface Listener {
        void onModeSelected(int index);

        default void onActiveModeTapped() {
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF pillRect = new RectF();
    private String[] labels = new String[0];
    private float[] centers = new float[0];
    private int selected;
    private float pos;
    private ValueAnimator anim;
    private Listener listener;
    private boolean locked;
    private float downX;

    public ModeStrip(Context context) {
        super(context);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textPaint.setLetterSpacing(0.06f);
        pillBorder.setStyle(Paint.Style.STROKE);
    }

    public void setLabels(String[] l, int sel) {
        labels = l;
        selected = sel;
        pos = sel;
        centers = new float[0];
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setLocked(boolean l) {
        locked = l;
        animate().alpha(l ? 0f : 1f).setDuration(200).start();
    }

    public void select(int i, boolean notify) {
        if (i < 0 || i >= labels.length || i == selected) {
            return;
        }
        selected = i;
        if (anim != null) {
            anim.cancel();
        }
        anim = ValueAnimator.ofFloat(pos, i);
        anim.setDuration(250);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (notify && listener != null) {
            listener.onModeSelected(i);
        }
    }

    public int selected() {
        return selected;
    }

    private void measureCenters(float textSize, float gap) {
        textPaint.setTextSize(textSize);
        centers = new float[labels.length];
        float x = 0f;
        for (int i = 0; i < labels.length; i++) {
            float w = textPaint.measureText(labels[i]);
            centers[i] = x + w / 2f;
            x += w + gap;
        }
    }

    private float centerAt(float p) {
        int i = (int) Math.floor(p);
        if (i >= centers.length - 1) {
            return centers[centers.length - 1];
        }
        if (i < 0) {
            return centers[0];
        }
        return centers[i] + (centers[i + 1] - centers[i]) * (p - i);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (labels.length == 0) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        float h = getHeight();
        float w = getWidth();
        float baseSize = 12.5f * d;
        float gap = 22f * d;
        if (centers.length != labels.length) {
            measureCenters(baseSize, gap);
        }

        // Zona visible: todo el ancho; los vecinos se desvanecen hacia los bordes
        float trackW = w;
        trackRect.set(0, 0, w, h);

        textPaint.setTextSize(baseSize);

        // Píldora de cristal líquido (Liquid Glass iOS 27.2) sobre el modo activo con borde especular al 18%
        int activeIdx = Math.max(0, Math.min(labels.length - 1, Math.round(pos)));
        float activeTextW = textPaint.measureText(labels[activeIdx]);
        float pillHalfW = activeTextW / 2f + 13f * d;
        float pillHalfH = 14f * d;
        pillRect.set(w / 2f - pillHalfW, h / 2f - pillHalfH, w / 2f + pillHalfW, h / 2f + pillHalfH);
        pillPaint.setColor(Color.argb(145, 28, 28, 34));
        c.drawRoundRect(pillRect, pillHalfH, pillHalfH, pillPaint);
        pillBorder.setStrokeWidth(1.0f * d);
        pillBorder.setColor(Color.argb(46, 255, 255, 255)); // 18% opacidad especular
        c.drawRoundRect(pillRect, pillHalfH, pillHalfH, pillBorder);

        float offset = w / 2f - centerAt(pos);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = h / 2f - (fm.descent + fm.ascent) / 2f;

        c.save();
        c.clipRect(trackRect);
        for (int i = 0; i < labels.length; i++) {
            float dist = Math.abs(i - pos);
            float x = centers[i] + offset;
            float outer = Math.abs(x - w / 2f) + textPaint.measureText(labels[i]) / 2f;
            float alphaFactor = Math.max(0f, Math.min(1f, (trackW * 0.5f - outer) / (18f * d)));
            if (dist < 0.45f) {
                textPaint.setColor(IconView.YELLOW);
            } else {
                textPaint.setColor(Color.argb(Math.round(235 * alphaFactor), 255, 255, 255));
            }
            c.drawText(labels[i], x, baseline, textPaint);
        }
        c.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (locked || labels.length == 0) {
            return false;
        }
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_UP:
                float dx = e.getX() - downX;
                float d = getResources().getDisplayMetrics().density;
                if (Math.abs(dx) > 26f * d) {
                    select(selected + (dx < 0 ? 1 : -1), true);
                    return true;
                }
                if (e.getX() < trackRect.left || e.getX() > trackRect.right) {
                    return true;
                }
                float offset = getWidth() / 2f - centerAt(pos);
                int best = -1;
                float bestD = 48f * d;
                for (int i = 0; i < centers.length; i++) {
                    float dist = Math.abs(centers[i] + offset - e.getX());
                    if (dist < bestD) {
                        bestD = dist;
                        best = i;
                    }
                }
                if (best >= 0) {
                    if (best != selected) {
                        select(best, true);
                    } else if (listener != null) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        listener.onActiveModeTapped();
                    }
                }
                return true;
            default:
                return true;
        }
    }
}
