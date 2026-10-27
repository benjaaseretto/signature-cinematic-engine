package com.signature.cinematic.color;

/**
 * Curva tonal paramétrica en dominio gamma (0..1), monótona e identidad con los valores neutros.
 * Se aplica sobre la luminancia para no desplazar el tono de color.
 */
public final class ToneCurve {
    public static final int LUT_SIZE = 256;

    /** Contraste alrededor del pivote; 1 = sin cambio. */
    public final float contrast;
    public final float pivot;
    /** Desde dónde empieza la compresión de altas luces. */
    public final float knee;
    /** 0 = sin hombro; 1 = pendiente nula en blanco (transición más suave a altas luces). */
    public final float shoulder;
    /** Nivel de salida del negro y del blanco. */
    public final float black;
    public final float white;

    public ToneCurve(float contrast, float pivot, float knee, float shoulder, float black, float white) {
        this.contrast = contrast;
        this.pivot = pivot;
        this.knee = knee;
        this.shoulder = shoulder;
        this.black = black;
        this.white = white;
    }

    public static ToneCurve identity() {
        return new ToneCurve(1f, 0.45f, 0.7f, 0f, 0f, 1f);
    }

    public float apply(float x) {
        x = clamp01(x);
        float y;
        if (x < pivot) {
            y = pivot * (float) Math.pow(x / pivot, contrast);
        } else {
            y = 1f - (1f - pivot) * (float) Math.pow((1f - x) / (1f - pivot), contrast);
        }
        if (shoulder > 0f && y > knee) {
            float t = (y - knee) / (1f - knee);
            // g'(0)=1 (continuidad con la parte lineal) y g'(1)=1-shoulder.
            t = t + shoulder * t * t * (1f - t);
            y = knee + (1f - knee) * t;
        }
        return black + (white - black) * clamp01(y);
    }

    /** LUT de 8 bits para subir como textura. */
    public byte[] toLut8() {
        byte[] lut = new byte[LUT_SIZE];
        for (int i = 0; i < LUT_SIZE; i++) {
            int v = Math.round(apply(i / (float) (LUT_SIZE - 1)) * 255f);
            lut[i] = (byte) Math.max(0, Math.min(255, v));
        }
        return lut;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
