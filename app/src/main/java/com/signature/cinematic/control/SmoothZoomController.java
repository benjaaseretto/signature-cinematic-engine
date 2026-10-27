package com.signature.cinematic.control;

import android.view.Choreographer;
import java.util.function.Consumer;

/**
 * Controlador de zoom continuo con física de resorte críticamente amortiguado / rampa exponencial.
 * - Sincronizado a VSYNC (Choreographer) para actualización de la UI a 60/120 Hz sin tirones.
 * - Rate-limiter desacoplado hacia Camera2 (CONTROL_ZOOM_RATIO) para no saturar la cola de peticiones.
 * - Interpolación en espacio logarítmico óptico (velocidad visual constante a lo largo del rango).
 */
public final class SmoothZoomController implements Choreographer.FrameCallback {
    private final Consumer<Float> uiListener;
    private final Consumer<Float> halListener;

    private float minZoom = 0.5f;
    private float maxZoom = 10.0f;
    private float currentZoom = 1.0f;
    private float targetZoom = 1.0f;
    private float lastHalZoom = 1.0f;

    private long lastFrameNs = 0;
    private long transitionStartNs = 0;
    private long lastHalSubmitNs = 0;
    private boolean running = false;

    /** Constante de amortiguamiento óptico (lambda): servo cinematográfico suave estilo iOS Video a 120 Hz */
    private static final float DAMPING_LAMBDA = 11.5f;
    /** Rampa de aceleración inicial suave (ease-in de 55 ms) para eliminar tirones al iniciar el zoom */
    private static final float EASE_IN_DURATION_S = 0.055f;
    /** Umbral mínimo de cambio para justificar una actualización de CaptureRequest */
    private static final float HAL_DELTA_THRESHOLD = 0.0020f;
    /** Cadencia máxima de emisión sincronizada a 120 fps (~8.33 ms) en pantalla pOLED del Motorola Signature */
    private static final long HAL_INTERVAL_NS = 8_333_333L;

    public SmoothZoomController(Consumer<Float> uiListener, Consumer<Float> halListener) {
        this.uiListener = uiListener;
        this.halListener = halListener;
    }

    public void setLimits(float min, float max) {
        minZoom = Math.max(0.1f, min);
        maxZoom = Math.max(minZoom + 0.1f, max);
        currentZoom = Math.max(minZoom, Math.min(maxZoom, currentZoom));
        targetZoom = Math.max(minZoom, Math.min(maxZoom, targetZoom));
    }

    public void setTargetZoom(float target) {
        float clamped = Math.max(minZoom, Math.min(maxZoom, target));
        if (Math.abs(clamped - targetZoom) > 0.05f && !running) {
            transitionStartNs = 0;
        }
        targetZoom = clamped;
        if (Math.abs(targetZoom - currentZoom) > 0.001f && !running) {
            running = true;
            lastFrameNs = 0;
            transitionStartNs = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void setImmediateZoom(float zoom) {
        currentZoom = Math.max(minZoom, Math.min(maxZoom, zoom));
        targetZoom = currentZoom;
        lastHalZoom = currentZoom;
        if (running) {
            running = false;
            Choreographer.getInstance().removeFrameCallback(this);
        }
        if (uiListener != null) {
            uiListener.accept(currentZoom);
        }
        if (halListener != null) {
            halListener.accept(currentZoom);
        }
    }

    public float getCurrentZoom() {
        return currentZoom;
    }

    public float getTargetZoom() {
        return targetZoom;
    }

    @Override
    public void doFrame(long frameTimeNs) {
        if (!running) {
            return;
        }

        if (lastFrameNs == 0) {
            lastFrameNs = frameTimeNs;
            transitionStartNs = frameTimeNs;
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        float dt = (frameTimeNs - lastFrameNs) * 1e-9f;
        lastFrameNs = frameTimeNs;
        if (dt > 0.05f) {
            dt = 0.05f; // Clampeo ante pausas de GC o frame drops
        }

        float elapsedS = Math.max(0f, (frameTimeNs - transitionStartNs) * 1e-9f);
        float easeIn = Math.min(1.0f, elapsedS / EASE_IN_DURATION_S);
        // Smoothstep ease-in (0.35 -> 1.0) para arranque sedoso de lente
        float smoothRamp = 0.35f + 0.65f * (easeIn * easeIn * (3.0f - 2.0f * easeIn));

        // Amortiguamiento exponencial en espacio logarítmico con rampa S-curve
        double lnCurrent = Math.log(Math.max(0.01f, currentZoom));
        double lnTarget = Math.log(Math.max(0.01f, targetZoom));
        float alpha = 1.0f - (float) Math.exp(-(DAMPING_LAMBDA * smoothRamp) * dt);
        double newLn = lnCurrent + (lnTarget - lnCurrent) * alpha;
        currentZoom = (float) Math.exp(newLn);

        // Si la distancia al target es despreciable, se ancla y se detiene el bucle
        if (Math.abs(currentZoom - targetZoom) < 0.0018f) {
            currentZoom = targetZoom;
            running = false;
        }

        // 1. Notificar a la UI en cada frame de pantalla (60/120 Hz)
        if (uiListener != null) {
            uiListener.accept(currentZoom);
        }

        // 2. Notificar al HAL de cámara respetando el rate-limiter de hardware
        boolean timeElapsed = (frameTimeNs - lastHalSubmitNs) >= HAL_INTERVAL_NS;
        boolean deltaSignificant = Math.abs(currentZoom - lastHalZoom) >= HAL_DELTA_THRESHOLD;
        if ((deltaSignificant && timeElapsed) || (!running && currentZoom != lastHalZoom)) {
            lastHalZoom = currentZoom;
            lastHalSubmitNs = frameTimeNs;
            if (halListener != null) {
                halListener.accept(currentZoom);
            }
        }

        if (running) {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }
}
