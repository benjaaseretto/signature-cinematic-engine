package com.signature.cinematic.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Disparador iOS 27 Liquid Glass:
 * aro exterior de vidrio translúcido con refracción especular (en lugar del aro blanco plano anterior)
 * y núcleo flotante con respuesta elástica.
 */
public final class ShutterButton extends View {
    public enum Style { PHOTO, VIDEO }

    private static final int RED = Color.rgb(255, 59, 48);
    private final Paint glassRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Style style = Style.PHOTO;
    private boolean recording;
    private boolean tickRing;
    /** 0 = círculo, 1 = cuadrado de grabación. */
    private float morph;
    /** 0 = núcleo blanco (FOTO), 1 = núcleo rojo carmesí (VIDEO). */
    private float styleMix;
    private float press = 1f;
    private ValueAnimator anim;
    private ValueAnimator styleAnim;
    private ValueAnimator pressAnim;

    public ShutterButton(Context context) {
        super(context);
        glassRing.setStyle(Paint.Style.STROKE);
        setContentDescription("Disparador");
    }

    public void setStyle(Style s) {
        if (style != s) {
            style = s;
            float target = (s == Style.VIDEO) ? 1f : 0f;
            if (styleAnim != null) {
                styleAnim.cancel();
            }
            styleAnim = ValueAnimator.ofFloat(styleMix, target);
            styleAnim.setDuration(220);
            styleAnim.setInterpolator(new DecelerateInterpolator(1.5f));
            styleAnim.addUpdateListener(a -> {
                styleMix = (Float) a.getAnimatedValue();
                invalidate();
            });
            styleAnim.start();
        }
    }

    public void setRecording(boolean r) {
        if (recording == r) {
            return;
        }
        recording = r;
        setContentDescription(r ? "Detener" : "Grabar");
        if (anim != null) {
            anim.cancel();
        }
        anim = ValueAnimator.ofFloat(morph, r ? 1f : 0f);
        anim.setDuration(300);
        anim.setInterpolator(new OvershootInterpolator(1.35f));
        anim.addUpdateListener(a -> {
            morph = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    private void animatePress(float target) {
        if (pressAnim != null) {
            pressAnim.cancel();
        }
        pressAnim = ValueAnimator.ofFloat(press, target);
        pressAnim.setDuration(target < 1f ? 85 : 220);
        pressAnim.setInterpolator(new OvershootInterpolator(1.8f));
        pressAnim.addUpdateListener(a -> {
            press = (Float) a.getAnimatedValue();
            invalidate();
        });
        pressAnim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            animatePress(0.86f);
        } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            animatePress(1f);
        }
        return super.onTouchEvent(e);
    }

    public void setTickRing(boolean t) {
        if (tickRing != t) {
            tickRing = t;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f;
        float stroke = r * 0.075f;

        // Aro exterior de cristal/metal especular iOS 27.2 (punteado en Cámara lenta / rápida)
        glassRing.setShader(new LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                Color.argb(250, 255, 255, 255),
                Color.argb(185, 215, 220, 230),
                Shader.TileMode.CLAMP));
        if (tickRing) {
            glassRing.setStrokeWidth(stroke * 1.6f);
            float circ = (float) (2 * Math.PI * (r - stroke));
            float seg = circ / 120f;
            glassRing.setPathEffect(new DashPathEffect(new float[] {seg * 0.28f, seg * 0.72f}, 0));
        } else {
            glassRing.setStrokeWidth(stroke);
            glassRing.setPathEffect(null);
        }
        canvas.drawCircle(cx, cy, r - stroke, glassRing);

        // Borde interior especular Liquid Glass (18% blanco)
        glassRing.setShader(null);
        glassRing.setPathEffect(null);
        glassRing.setStrokeWidth(Math.max(1f, stroke * 0.28f));
        glassRing.setColor(Color.argb(46, 255, 255, 255));
        canvas.drawCircle(cx, cy, r - stroke * 1.85f, glassRing);

        // Núcleo reactivo con interpolación suave entre Blanco (Foto) y Rojo Carmesí (Video) + Spring Morphing
        fill.setShader(null);
        int coreR = Math.round(255 + (Color.red(RED) - 255) * styleMix);
        int coreG = Math.round(255 + (Color.green(RED) - 255) * styleMix);
        int coreB = Math.round(255 + (Color.blue(RED) - 255) * styleMix);
        fill.setColor(Color.rgb(coreR, coreG, coreB));
        float inner = (r - stroke * 2.55f) * press;
        float half = inner + (r * 0.34f - inner) * morph;
        float corner = half + (half * 0.28f - half) * morph;
        rect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(rect, corner, corner, fill);
    }
}
