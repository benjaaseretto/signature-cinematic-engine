package com.signature.cinematic.control;

import java.util.HashMap;
import java.util.Map;

/**
 * Estabiliza y correlaciona el balance de blancos entre sensores físicos sin apagar el AWB del firmware.
 *
 * Características clave:
 * 1. Correlación Multi-Cámara: mapea las ganancias de cada sensor físico (.5x / 1x / 3x / Frontal)
 *    a un espacio de temperatura de color de escena común, preservando la continuidad cromática del video.
 * 2. Transición Suave entre Sensores: al conmutar de lente física o cámara, interpola suavemente
 *    las ganancias a lo largo de una ventana de hand-off para eliminar saltos y parpadeos.
 * 3. Normalización Conservativa de Luminancia (Anti-Quemado de Blancos): normaliza las contra-ganancias
 *    según los coeficientes de luminancia Rec.709 (0.2126 R + 0.7152 G + 0.0722 B = 1.0) para que
 *    el ajuste de balance afecte únicamente a la cromaticidad, sin inflar la luminancia ni quemar altas luces.
 * 4. Compresión Suave de Rango (Soft-Knee): limita multiplicadores extremos para evitar halos y recortes.
 */
public final class WhiteBalanceSmoother {
    static final double START = 0.030;
    static final double STOP = 0.006;
    static final double TAU_S = 1.1;
    static final double MAX_PER_S = 0.30;
    static final float MAX_CORRECTION = 0.22f;
    static final int TRANSITION_FRAMES = 22;

    // Luminancia relativa Rec.709 para conservación estricta de energía
    private static final float LUMA_R = 0.2126f;
    private static final float LUMA_G = 0.7152f;
    private static final float LUMA_B = 0.0722f;

    // Offsets logarítmicos de cromaticidad por sensor (referencia: LYT-808 Sensor "3" = 0,0)
    // Sensor "2" (13mm Ultra-wide JN1): menor sensibilidad al rojo, mayor transmisión azul
    // Sensor "5" (70mm Tele IMX882): ligero sesgo cálido
    // Sensor "1" (23mm Frontal OV50D/IMX816): calibrado para tonos de piel neutros
    private static final class SensorOffset {
        double offR;
        double offB;
        int frameCount;

        SensorOffset(double r, double b) {
            this.offR = r;
            this.offB = b;
            this.frameCount = 0;
        }
    }

    private final Map<String, SensorOffset> sensorOffsets = new HashMap<>();

    private boolean enabled;
    private boolean has;
    private boolean moving;
    private double sceneSmoothR; // Cromaticidad suavizada de la escena (independiente de sensor)
    private double sceneSmoothB;
    private double curSceneR;
    private double curSceneB;
    private long lastNs;

    private String currentPhysicalId;
    private int transitionFramesRemaining;
    private int sessionWarmupFrames;
    private final float[] prevCorrection = new float[] {1f, 1f, 1f};

    public WhiteBalanceSmoother() {
        initDefaultSensorOffsets();
    }

    /** Llamar al abrir sesión de cámara para converger de inmediato sobre el AWB real del ISP sin retener ganancias frías de arranque. */
    public synchronized void resetForNewSession() {
        sessionWarmupFrames = 10;
        moving = false;
        transitionFramesRemaining = 0;
    }

    private void initDefaultSensorOffsets() {
        // Motorola Signature (vantage_g / canoe):
        sensorOffsets.put("3", new SensorOffset(0.0, 0.0));       // Principal 24mm (Sony LYT-808) - Referencia
        sensorOffsets.put("2", new SensorOffset(0.20, -0.13));    // Ultra Gran Angular 13mm (Samsung JN1)
        sensorOffsets.put("5", new SensorOffset(0.07, 0.05));     // Teleobjetivo 70mm (Sony IMX882)
        sensorOffsets.put("1", new SensorOffset(0.13, -0.04));    // Frontal 23mm (OmniVision/Sony)
    }

