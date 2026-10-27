package com.signature.cinematic.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zoom estilo Cámara iOS / Google Pixel Pro:
 * - En reposo, discos de lente táctiles (.5 · 1× · 2 · 3 · 6) sobre el visor con micro-animaciones elásticas.
 * - Al deslizar el dedo sobre los discos se despliega una rueda en arco con escala logarítmica que gira
 *   bajo un puntero amarillo fijo, con distancias focales (13 / 24 / 48 / 70 / 140 MM) y ticks hápticos.
 */
public final class ZoomDialView extends View {
    public interface Listener {
        /** @param interactive true mientras el dedo desliza; false cuando toca un disco */
        void onZoomChanged(float zoom, boolean interactive);

        default void onOneXRetapped() {
        }
    }

    private static final float[] FOCAL_ZOOMS = {0.5f, 1f, 1.17f, 1.46f, 2f, 3f, 6f};
    private static final String[] FOCAL_LABELS = {"12MM", "24MM", "28MM", "35MM", "50MM", "73MM", "146MM"};

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Float> stops = new ArrayList<>();
    private float minZoom = 0.5f;
    private float maxZoom = 10f;
    private float zoom = 1f;
    private int iconRotation;
    private boolean single;
    private Listener listener;

    private boolean scrubbing;
    private boolean movedDuringTouch;
    private float dialVisibility;
    private ValueAnimator dialAnim;
    private float downX;
    private float downZoom;
    private int lastHapticStop = -1;
    private int lastHapticTick = -1000;
    private float tapBounce = 0f;
    private ValueAnimator tapBounceAnim;
    private float animatedActivePos = 1f;
    private boolean activePosInitialized = false;
    private float tapTargetZoom = -1f;
    private float focalBadgeAlpha = 0f;
    private ValueAnimator focalBadgeAnim;
    private final Runnable hideFocalBadgeRunnable = () -> animateFocalBadge(false);
    private final Runnable collapseRunnable = () -> animateDial(false);
    private final Runnable longPressRunnable = () -> {
        if (!movedDuringTouch && !scrubbing) {
            scrubbing = true;
            tapTargetZoom = -1f;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            animateDial(true);
        }
    };

    private void animateFocalBadge(boolean show) {
        if (focalBadgeAnim != null) {
            focalBadgeAnim.cancel();
        }
        focalBadgeAnim = ValueAnimator.ofFloat(focalBadgeAlpha, show ? 1f : 0f);
        focalBadgeAnim.setDuration(show ? 140 : 320);
        focalBadgeAnim.setInterpolator(new DecelerateInterpolator(1.4f));
        focalBadgeAnim.addUpdateListener(a -> {
            focalBadgeAlpha = (Float) a.getAnimatedValue();
            invalidate();
        });
        focalBadgeAnim.start();
    }

    private void pulseFocalBadge() {
        main.removeCallbacks(hideFocalBadgeRunnable);
        if (focalBadgeAlpha < 0.95f) {
            animateFocalBadge(true);
        }
        main.postDelayed(hideFocalBadgeRunnable, 950);
    }

    public void setTargetStopZoom(float target) {
        tapTargetZoom = target;
        pulseFocalBadge();
        invalidate();
    }

    public void clearTargetStopZoom() {
        tapTargetZoom = -1f;
        invalidate();
    }

    private void triggerTapBounce() {
        if (tapBounceAnim != null) {
            tapBounceAnim.cancel();
        }
        tapBounceAnim = ValueAnimator.ofFloat(1f, 0f);
        tapBounceAnim.setDuration(300);
        tapBounceAnim.setInterpolator(new OvershootInterpolator(1.8f));
        tapBounceAnim.addUpdateListener(a -> {
            tapBounce = (Float) a.getAnimatedValue();
            invalidate();
        });
        tapBounceAnim.start();
    }

