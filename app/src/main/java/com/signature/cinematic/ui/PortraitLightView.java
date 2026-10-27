package com.signature.cinematic.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Selector de iluminación de Retrato ("LUZ NATURAL", "LUZ DE ESTUDIO", "LUZ DE CONTORNO",
 * "LUZ DE ESCENARIO", "LUZ ESCENARIO MONO"):
 * píldora amarilla superior + carrusel en arco con cubos hexagonales 3D y esferas sombreadas.
 */
public final class PortraitLightView extends View {
    public enum Preset {
        NATURAL("LUZ NATURAL", "pro", 0.00f, 0.00f, 0.35f, 0.15f),
        STUDIO("LUZ DE ESTUDIO", "natural_warm", 0.18f, 0.06f, 0.48f, 0.28f),
        CONTOUR("LUZ DE CONTORNO", "cine_warm", -0.38f, 0.02f, 0.30f, 0.10f),
        STAGE("LUZ DE ESCENARIO", "cine_cool", -0.65f, -0.10f, 0.25f, 0.05f),
        MONO("ESCENARIO MONO", "mono", -0.50f, -1.00f, 0.25f, 0.05f);

        public final String label;
        public final String profileId;
        public final float tone;
        public final float color;
        public final float softSkin;
        public final float glow;

        Preset(String label, String profileId, float tone, float color, float softSkin, float glow) {
            this.label = label;
            this.profileId = profileId;
            this.tone = tone;
            this.color = color;
            this.softSkin = softSkin;
            this.glow = glow;
        }
    }

    public interface Listener {
        void onPresetChanged(Preset preset);
    }

    private static final Preset[] PRESETS = Preset.values();

    private final Paint pillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hexStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint circleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stageBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final Path hexPath = new Path();

    private int selected;
    private float pos;
    private ValueAnimator anim;
    private float downX;
    private float downPos;
    private boolean dragging;
    private Listener listener;

    public PortraitLightView(Context context) {
        super(context);
        pillBg.setColor(IconView.YELLOW);
        pillText.setColor(Color.BLACK);
        pillText.setTextAlign(Paint.Align.CENTER);
        pillText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        pillText.setLetterSpacing(0.06f);

        arcLine.setStyle(Paint.Style.STROKE);
        hexStroke.setStyle(Paint.Style.STROKE);
        hexStroke.setStrokeJoin(Paint.Join.ROUND);
        hexStroke.setStrokeCap(Paint.Cap.ROUND);
        circleStroke.setStyle(Paint.Style.STROKE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Preset current() {
        return PRESETS[selected];
    }

    public void select(int index, boolean notify) {
        int clamped = Math.max(0, Math.min(PRESETS.length - 1, index));
        boolean changed = clamped != selected;
        selected = clamped;
        if (anim != null) {
            anim.cancel();
        }
        anim = ValueAnimator.ofFloat(pos, selected);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator(1.6f));
        anim.addUpdateListener(a -> {
            pos = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
        if (changed) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (notify && listener != null) {
                listener.onPresetChanged(PRESETS[selected]);
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;

        // 1. Píldora amarilla con el nombre del esquema de iluminación activo
        String label = PRESETS[selected].label;
        pillText.setTextSize(10.5f * d);
        float pw = pillText.measureText(label) + 22f * d;
        float ph = 21f * d;
        float py = 12f * d;
        pillRect.set(cx - pw / 2f, py - ph / 2f, cx + pw / 2f, py + ph / 2f);
        c.drawRoundRect(pillRect, ph / 2f, ph / 2f, pillBg);
        Paint.FontMetrics fm = pillText.getFontMetrics();
        c.drawText(label, cx, py - (fm.ascent + fm.descent) / 2f, pillText);

        // 2. Carrusel curvo de esferas/cubos 3D debajo de la píldora
        float rowCy = 52f * d;
        float step = 50f * d;

        for (int i = 0; i < PRESETS.length; i++) {
            float delta = i - pos;
            float ix = cx + delta * step;
            float iy = rowCy + (delta * delta) * 3.2f * d;
            float dist = Math.abs(delta);
            if (dist > 3.2f) {
                continue;
            }
            boolean isCenter = dist < 0.35f;
            float scale = Math.max(0.74f, 1f - dist * 0.14f);
            float r = 16.5f * d * scale;

            // Esfera sombreada según el tipo de luz
            drawShadedSphere(c, ix, iy, r * 0.68f, i, isCenter);

            if (isCenter) {
                // Cubo hexagonal amarillo alrededor del icono seleccionado
                hexStroke.setColor(IconView.YELLOW);
                hexStroke.setStrokeWidth(1.6f * d);
                drawHexCube(c, ix, iy, r * 1.05f);
            } else {
                circleStroke.setStrokeWidth(1.2f * d);
                circleStroke.setColor(Color.argb(Math.round(165 * (1f - dist * 0.22f)), 255, 255, 255));
                c.drawCircle(ix, iy, r * 0.90f, circleStroke);
            }
        }
    }

    private void drawShadedSphere(Canvas c, float cx, float cy, float r, int styleIdx, boolean active) {
        int alpha = active ? 245 : 185;
        int hi = Color.argb(alpha, 255, 255, 255);
        int mid = Color.argb(alpha, 175, 175, 182);
        int lo = Color.argb(alpha, 38, 38, 44);
        float offX = cx - r * 0.28f;
        float offY = cy - r * 0.28f;
        if (styleIdx == 1) { // Estudio: más brillante
            mid = Color.argb(alpha, 220, 220, 226);
            offX = cx;
            offY = cy - r * 0.15f;
        } else if (styleIdx == 2) { // Contorno: iluminado por los bordes
            offX = cx + r * 0.35f;
            lo = Color.argb(alpha, 22, 22, 26);
        } else if (styleIdx == 3 || styleIdx == 4) { // Escenario: fondo negro
            stageBg.setColor(Color.argb(210, 10, 10, 12));
            c.drawCircle(cx, cy, r * 1.18f, stageBg);
        }
        spherePaint.setShader(new RadialGradient(
                offX, offY, Math.max(1f, r * 1.35f),
                new int[] {hi, mid, lo}, new float[] {0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, spherePaint);
    }

    private void drawHexCube(Canvas c, float cx, float cy, float r) {
        hexPath.reset();
        for (int k = 0; k < 6; k++) {
            double a = Math.toRadians(-90 + k * 60);
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            if (k == 0) {
                hexPath.moveTo(x, y);
            } else {
                hexPath.lineTo(x, y);
            }
        }
        hexPath.close();
        c.drawPath(hexPath, hexStroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float d = getResources().getDisplayMetrics().density;
        float step = 50f * d;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                downX = e.getX();
                downPos = pos;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                if (!dragging && Math.abs(dx) > 5f * d) {
                    dragging = true;
                }
                if (dragging) {
                    pos = Math.max(0f, Math.min(PRESETS.length - 1, downPos - dx / step));
                    int near = Math.round(pos);
                    if (near != selected) {
                        selected = near;
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        if (listener != null) {
                            listener.onPresetChanged(PRESETS[selected]);
                        }
                    }
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    select(Math.round(pos), true);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    float tapDelta = (e.getX() - getWidth() / 2f) / step;
                    int target = Math.round(selected + tapDelta);
                    select(target, true);
                }
                return true;
            default:
                return true;
        }
    }
}
