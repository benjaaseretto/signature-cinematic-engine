package com.signature.cinematic.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import java.util.Locale;

/**
 * Capa sobre el visor estilo iPhone 17 Pro:
 * - Grilla de composición en tercios + cruz central sutil
 * - Nivel de horizonte electrónico con transición a amarillo al nivelar (0.0°)
 * - Retícula de enfoque con esquinas marcadas, control deslizante de exposición y píldora de EV
 * - Máscara de recorte con borde fino cuando la proporción es 16:9 o 1:1 en foto
 */
public final class FocusView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path corners = new Path();

    private float fx = -1;
    private float fy;
    private float scale = 1f;
    private float alpha;
    private float ev;
    private float ratio = 4f / 3f;
    private boolean showGrid = true;
    private float rollDeg;
    private float levelAlpha;
    private ValueAnimator anim;
    private final Runnable fade = () -> animateAlpha(alpha, 0.35f, 500);
    private final Runnable hideRunnable = () -> animateAlpha(alpha, 0f, 400);

    public FocusView(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(IconView.YELLOW);

        grid.setStyle(Paint.Style.STROKE);
        grid.setColor(Color.argb(46, 255, 255, 255));

        levelPaint.setStyle(Paint.Style.STROKE);
        levelPaint.setStrokeCap(Paint.Cap.ROUND);

        mask.setColor(Color.argb(210, 0, 0, 0));

        badgeBg.setColor(Color.argb(180, 20, 20, 22));
        badgeText.setColor(IconView.YELLOW);
        badgeText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        badgeText.setTextAlign(Paint.Align.CENTER);
    }

    public void setGridEnabled(boolean enabled) {
        showGrid = enabled;
        invalidate();
    }

    public boolean isGridEnabled() {
        return showGrid;
    }

    /** Actualiza el ángulo de inclinación del horizonte en grados (-180..180). */
    public void setRollDegrees(float deg) {
        rollDeg = rollDeg + (deg - rollDeg) * 0.25f;
        float abs = Math.abs(rollDeg);
        float target = abs < 12f ? (abs < 1.0f ? 0.95f : 0.55f) : 0f;
        levelAlpha += (target - levelAlpha) * 0.2f;
        if (showGrid && levelAlpha > 0.02f) {
            invalidate();
        }
    }

    /** Proporción de la foto (lado largo / corto) dentro de una vista previa 4:3; 0 = sin máscara. */
    public void setAspect(float r) {
        ratio = r;
        invalidate();
    }

    public void showAt(float x, float y) {
        fx = x;
        fy = y;
        ev = 0f;
        removeCallbacks(fade);
        removeCallbacks(hideRunnable);
        if (anim != null) {
            anim.cancel();
        }
        // Animación de bloqueo instantánea (scale-down 1.32 -> 1.0 en 170ms estilo iOS 27.2)
        anim = ValueAnimator.ofFloat(1.32f, 1f);
        anim.setDuration(170);
        anim.setInterpolator(new OvershootInterpolator(1.05f));
        anim.addUpdateListener(a -> {
            scale = (Float) a.getAnimatedValue();
            alpha = 1f;
            invalidate();
        });
        anim.start();
        postDelayed(fade, 1600);
        postDelayed(hideRunnable, 3400);
    }

    public void setEv(float e) {
        ev = e;
        alpha = 1f;
        removeCallbacks(fade);
        removeCallbacks(hideRunnable);
        postDelayed(fade, 1600);
        postDelayed(hideRunnable, 3400);
        invalidate();
    }

    /** Bloqueo AE/AF: el cuadro queda fijo y no se desvanece. */
    public void hold() {
        removeCallbacks(fade);
        removeCallbacks(hideRunnable);
        alpha = 1f;
        invalidate();
    }

    public void hide() {
        removeCallbacks(fade);
        removeCallbacks(hideRunnable);
        alpha = 0f;
        invalidate();
    }

    private void animateAlpha(float from, float to, long ms) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.addUpdateListener(v -> {
            alpha = (Float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float d = getResources().getDisplayMetrics().density;

        float cropLeft = 0f;
        float cropTop = 0f;
        float cropRight = w;
        float cropBottom = h;

        if (ratio > 0f && Math.abs(ratio - 4f / 3f) > 1e-3f) {
            if (ratio > 4f / 3f) {
                float side = (w - h / ratio) / 2f;
                c.drawRect(0, 0, side, h, mask);
                c.drawRect(w - side, 0, w, h, mask);
                cropLeft = side;
                cropRight = w - side;
            } else {
                float band = (h - w * ratio) / 2f;
                c.drawRect(0, 0, w, band, mask);
                c.drawRect(0, h - band, w, h, mask);
                cropTop = band;
                cropBottom = h - band;
            }
        }

        // 1. Grilla de composición en tercios + nivel de horizonte
        if (showGrid) {
            float cw = cropRight - cropLeft;
            float ch = cropBottom - cropTop;
            grid.setStrokeWidth(0.8f * d);
            grid.setColor(Color.argb(44, 255, 255, 255));
            c.drawLine(cropLeft + cw / 3f, cropTop, cropLeft + cw / 3f, cropBottom, grid);
            c.drawLine(cropLeft + cw * 2f / 3f, cropTop, cropLeft + cw * 2f / 3f, cropBottom, grid);
            c.drawLine(cropLeft, cropTop + ch / 3f, cropRight, cropTop + ch / 3f, grid);
            c.drawLine(cropLeft, cropTop + ch * 2f / 3f, cropRight, cropTop + ch * 2f / 3f, grid);

            // Marco interior sutil
            rect.set(cropLeft + 0.5f * d, cropTop + 0.5f * d, cropRight - 0.5f * d, cropBottom - 0.5f * d);
            grid.setColor(Color.argb(32, 255, 255, 255));
            c.drawRect(rect, grid);

            if (levelAlpha > 0.02f) {
                float cx = w / 2f;
                float cy = (cropTop + cropBottom) / 2f;
                boolean level = Math.abs(rollDeg) < 1.0f;
                int col = level ? IconView.YELLOW : Color.WHITE;
                int a = Math.round(255 * levelAlpha * (level ? 0.95f : 0.55f));
                levelPaint.setColor(Color.argb(a, Color.red(col), Color.green(col), Color.blue(col)));
                levelPaint.setStrokeWidth((level ? 1.8f : 1.2f) * d);

                float outer = 46f * d;
                float inner = 26f * d;
                // Guías fijas laterales
                c.drawLine(cx - outer, cy, cx - inner - 4f * d, cy, levelPaint);
                c.drawLine(cx + inner + 4f * d, cy, cx + outer, cy, levelPaint);
                // Línea central inclinada
                c.save();
                c.rotate(-rollDeg, cx, cy);
                c.drawLine(cx - inner, cy, cx + inner, cy, levelPaint);
                c.restore();
            }
        }

        // 2. Retícula Inteligente iOS 27.2 (Tap-to-Focus & Exposure HUD)
        if (fx < 0 || alpha <= 0.01f) {
            return;
        }
        float half = 35f * d * scale;
        int lineAlpha = Math.round(255 * alpha);
        line.setAlpha(lineAlpha);
        line.setStrokeWidth(1.5f * d);

        // Cuadrado amarillo minimalista iOS 27.2 con esquinas suavizadas (4.5dp) y ticks centrales hacia el interior
        rect.set(fx - half, fy - half, fx + half, fy + half);
        float cornerRadius = 4.5f * d;
        c.drawRoundRect(rect, cornerRadius, cornerRadius, line);

        float tickLen = 5.5f * d;
        // Ticks centrales en los 4 lados del cuadro estilo cámara nativa iPhone
        c.drawLine(fx, fy - half, fx, fy - half + tickLen, line);
        c.drawLine(fx, fy + half - tickLen, fx, fy + half, line);
        c.drawLine(fx - half, fy, fx - half + tickLen, fy, line);
        c.drawLine(fx + half - tickLen, fy, fx + half, fy, line);

        // Control deslizante de exposición vertical con sol estilo iOS
        float sx = fx + half + 22f * d > w - 18f * d ? fx - half - 22f * d : fx + half + 20f * d;
        float sy = fy - (ev / 2f) * (half * 0.85f);
        float trackTop = fy - half * 0.9f;
        float trackBottom = fy + half * 0.9f;

        // Pista vertical con separación para el sol
        line.setStrokeWidth(1.2f * d);
        line.setAlpha(Math.round(160 * alpha));
        if (sy - 10f * d > trackTop) {
            c.drawLine(sx, trackTop, sx, sy - 10f * d, line);
        }
        if (sy + 10f * d < trackBottom) {
            c.drawLine(sx, sy + 10f * d, sx, trackBottom, line);
        }

        // Símbolo '+' arriba de la pista
        float plusY = trackTop - 7f * d;
        float symHalf = 3f * d;
        c.drawLine(sx - symHalf, plusY, sx + symHalf, plusY, line);
        c.drawLine(sx, plusY - symHalf, sx, plusY + symHalf, line);

        // Símbolo '-' abajo de la pista
        float minusY = trackBottom + 7f * d;
        c.drawLine(sx - symHalf, minusY, sx + symHalf, minusY, line);

        // Sol de exposición
        line.setAlpha(lineAlpha);
        line.setStrokeWidth(1.4f * d);
        c.drawCircle(sx, sy, 3.8f * d, line);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(45 * i);
            c.drawLine(sx + (float) Math.cos(a) * 5.4f * d, sy + (float) Math.sin(a) * 5.4f * d,
                    sx + (float) Math.cos(a) * 7.6f * d, sy + (float) Math.sin(a) * 7.6f * d, line);
        }

        // Píldora de EV cuando el usuario desliza la exposición
        if (Math.abs(ev) >= 0.08f) {
            String evTxt = String.format(Locale.US, "%+.1f EV", ev);
            badgeText.setTextSize(10.5f * d);
            badgeText.setAlpha(lineAlpha);
            badgeBg.setAlpha(Math.round(190 * alpha));
            float tw = badgeText.measureText(evTxt) + 14f * d;
            float th = 20f * d;
            float by = Math.max(12f * d, fy - half - th - 8f * d);
            rect.set(fx - tw / 2f, by, fx + tw / 2f, by + th);
            c.drawRoundRect(rect, th / 2f, th / 2f, badgeBg);
            Paint.FontMetrics fm = badgeText.getFontMetrics();
            c.drawText(evTxt, fx, rect.centerY() - (fm.ascent + fm.descent) / 2f, badgeText);
        }
    }
}