    public ZoomDialView(Context context) {
        super(context);
        stops.add(0.5f);
        stops.add(1f);
        stops.add(2f);
        stops.add(3f);
        stops.add(6f);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textPaint.setFontFeatureSettings("tnum");
        smallText.setTextAlign(Paint.Align.CENTER);
        smallText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        smallText.setLetterSpacing(0.04f);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        pointer.setColor(IconView.YELLOW);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setConfig(List<Float> lensStops, float minZ, float maxZ) {
        stops.clear();
        if (lensStops != null && !lensStops.isEmpty()) {
            stops.addAll(lensStops);
        } else {
            stops.add(1f);
        }
        minZoom = Math.max(0.5f, minZ);
        maxZoom = Math.max(minZoom + 0.5f, Math.min(10f, maxZ));
        invalidate();
    }

    /** Retrato: un solo disco abajo a la izquierda en lugar de la fila de lentes. */
    public void setSingle(boolean s) {
        if (single != s) {
            single = s;
            invalidate();
        }
    }

    public void setZoom(float z) {
        float prev = zoom;
        zoom = Math.max(minZoom, Math.min(maxZoom, z));
        if (tapTargetZoom > 0f && Math.abs(zoom - tapTargetZoom) < 0.015f) {
            tapTargetZoom = -1f;
        }
        if (Math.abs(zoom - prev) > 0.008f) {
            pulseFocalBadge();
        }
        invalidate();
    }

    public float zoom() {
        return zoom;
    }

    public void setIconRotation(int deg) {
        iconRotation = deg;
        invalidate();
    }

    private void animateDial(boolean open) {
        if (dialAnim != null) {
            dialAnim.cancel();
        }
        dialAnim = ValueAnimator.ofFloat(dialVisibility, open ? 1f : 0f);
        dialAnim.setDuration(open ? 160 : 260);
        dialAnim.setInterpolator(new DecelerateInterpolator(1.5f));
        dialAnim.addUpdateListener(a -> {
            dialVisibility = (Float) a.getAnimatedValue();
            invalidate();
        });
        dialAnim.start();
    }

    // ------------------------------------------------------------ geometría

    private float d() {
        return getResources().getDisplayMetrics().density;
    }

    private float pillCy() {
        return getHeight() - 26f * d();
    }

    private float pillStep() {
        return 46f * d();
    }

    private float pillStartX() {
        return (getWidth() - (stops.size() - 1) * pillStep()) / 2f;
    }

    private float singleX() {
        return 40f * d();
    }

    private float arcRadius() {
        return getWidth() * 0.95f;
    }

    private float arcTop() {
        return 46f * d();
    }

    /** Radianes de arco por unidad de ln(zoom): ~75 dp de recorrido por cada factor e. */
    private float radPerLn() {
        return 75f * d() / arcRadius();
    }

    // ------------------------------------------------------------ dibujo

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (stops.isEmpty() || w <= 0 || h <= 0) {
            return;
        }
        if (dialVisibility > 0.02f) {
            drawArc(c, dialVisibility);
        }
        float pillAlpha = 1f - dialVisibility;
        if (pillAlpha > 0.02f) {
            if (single) {
                drawSingle(c, pillAlpha);
            } else {
                drawPills(c, pillAlpha);
            }
        }
    }

    private float continuousStopPosition(float z) {
        if (stops.size() <= 1) {
            return 0f;
        }
        if (z <= stops.get(0)) {
            return 0f;
        }
        int last = stops.size() - 1;
        if (z >= stops.get(last)) {
            return last;
        }
        for (int i = 0; i < last; i++) {
            float s0 = stops.get(i);
            float s1 = stops.get(i + 1);
            if (z >= s0 && z <= s1) {
                double ln0 = Math.log(Math.max(0.1f, s0));
                double ln1 = Math.log(Math.max(0.1f, s1));
                double lnz = Math.log(Math.max(0.1f, z));
                float t = (ln1 > ln0) ? (float) ((lnz - ln0) / (ln1 - ln0)) : 0f;
                // Smoothstep para que el indicador se adhiera magnéticamente a cada lente pero deslice con fluidez
                t = t * t * (3f - 2f * t);
                return i + t;
            }
        }
        return activeStopIndex();
    }

