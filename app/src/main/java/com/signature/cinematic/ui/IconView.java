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

/**
 * Íconos vectoriales estilo iOS 27 Liquid Glass:
 * discos translúcidos con gradiente de refracción especular y el ícono de 6 puntos (SIX_DOTS) de iOS 27.
 */
public final class IconView extends View {
    public enum Kind {
        FLASH, FLASH_OFF, FLASH_AUTO, CHEVRON_UP, CHEVRON_DOWN, TIMER, ASPECT, STYLES, EXPOSURE, FLIP, GEAR,
        ACTION, GRID, SIX_DOTS, TEXT, F_STOP, NIGHT, STYLES_A,
    }

    public static final int YELLOW = Color.rgb(255, 214, 10);

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint discBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private Kind kind;
    private String label = "";
    private boolean active;
    private boolean disc;

    public IconView(Context context, Kind kind) {
        super(context);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        discBorder.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    }

    public void setKind(Kind k) {
        kind = k;
        invalidate();
    }

    public void setLabel(String l) {
        label = l;
        invalidate();
    }

    public void setActive(boolean a) {
        active = a;
        invalidate();
    }

    public void setDisc(boolean d) {
        disc = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        float d = getResources().getDisplayMetrics().density;
        int color = active ? YELLOW : Color.WHITE;
        if (disc) {
            float r = s / 2f - 0.8f * d;
            int topCol = active ? Color.argb(92, 255, 214, 10) : Color.argb(135, 68, 68, 76);
            int botCol = active ? Color.argb(42, 255, 214, 10) : Color.argb(110, 22, 22, 26);
            glassFill.setShader(new LinearGradient(0, cy - r, 0, cy + r, topCol, botCol, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, glassFill);

            discBorder.setStrokeWidth(1.0f * d);
            int rimTop = active ? Color.argb(210, 255, 214, 10) : Color.argb(140, 255, 255, 255);
            int rimBot = active ? Color.argb(70, 255, 214, 10) : Color.argb(32, 255, 255, 255);
            discBorder.setShader(new LinearGradient(0, cy - r, 0, cy + r, rimTop, rimBot, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, discBorder);
        }
        float u = s / 24f * (disc ? 0.68f : 0.86f);
        stroke.setColor(color);
        stroke.setStrokeWidth(1.75f * u);
        fill.setColor(color);
        text.setColor(color);
        path.reset();
        switch (kind) {
            case FLASH:
            case FLASH_OFF:
            case FLASH_AUTO:
                bolt(cx, cy, u);
                c.drawPath(path, stroke);
                if (kind == Kind.FLASH_OFF) {
                    c.drawLine(cx - 7 * u, cy - 8 * u, cx + 7 * u, cy + 8 * u, stroke);
                } else if (kind == Kind.FLASH_AUTO) {
                    text.setTextSize(7.5f * u);
                    c.drawText("A", cx + 7.5f * u, cy + 9f * u, text);
                }
                break;
            case CHEVRON_UP:
            case CHEVRON_DOWN:
                float dir = kind == Kind.CHEVRON_UP ? -1f : 1f;
                path.moveTo(cx - 6.5f * u, cy - 2.8f * u * dir);
                path.lineTo(cx, cy + 2.8f * u * dir);
                path.lineTo(cx + 6.5f * u, cy - 2.8f * u * dir);
                c.drawPath(path, stroke);
                break;
            case TIMER:
                c.drawCircle(cx, cy + u, 8 * u, stroke);
                c.drawLine(cx, cy + u, cx, cy - 4 * u, stroke);
                c.drawLine(cx - 2.5f * u, cy - 9.5f * u, cx + 2.5f * u, cy - 9.5f * u, stroke);
                break;
            case ASPECT:
                rect.set(cx - 9 * u, cy - 6.5f * u, cx + 9 * u, cy + 6.5f * u);
                c.drawRoundRect(rect, 2 * u, 2 * u, stroke);
                break;
            case STYLES:
            case STYLES_A:
                for (int i = 0; i < 3; i++) {
                    double a = Math.toRadians(-90 + 120 * i);
                    c.drawCircle(cx + (float) Math.cos(a) * 3.6f * u, cy + (float) Math.sin(a) * 3.6f * u, 5f * u, stroke);
                }
                if (kind == Kind.STYLES_A) {
                    text.setTextSize(7.5f * u);
                    c.drawText("A", cx + 8.5f * u, cy + 10f * u, text);
                }
                break;
            case F_STOP:
                text.setTypeface(Typeface.create("serif", Typeface.BOLD_ITALIC));
                text.setTextSize(17f * u);
                Paint.FontMetrics ffm = text.getFontMetrics();
                c.drawText("ƒ", cx, cy - (ffm.ascent + ffm.descent) / 2f, text);
                text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                break;
            case NIGHT:
                path.addCircle(cx - 0.5f * u, cy, 8f * u, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(cx + 4.5f * u, cy - 3.5f * u, 7f * u, Path.Direction.CW);
                path.op(bite, Path.Op.DIFFERENCE);
                c.drawPath(path, stroke);
                text.setTextSize(7.5f * u);
                c.drawText("A", cx + 8.5f * u, cy + 10f * u, text);
                break;
            case EXPOSURE:
                c.drawCircle(cx, cy, 9 * u, stroke);
                c.drawLine(cx - 6.4f * u, cy + 6.4f * u, cx + 6.4f * u, cy - 6.4f * u, stroke);
                c.drawLine(cx - 6 * u, cy - 3 * u, cx - 1.5f * u, cy - 3 * u, stroke);
                c.drawLine(cx - 3.75f * u, cy - 5.25f * u, cx - 3.75f * u, cy - 0.75f * u, stroke);
                c.drawLine(cx + 1.5f * u, cy + 3.5f * u, cx + 6 * u, cy + 3.5f * u, stroke);
                break;
            case FLIP:
                rect.set(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u);
                c.drawArc(rect, 200, 140, false, stroke);
                c.drawArc(rect, 20, 140, false, stroke);
                arrow(c, cx + 7.5f * u, cy - 2.7f * u, u, 1);
                arrow(c, cx - 7.5f * u, cy + 2.7f * u, u, -1);
                break;
            case GEAR:
                c.drawCircle(cx, cy, 5.5f * u, stroke);
                c.drawCircle(cx, cy, 2f * u, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.toRadians(45 * i);
                    float x0 = cx + (float) Math.cos(a) * 6.5f * u;
                    float y0 = cy + (float) Math.sin(a) * 6.5f * u;
                    c.drawLine(x0, y0, cx + (float) Math.cos(a) * 9f * u, cy + (float) Math.sin(a) * 9f * u, stroke);
                }
                break;
            case ACTION:
                c.drawCircle(cx + 2 * u, cy - 8 * u, 2 * u, fill);
                path.moveTo(cx - 6 * u, cy - 1 * u);
                path.lineTo(cx - 1 * u, cy - 4 * u);
                path.lineTo(cx + 4 * u, cy - 2 * u);
                path.lineTo(cx + 7 * u, cy + 1 * u);
                path.moveTo(cx - 1 * u, cy - 4 * u);
                path.lineTo(cx - 2 * u, cy + 3 * u);
                path.lineTo(cx + 3 * u, cy + 6 * u);
                path.lineTo(cx + 2 * u, cy + 10 * u);
                path.moveTo(cx - 2 * u, cy + 3 * u);
                path.lineTo(cx - 6 * u, cy + 9 * u);
                c.drawPath(path, stroke);
                break;
            case GRID:
                rect.set(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u);
                c.drawRoundRect(rect, 2 * u, 2 * u, stroke);
                c.drawLine(cx - 2.7f * u, cy - 8 * u, cx - 2.7f * u, cy + 8 * u, stroke);
                c.drawLine(cx + 2.7f * u, cy - 8 * u, cx + 2.7f * u, cy + 8 * u, stroke);
                c.drawLine(cx - 8 * u, cy - 2.7f * u, cx + 8 * u, cy - 2.7f * u, stroke);
                c.drawLine(cx - 8 * u, cy + 2.7f * u, cx + 8 * u, cy + 2.7f * u, stroke);
                break;
            case SIX_DOTS:
                // Botón de 6 círculos (3×2) característico de iOS 27 para abrir y personalizar controles
                float dx = 5.0f * u;
                float dy = 3.6f * u;
                float dotR = 1.85f * u;
                for (int col = -1; col <= 1; col++) {
                    c.drawCircle(cx + col * dx, cy - dy, dotR, fill);
                    c.drawCircle(cx + col * dx, cy + dy, dotR, fill);
                }
                break;
            default:
                text.setTextSize(label.length() > 2 ? 8.5f * u : 11.5f * u);
                Paint.FontMetrics fm = text.getFontMetrics();
                c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    private void bolt(float cx, float cy, float u) {
        path.moveTo(cx + 1.5f * u, cy - 10 * u);
        path.lineTo(cx - 6 * u, cy + 1.5f * u);
        path.lineTo(cx - 0.5f * u, cy + 1.5f * u);
        path.lineTo(cx - 1.5f * u, cy + 10 * u);
        path.lineTo(cx + 6 * u, cy - 1.5f * u);
        path.lineTo(cx + 0.5f * u, cy - 1.5f * u);
        path.close();
    }

    private void arrow(Canvas c, float x, float y, float u, int dir) {
        c.drawLine(x, y, x - 3 * u * dir, y - 1.2f * u * dir, stroke);
        c.drawLine(x, y, x + 0.8f * u * dir, y - 3.2f * u * dir, stroke);
    }
}
