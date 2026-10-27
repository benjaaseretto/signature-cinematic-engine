package com.signature.cinematic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * HUD de telemetría en vivo estilo iPhone 17 Pro / Final Cut Camera:
 * lente óptica activa (mm + apertura), obturador 1/x, ISO real, look activo,
 * histograma de luminancia de 32 barras en tiempo real y vúmetro de audio.
 */
public final class ProHudView extends View {
    private static final int AMBER = Color.rgb(255, 214, 10);
    private static final int GREEN = Color.rgb(48, 209, 88);
    private static final int RED = Color.rgb(255, 69, 58);

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint histLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF pill = new RectF();
    private final Path histPath = new Path();

    private int iso = 100;
    private long exposureNs = 16_666_667L;
    private float aperture = 1.6f;
    private int focalMm = 24;
    private String lookName = "PRO";
    private String stabName = "SMART EIS";
    private float audioDb = -90f;
    private boolean videoMode = true;
    private float clipFraction;
    private final float[] smoothBins = new float[32];

    public ProHudView(Context context) {
        super(context);
        bgPaint.setColor(Color.argb(175, 16, 16, 18));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(Color.argb(48, 255, 255, 255));

        labelPaint.setColor(Color.argb(145, 255, 255, 255));
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setLetterSpacing(0.06f);

        valuePaint.setColor(Color.WHITE);
        valuePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valuePaint.setFontFeatureSettings("tnum");

        histLinePaint.setStyle(Paint.Style.STROKE);
        histLinePaint.setColor(Color.argb(210, 255, 255, 255));
    }

    public void update(int iso, long exposureNs, float aperture, int focalMm, String lookName,
            String stabName, float audioDb, boolean videoMode, float[] bins, float clipFraction) {
        if (iso > 0) {
            this.iso = iso;
        }
        if (exposureNs > 0) {
            this.exposureNs = exposureNs;
        }
        if (aperture > 0f) {
            this.aperture = aperture;
        }
        this.focalMm = focalMm;
        this.lookName = lookName;
        this.stabName = stabName;
        this.audioDb = audioDb;
        this.videoMode = videoMode;
        this.clipFraction = clipFraction;
        if (bins != null && bins.length == 32) {
            for (int i = 0; i < 32; i++) {
                smoothBins[i] += (bins[i] - smoothBins[i]) * 0.35f;
            }
        }
        invalidate();
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
        rect.set(d, d, w - d, h - d);
        borderPaint.setStrokeWidth(1f * d);
        c.drawRoundRect(rect, r, r, bgPaint);
        c.drawRoundRect(rect, r, r, borderPaint);

        float padX = 12f * d;
        float cy = h / 2f;

        // 1. Badge de Look (ej. "17 PRO")
        labelPaint.setTextSize(9.5f * d);
        valuePaint.setTextSize(10.5f * d);
        String badge = lookName.toUpperCase(Locale.US);
        float bw = valuePaint.measureText(badge) + 12f * d;
        float bh = h - 12f * d;
        pill.set(padX, (h - bh) / 2f, padX + bw, (h + bh) / 2f);
        badgePaint.setColor(Color.argb(42, 255, 214, 10));
        c.drawRoundRect(pill, bh / 2f, bh / 2f, badgePaint);
        valuePaint.setColor(AMBER);
        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        c.drawText(badge, pill.centerX() - valuePaint.measureText(badge) / 2f, cy - (fm.ascent + fm.descent) / 2f, valuePaint);

        float x = pill.right + 10f * d;

        // 2. Distancia focal + apertura
        String lensText = String.format(Locale.US, "%dmm ƒ%.1f", focalMm, aperture);
        valuePaint.setColor(Color.WHITE);
        c.drawText(lensText, x, cy - (fm.ascent + fm.descent) / 2f, valuePaint);
        x += valuePaint.measureText(lensText) + 10f * d;

        drawDot(c, x, cy, d);
        x += 8f * d;

        // 3. Obturador
        String shutter = formatShutter(exposureNs);
        valuePaint.setColor(AMBER);
        c.drawText(shutter, x, cy - (fm.ascent + fm.descent) / 2f, valuePaint);
        x += valuePaint.measureText(shutter) + 10f * d;

        drawDot(c, x, cy, d);
        x += 8f * d;

        // 4. ISO
        String isoStr = "ISO " + iso;
        valuePaint.setColor(Color.argb(235, 255, 255, 255));
        c.drawText(isoStr, x, cy - (fm.ascent + fm.descent) / 2f, valuePaint);

        // 5. Derecha: Histograma en vivo + Vúmetro de audio
        float rightEdge = w - padX;
        if (videoMode) {
            float meterW = 26f * d;
            drawAudioMeter(c, rightEdge - meterW, cy - 6f * d, meterW, 12f * d, d);
            rightEdge -= meterW + 8f * d;
        }
        float histW = 52f * d;
        float histH = h - 14f * d;
        if (rightEdge - histW > x + valuePaint.measureText(isoStr) + 8f * d) {
            drawHistogram(c, rightEdge - histW, (h - histH) / 2f, histW, histH, d);
        }
    }