    private void drawPills(Canvas c, float alpha) {
        float d = d();
        float cy = pillCy();
        float startX = pillStartX();
        float displayZoom = (tapTargetZoom > 0f) ? tapTargetZoom : zoom;
        int active = activeStopIndexFor(displayZoom);

        float targetPos = (tapTargetZoom > 0f) ? active : continuousStopPosition(zoom);
        if (!activePosInitialized) {
            animatedActivePos = targetPos;
            activePosInitialized = true;
        } else {
            float diff = targetPos - animatedActivePos;
            if (Math.abs(diff) > 0.003f) {
                animatedActivePos += diff * 0.28f;
                postInvalidateOnAnimation();
            } else {
                animatedActivePos = targetPos;
            }
        }

        // Single unified Liquid Glass pill capsule enclosing all stops
        float padX = 20f * d;
        float pillHalfH = 18f * d;
        float left = startX - padX;
        float right = startX + (stops.size() - 1) * pillStep() + padX;
        RectF capsuleRect = new RectF(left, cy - pillHalfH, right, cy + pillHalfH);

        // Dark frosted glass background with subtle inner depth
        discPaint.setStyle(Paint.Style.FILL);
        discPaint.setColor(Color.argb(Math.round(162 * alpha), 18, 18, 22));
        c.drawRoundRect(capsuleRect, pillHalfH, pillHalfH, discPaint);

        // Specular Liquid Glass border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.0f * d);
        borderPaint.setColor(Color.argb(Math.round(58 * alpha), 255, 255, 255));
        c.drawRoundRect(capsuleRect, pillHalfH, pillHalfH, borderPaint);

        // Indicador circular activo que se desliza fluidamente (Liquid Glass Gliding Pill)
        float indicatorX = startX + animatedActivePos * pillStep();
        float bounceScale = (tapBounce > 0f) ? (1f + 0.14f * tapBounce) : 1f;
        float activeRadius = 15.2f * d * bounceScale;

        Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(Color.argb(Math.round(34 * alpha), 255, 214, 10));
        c.drawCircle(indicatorX, cy, activeRadius + 2.5f * d, glowPaint);

        Paint activeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        activeBg.setStyle(Paint.Style.FILL);
        activeBg.setColor(Color.argb(Math.round(82 * alpha), 255, 214, 10));
        c.drawCircle(indicatorX, cy, activeRadius, activeBg);

        Paint activeRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        activeRing.setStyle(Paint.Style.STROKE);
        activeRing.setStrokeWidth(1.0f * d);
        activeRing.setColor(Color.argb(Math.round(155 * alpha), 255, 214, 10));
        c.drawCircle(indicatorX, cy, activeRadius, activeRing);

        // Micro-cápsula flotante con milímetros equivalentes (ej. "24 MM · 1.0×") durante transiciones de zoom
        float badgeVis = focalBadgeAlpha * alpha;
        if (badgeVis > 0.03f) {
            int eqMm = Math.round(24f * zoom);
            String badgeText = String.format(Locale.US, "%d MM", eqMm);
            smallText.setTextSize(9.5f * d);
            smallText.setColor(Color.argb(Math.round(235 * badgeVis), 255, 214, 10));
            float bw = smallText.measureText(badgeText) + 16f * d;
            float bh = 18f * d;
            float by = cy - pillHalfH - 13f * d - (1f - focalBadgeAlpha) * 4f * d;
            RectF badgeRect = new RectF(indicatorX - bw / 2f, by - bh / 2f, indicatorX + bw / 2f, by + bh / 2f);
            discPaint.setColor(Color.argb(Math.round(175 * badgeVis), 18, 18, 22));
            c.drawRoundRect(badgeRect, bh / 2f, bh / 2f, discPaint);
            borderPaint.setColor(Color.argb(Math.round(52 * badgeVis), 255, 214, 10));
            c.drawRoundRect(badgeRect, bh / 2f, bh / 2f, borderPaint);
            Paint.FontMetrics bfm = smallText.getFontMetrics();
            c.drawText(badgeText, indicatorX, by - (bfm.ascent + bfm.descent) / 2f, smallText);
        }

        for (int i = 0; i < stops.size(); i++) {
            boolean on = i == active;
            float bx = startX + i * pillStep();
            float proximity = Math.max(0f, 1f - Math.abs(animatedActivePos - i));

            c.save();
            c.rotate(iconRotation, bx, cy);
            textPaint.setTextSize((11.0f + 1.6f * proximity) * d);
            textPaint.setColor(on ? withAlpha(IconView.YELLOW, alpha) : withAlpha(Color.WHITE, alpha * 0.86f));
            float stopZ = stops.get(i);
            String label;
            if (on) {
                label = (Math.abs(displayZoom - stopZ) < 0.045f)
                        ? (stopZ == 1f ? "1×" : formatZoom(stopZ) + "×")
                        : formatZoom(displayZoom) + "×";
            } else {
                label = formatZoom(stopZ);
            }
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            c.drawText(label, bx, cy - (fm.ascent + fm.descent) / 2f, textPaint);
            c.restore();
        }
    }

    private void drawSingle(Canvas c, float alpha) {
        float d = d();
        float cx = singleX();
        float cy = pillCy();
        discPaint.setColor(Color.argb(Math.round(170 * alpha), 22, 22, 24));
        c.drawCircle(cx, cy, 19f * d, discPaint);
        c.save();
        c.rotate(iconRotation, cx, cy);
        textPaint.setTextSize(11.5f * d);
        textPaint.setColor(withAlpha(Math.abs(zoom - 1f) < 0.03f ? Color.WHITE : IconView.YELLOW, alpha));
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        c.drawText(formatZoom(zoom) + "×", cx, cy - (fm.ascent + fm.descent) / 2f, textPaint);
        c.restore();
    }

