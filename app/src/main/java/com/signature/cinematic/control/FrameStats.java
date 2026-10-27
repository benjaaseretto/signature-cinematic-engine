package com.signature.cinematic.control;

/** Medición de escena calculada sobre una versión reducida del fotograma. */
public final class FrameStats {
    /** Media logarítmica de luminancia lineal, ponderada al centro o al punto tocado. */
    public final float logMeanLuma;
    /** Fracción de píxeles con algún canal recortado. */
    public final float clipFraction;
    /** Percentil 98 de luminancia lineal. */
    public final float p98;
    /** Presión de altas luces (combinación de recorte y pre-recorte 90-98%). */
    public final float highlightPressure;
    /** Histograma de 32 barras en dominio gamma (0..1, normalizado al pico) para el HUD Pro. */
    public final float[] bins;

    public FrameStats(float logMeanLuma, float clipFraction, float p98) {
        this(logMeanLuma, clipFraction, p98, clipFraction, new float[32]);
    }

    public FrameStats(float logMeanLuma, float clipFraction, float p98, float[] bins) {
        this(logMeanLuma, clipFraction, p98, clipFraction, bins);
    }

    public FrameStats(float logMeanLuma, float clipFraction, float p98, float highlightPressure, float[] bins) {
        this.logMeanLuma = logMeanLuma;
        this.clipFraction = clipFraction;
        this.p98 = p98;
        this.highlightPressure = highlightPressure;
        this.bins = bins;
    }

    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /**
     * @param rgba píxeles RGBA8 (como los devuelve glReadPixels)
     * @param focusX centro de medición normalizado 0..1 en el fotograma
     */
    public static FrameStats compute(byte[] rgba, int width, int height, float focusX, float focusY) {
        int[] hist = new int[64];
        int[] gammaHist = new int[32];
        double sumLog = 0;
        double sumW = 0;
        int clipped = 0;
        int nearClip = 0;
        int spotClipped = 0;
        int spotNearClip = 0;
        int spotPixels = 0;
        int n = width * height;
        for (int y = 0; y < height; y++) {
            float dy = (y + 0.5f) / height - focusY;
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int r = rgba[i] & 0xff;
                int g = rgba[i + 1] & 0xff;
                int b = rgba[i + 2] & 0xff;
                float dx = (x + 0.5f) / width - focusX;
                float dist2 = dx * dx + dy * dy;
                boolean inSpot = dist2 <= 0.045f;
                if (inSpot) {
                    spotPixels++;
                }
                if (r >= 250 || g >= 250 || b >= 250) {
                    clipped++;
                    if (inSpot) {
                        spotClipped++;
                    }
                } else if (r >= 225 || g >= 225 || b >= 225) {
                    nearClip++;
                    if (inSpot) {
                        spotNearClip++;
                    }
                }
                int yGam = (54 * r + 183 * g + 19 * b) >> 8;
                gammaHist[Math.min(31, yGam >> 3)]++;
                float luma = 0.2126f * SRGB_TO_LINEAR[r] + 0.7152f * SRGB_TO_LINEAR[g] + 0.0722f * SRGB_TO_LINEAR[b];
                float spotWeight = 0.4f + 2.4f * (float) Math.exp(-dist2 / 0.025f);
                float hlWeight = luma > 0.70f ? (1.0f + 3.2f * (luma - 0.70f)) : 1.0f;
                float w = spotWeight * hlWeight;
                sumLog += w * Math.log(Math.max(luma, 1e-4f));
                sumW += w;
                hist[Math.min(63, (int) (luma * 64f))]++;
            }
        }
        int target = (int) (n * 0.98f);
        int acc = 0;
        int bin = 63;
        for (int k = 0; k < 64; k++) {
            acc += hist[k];
            if (acc >= target) {
                bin = k;
                break;
            }
        }
        int maxBin = 1;
        for (int v : gammaHist) {
            if (v > maxBin) {
                maxBin = v;
            }
        }
        float[] normBins = new float[32];
        for (int k = 0; k < 32; k++) {
            normBins[k] = gammaHist[k] / (float) maxBin;
        }
        float clipFrac = clipped / (float) Math.max(n, 1);
        float nearClipFrac = nearClip / (float) Math.max(n, 1);
        float globalPressure = clipFrac * 2.0f + nearClipFrac * 0.75f;
        float spotClipFrac = spotClipped / (float) Math.max(spotPixels, 1);
        float spotNearFrac = spotNearClip / (float) Math.max(spotPixels, 1);
        float spotPressure = spotClipFrac * 2.6f + spotNearFrac * 1.1f;
        float hlPressure = Math.max(globalPressure, spotPressure);
        return new FrameStats((float) (sumLog / Math.max(sumW, 1e-9)), clipFrac,
                (bin + 1) / 64f, hlPressure, normBins);
    }
}