    public synchronized void setEnabled(boolean on) {
        enabled = on;
        if (on && has) {
            sceneSmoothR = curSceneR;
            sceneSmoothB = curSceneB;
            moving = false;
            transitionFramesRemaining = 0;
        }
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized void update(float r, float gEven, float gOdd, float b, long timestampNs) {
        update(r, gEven, gOdd, b, null, timestampNs);
    }

    public synchronized void update(float r, float gEven, float gOdd, float b, String physicalId, long timestampNs) {
        float g = (gEven + gOdd) * 0.5f;
        if (r <= 0f || g <= 0f || b <= 0f) {
            return;
        }

        // Obtener o registrar offset de sensor
        SensorOffset off = getOrCreateOffset(physicalId);

        // Detectar conmutación de sensor físico (.5x <-> 1x <-> 3x <-> Frontal)
        if (physicalId != null && currentPhysicalId != null && !physicalId.equals(currentPhysicalId)) {
            // Cambio de cámara o lente: sincronizar inmediatamente el offset del nuevo sensor
            // con la escena actual para evitar saltos cromáticos o de luminancia entre distintos CFAs,
            // e iniciar interpolación S-curve suave desde la corrección previa.
            off.offR = Math.log(r / g) - sceneSmoothR;
            off.offB = Math.log(b / g) - sceneSmoothB;
            off.frameCount = 0;
            transitionFramesRemaining = TRANSITION_FRAMES;
            sessionWarmupFrames = 6;
        }
        if (physicalId != null) {
            currentPhysicalId = physicalId;
        }

        double rawLogR = Math.log(r / g);
        double rawLogB = Math.log(b / g);

        if (sessionWarmupFrames > 0 && has) {
            // Durante los primeros fotogramas tras abrir cámara o cambiar de lente, el AWB del ISP
            // está convergiendo: adaptamos el offset del sensor sin introducir correcciones bruscas.
            off.offR = rawLogR - sceneSmoothR;
            off.offB = rawLogB - sceneSmoothB;
            sessionWarmupFrames--;
        }

        // Cromaticidad referida a la escena común
        curSceneR = rawLogR - off.offR;
        curSceneB = rawLogB - off.offB;

        if (!has) {
            sceneSmoothR = curSceneR;
            sceneSmoothB = curSceneB;
            has = true;
            lastNs = timestampNs;
            return;
        }

        double dt = Math.max(0, Math.min(0.1, (timestampNs - lastNs) / 1e9));
        lastNs = timestampNs;

        double dr = curSceneR - sceneSmoothR;
        double db = curSceneB - sceneSmoothB;
        double dist = Math.hypot(dr, db);

        if (!moving && dist < START) {
            // Pequeñas fluctuaciones: mantener estable
            // Cuando la escena está estable, refinamos sutilmente el offset de este sensor
            off.frameCount++;
            if (off.frameCount > 15) {
                off.offR += (rawLogR - sceneSmoothR - off.offR) * 0.015;
                off.offB += (rawLogB - sceneSmoothB - off.offB) * 0.015;
            }
            return;
        }

        moving = dist > STOP;
        if (!moving) {
            return;
        }

        double alpha = 1 - Math.exp(-dt / TAU_S);
        double maxStep = MAX_PER_S * dt;
        sceneSmoothR += clamp(dr * alpha, maxStep);
        sceneSmoothB += clamp(db * alpha, maxStep);

        if (transitionFramesRemaining > 0) {
            transitionFramesRemaining--;
        }
    }

    public synchronized float[] correctionFor(float r, float gEven, float gOdd, float b) {
        return correctionFor(r, gEven, gOdd, b, null);
    }

    /** Multiplicadores RGB lineales normalizados en luminancia para conservar altas luces sin quemados. */
    public synchronized float[] correctionFor(float r, float gEven, float gOdd, float b, String physicalId) {
        float g = (gEven + gOdd) * 0.5f;
        if (!enabled || !has || r <= 0f || g <= 0f || b <= 0f) {
            return new float[] {1f, 1f, 1f};
        }

        SensorOffset off = getOrCreateOffset(physicalId);
        double targetLogR = sceneSmoothR + off.offR;
        double targetLogB = sceneSmoothB + off.offB;

        double curLogR = Math.log(r / g);
        double curLogB = Math.log(b / g);

        float rawCr = (float) Math.exp(targetLogR - curLogR);
        float rawCb = (float) Math.exp(targetLogB - curLogB);

        // Limitación con compresión suave de hombro para no saturar canales
        float cr = softLimit(rawCr);
        float cb = softLimit(rawCb);
        float cg = 1.0f;

        // Normalización conservativa de luminancia Rec.709:
        // Y_scale = LUMA_R * cr + LUMA_G * cg + LUMA_B * cb
        // Garantiza que el dot product del vector resultante con [LUMA_R, LUMA_G, LUMA_B] sea EXACTAMENTE 1.0f.
        // Esto elimina cualquier aumento de luminosidad en blancos y evita por diseño que se queme la imagen.
        float yLuma = LUMA_R * cr + LUMA_G * cg + LUMA_B * cb;
        float invY = 1.0f / Math.max(1e-4f, yLuma);

        float finalR = cr * invY;
        float finalG = cg * invY;
        float finalB = cb * invY;

        // Si estamos en período de transición entre lentes, mezclar suavemente con el estado previo
        if (transitionFramesRemaining > 0) {
            float progress = 1.0f - (transitionFramesRemaining / (float) TRANSITION_FRAMES);
            // Curva S cúbica hermite
            float blend = progress * progress * (3.0f - 2.0f * progress);
            finalR = prevCorrection[0] * (1.0f - blend) + finalR * blend;
            finalG = prevCorrection[1] * (1.0f - blend) + finalG * blend;
            finalB = prevCorrection[2] * (1.0f - blend) + finalB * blend;
        }

        prevCorrection[0] = finalR;
        prevCorrection[1] = finalG;
        prevCorrection[2] = finalB;

        return new float[] {finalR, finalG, finalB};
    }

    private SensorOffset getOrCreateOffset(String id) {
        if (id == null) {
            SensorOffset def = sensorOffsets.get("default");
            if (def == null) {
                def = new SensorOffset(0.0, 0.0);
                sensorOffsets.put("default", def);
            }
            return def;
        }
        SensorOffset off = sensorOffsets.get(id);
        if (off == null) {
            off = new SensorOffset(0.0, 0.0);
            sensorOffsets.put(id, off);
        }
        return off;
    }

    private static double clamp(double v, double max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static float softLimit(float v) {
        float minVal = 1f - MAX_CORRECTION;
        float maxVal = 1f + MAX_CORRECTION;
        if (v > maxVal) {
            return maxVal + (float) Math.tanh((v - maxVal) * 2.0) * 0.04f;
        } else if (v < minVal) {
            return minVal - (float) Math.tanh((minVal - v) * 2.0) * 0.04f;
        }
        return v;
    }
}