    private void drawArc(Canvas c, float vis) {
        float d = d();
        float w = getWidth();
        float cx = w / 2f;
        float R = arcRadius();
        float top = arcTop() + (1f - vis) * 24f * d;
        float cy = top + R;
        float k = radPerLn();
        double lnZoom = Math.log(zoom);

        // Fondo oscuro de la rueda
        arcBg.setColor(Color.argb(Math.round(150 * vis), 0, 0, 0));
        c.drawCircle(cx, cy, R + 34f * d, arcBg);

        // Ticks: 0.1 entre .5–1, 0.2 entre 1–3, 0.5 entre 3–10
        float maxAngle = (float) Math.asin(Math.min(1f, (w / 2f + 12f * d) / R));
        List<Float> marks = new ArrayList<>();
        for (float z = (float) Math.ceil(minZoom * 10f - 1e-3f) / 10f; z <= maxZoom + 1e-3f;
                z = Math.round((z + tickStep(z)) * 10f) / 10f) {
            if (!nearMajor(z)) {
                marks.add(z);
            }
        }
        int minorCount = marks.size();
        marks.addAll(stops);
        if (maxZoom >= 1.5f) {
            if (!nearMajor(1.17f)) {
                marks.add(1.17f);
            }
            if (!nearMajor(1.46f)) {
                marks.add(1.46f);
            }
        }
        if (!nearMajor(maxZoom)) {
            marks.add(maxZoom);
        }
        for (int mi = 0; mi < marks.size(); mi++) {
            float z = marks.get(mi);
            float a = (float) ((Math.log(z) - lnZoom) * k);
            if (Math.abs(a) > maxAngle) {
                continue;
            }
            boolean major = mi >= minorCount;
            float len = (major ? 12f : 6f) * d;
            float fade = 1f - Math.abs(a) / maxAngle * 0.6f;
            tickPaint.setStrokeWidth((major ? 1.6f : 1.0f) * d);
            tickPaint.setColor(Color.argb(Math.round((major ? 235 : 150) * vis * fade), 255, 255, 255));
            float sin = (float) Math.sin(a);
            float cos = (float) Math.cos(a);
            c.drawLine(cx + sin * R, cy - cos * R, cx + sin * (R - len), cy - cos * (R - len), tickPaint);

            if (major) {
                float lr = R - 26f * d;
                textPaint.setTextSize(11f * d);
                textPaint.setColor(Color.argb(Math.round(240 * vis * fade), 255, 255, 255));
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                c.drawText(formatZoom(z), cx + sin * lr, cy - cos * lr - (fm.ascent + fm.descent) / 2f, textPaint);
                String focal = focalLabel(z);
                if (focal != null) {
                    float fr = R - 42f * d;
                    smallText.setTextSize(8.5f * d);
                    smallText.setColor(Color.argb(Math.round(185 * vis * fade), 255, 255, 255));
                    Paint.FontMetrics sm = smallText.getFontMetrics();
                    c.drawText(focal, cx + sin * fr, cy - cos * fr - (sm.ascent + sm.descent) / 2f, smallText);
                }
            }
        }

        // Puntero fijo + valor actual
        int pAlpha = Math.round(255 * vis);
        pointer.setAlpha(pAlpha);
        tri.reset();
        tri.moveTo(cx - 5f * d, top - 9f * d);
        tri.lineTo(cx + 5f * d, top - 9f * d);
        tri.lineTo(cx, top - 2f * d);
        tri.close();
        c.drawPath(tri, pointer);
        textPaint.setTextSize(13f * d);
        textPaint.setColor(withAlpha(IconView.YELLOW, vis));
        c.drawText(formatZoom(zoom) + "×", cx, top - 15f * d, textPaint);
    }

    private static float tickStep(float z) {
        if (z < 0.999f) {
            return 0.1f;
        }
        if (z < 2.999f) {
            return 0.2f;
        }
        return 0.5f;
    }

    private boolean nearMajor(float z) {
        for (float s : stops) {
            if (Math.abs(z - s) < s * 0.06f) {
                return true;
            }
        }
        return false;
    }

    private static String focalLabel(float z) {
        for (int i = 0; i < FOCAL_ZOOMS.length; i++) {
            if (Math.abs(z - FOCAL_ZOOMS[i]) < FOCAL_ZOOMS[i] * 0.06f) {
                return FOCAL_LABELS[i];
            }
        }
        return null;
    }

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int activeStopIndex() {
        return activeStopIndexFor(zoom);
    }