    private void drawDot(Canvas c, float x, float cy, float d) {
        badgePaint.setColor(Color.argb(90, 255, 255, 255));
        c.drawCircle(x, cy, 1.6f * d, badgePaint);
    }

    private void drawHistogram(Canvas c, float left, float top, float w, float h, float d) {
        pill.set(left - 3f * d, top - 2f * d, left + w + 3f * d, top + h + 2f * d);
        badgePaint.setColor(Color.argb(95, 0, 0, 0));
        c.drawRoundRect(pill, 4f * d, 4f * d, badgePaint);

        histPath.reset();
        histPath.moveTo(left, top + h);
        for (int i = 0; i < 32; i++) {
            float bx = left + (i / 31f) * w;
            float by = top + h - Math.max(0.05f, Math.min(1f, smoothBins[i])) * h;
            histPath.lineTo(bx, by);
        }
        histPath.lineTo(left + w, top + h);
        histPath.close();

        boolean clipping = clipFraction > 0.015f;
        int topColor = clipping ? Color.argb(190, 255, 69, 58) : Color.argb(165, 255, 214, 10);
        int botColor = Color.argb(25, 255, 255, 255);
        histPaint.setShader(new LinearGradient(0, top, 0, top + h, topColor, botColor, Shader.TileMode.CLAMP));
        c.drawPath(histPath, histPaint);

        histLinePaint.setStrokeWidth(1.1f * d);
        histLinePaint.setColor(clipping ? RED : Color.argb(220, 255, 255, 255));
        c.drawPath(histPath, histLinePaint);
    }

    private void drawAudioMeter(Canvas c, float left, float top, float w, float h, float d) {
        int bars = 6;
        float gap = 1.6f * d;
        float bw = (w - gap * (bars - 1)) / bars;
        // Normaliza -55 dB .. 0 dB a 0..1
        float norm = Math.max(0.08f, Math.min(1f, (audioDb + 55f) / 55f));
        int lit = Math.round(norm * bars);
        for (int i = 0; i < bars; i++) {
            float bx = left + i * (bw + gap);
            boolean on = i < lit;
            int col = i >= 5 ? RED : (i >= 4 ? AMBER : GREEN);
            meterPaint.setColor(on ? col : Color.argb(55, 255, 255, 255));
            pill.set(bx, top + (1f - (0.45f + 0.55f * (i + 1) / (float) bars)) * h, bx + bw, top + h);
            c.drawRoundRect(pill, d, d, meterPaint);
        }
    }

    private static String formatShutter(long ns) {
        if (ns <= 0) {
            return "1/60";
        }
        double sec = ns / 1e9;
        if (sec >= 0.4) {
            return String.format(Locale.US, "%.1fs", sec);
        }
        int denom = Math.max(1, (int) Math.round(1.0 / sec));
        return "1/" + denom;
    }
}