    private int activeStopIndexFor(float z) {
        int active = 0;
        for (int i = 0; i < stops.size(); i++) {
            if (z >= stops.get(i) - 0.04f) {
                active = i;
            }
        }
        return active;
    }

    public static String formatZoom(float z) {
        if (z < 0.95f) {
            float v = Math.max(0.5f, Math.round(z * 10f) / 10f);
            return String.format(Locale.US, "%.1f", v).replace("0.", ".");
        }
        return Math.abs(z - Math.round(z)) < 0.07f ? String.valueOf(Math.round(z))
                : String.format(Locale.US, "%.1f", z);
    }

    // ------------------------------------------------------------ gestos

    /** Solo captura toques sobre los discos (o sobre la rueda abierta); el resto pasa al visor. */
    private boolean hitsControl(float x, float y) {
        float d = d();
        if (dialVisibility > 0.05f) {
            return y > arcTop() - 24f * d;
        }
        if (Math.abs(y - pillCy()) > 28f * d) {
            return false;
        }
        if (single) {
            return Math.abs(x - singleX()) < 32f * d;
        }
        float startX = pillStartX();
        return x > startX - 30f * d && x < startX + (stops.size() - 1) * pillStep() + 30f * d;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float d = d();
        if (stops.isEmpty()) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hitsControl(e.getX(), e.getY())) {
                    return false;
                }
                main.removeCallbacks(collapseRunnable);
                main.removeCallbacks(longPressRunnable);
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                downX = e.getX();
                downZoom = zoom;
                movedDuringTouch = false;
                scrubbing = dialVisibility > 0.05f;
                lastHapticStop = activeStopIndex();
                lastHapticTick = Math.round((float) (Math.log(zoom) * 18.0));
                if (!scrubbing) {
                    main.postDelayed(longPressRunnable, 210);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                if (!movedDuringTouch && Math.abs(dx) > 6f * d) {
                    movedDuringTouch = true;
                    main.removeCallbacks(longPressRunnable);
                    if (!scrubbing) {
                        scrubbing = true;
                        animateDial(true);
                    }
                    downX = e.getX();
                    downZoom = zoom;
                    dx = 0f;
                }
                if (scrubbing && movedDuringTouch) {
                    // Arrastrar a la derecha gira la escala hacia los valores menores
                    double ln = Math.log(downZoom) - dx / (75f * d);
                    float target = (float) Math.exp(ln);
                    int nearStop = -1;
                    for (int i = 0; i < stops.size(); i++) {
                        float s = stops.get(i);
                        if (Math.abs(target - s) < s * 0.035f) {
                            target = s;
                            nearStop = i;
                            break;
                        }
                    }
                    target = Math.max(minZoom, Math.min(maxZoom, target));
                    if (nearStop >= 0 && nearStop != lastHapticStop) {
                        lastHapticStop = nearStop;
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    } else {
                        int tick = Math.round((float) (Math.log(target) * 18.0));
                        if (tick != lastHapticTick) {
                            lastHapticTick = tick;
                            if (android.os.Build.VERSION.SDK_INT >= 34) {
                                performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
                            } else {
                                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                            }
                        }
                    }
                    zoom = target;
                    invalidate();
                    if (listener != null) {
                        listener.onZoomChanged(zoom, true);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPressRunnable);
                if (scrubbing) {
                    scrubbing = false;
                    main.postDelayed(collapseRunnable, 450);
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    onTap(e.getX());
                }
                return true;
            default:
                return true;
        }
    }

    private void onTap(float x) {
        float d = d();
        if (single) {
            int idx = activeStopIndex();
            float next = Math.abs(zoom - stops.get(idx)) > 0.05f ? stops.get(idx)
                    : stops.get((idx + 1) % stops.size());
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) {
                listener.onZoomChanged(next, false);
            }
            return;
        }
        float startX = pillStartX();
        int best = -1;
        float bestDist = 28f * d;
        for (int i = 0; i < stops.size(); i++) {
            float dist = Math.abs(x - (startX + i * pillStep()));
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        triggerTapBounce();
        float target = stops.get(best);
        tapTargetZoom = target;
        pulseFocalBadge();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (Math.abs(target - 1f) < 0.05f && downZoom >= 0.98f && downZoom < 1.6f) {
            if (listener != null) {
                listener.onOneXRetapped();
            }
        } else if (listener != null) {
            listener.onZoomChanged(target, false);
        }
    }
}
