package com.signature.cinematic.control;

/**
 * Exposición para video.
 *
 * AUTO: AE del firmware (rápido) con protección de altas luces vía compensación.
 * CINEMATIC: si el HAL permite control manual del sensor, este controlador fija tiempo e ISO
 * con transiciones lentas, histéresis contra el bombeo y obturación anti-parpadeo.
 * Si no hay control manual, se suaviza la exposición del firmware con ganancia digital acotada.
 * LOCKED: congela la exposición actual.
 */
public final class ExposureController {
    public enum Mode { AUTO, CINEMATIC, LOCKED }

    public static final class Limits {
        public long minExposureNs = 100_000L;
        public long maxExposureNs = 33_333_333L;
        public int minIso = 100;
        public int maxIso = 3200;
        public boolean manualSensor;
        public float fps = 30f;
        public int mainsHz = 50;
        /** Obturador preferido como fracción de la duración de cuadro (0.5 = 180°). */
        public float shutterFraction = 0.5f;
        /** ISO máximo del perfil; por encima se alarga el obturador y luego se acepta subexposición. */
        public int isoCap = 6400;
    }

    /** Lo que hay que pedirle a la cámara y al shader para el próximo fotograma. */
    public static final class Output {
        public boolean manual;
        public boolean userManualPro;
        public long exposureNs;
        public int iso;
        public float aeCompensationEv;
        public boolean aeLock;
    }

    static final float TARGET_LUMA = 0.18f;
    static final float CINE_START_EV = 0.35f;
    static final float CINE_STOP_EV = 0.10f;
    static final float CINE_GAIN_PER_S = 1.2f;
    static final float CINE_MAX_EV_PER_S = 0.75f;
    static final float FAST_MAX_EV_PER_S = 4f;
    static final int ARTIFICIAL_LIGHT_ISO = 400;

    private final Limits limits;
    private Mode mode = Mode.AUTO;
    private float evBias;

    private double logE;
    private boolean seeded;
    private boolean moving;
    private long lastStatsNs;
    private float lastAperture;

    private double fwLogE;
    private double fwSmoothLogE;
    private boolean hasFw;

    private float highlightBias;
    private float targetLog = (float) Math.log(TARGET_LUMA);
    private boolean captureTarget;
    private int fastConvergenceFrames;

    public ExposureController(Limits limits) {
        this.limits = limits;
    }

    /**
     * Activa convergencia ultrarrápida (<150 ms) al tocar el visor (Tap-to-Focus / Spot Metering),
     * rompiendo la histéresis lenta para recuperar altas luces quemadas de inmediato.
     */
    public synchronized void triggerFastConvergence() {
        fastConvergenceFrames = 18;
        moving = true;
        captureTarget = false;
        targetLog = (float) Math.log(TARGET_LUMA);
    }

    public synchronized void setMode(Mode m) {
        if (m == Mode.CINEMATIC && mode != Mode.CINEMATIC) {
            if (hasFw) {
                logE = fwLogE;
                seeded = true;
            }
            moving = false;
            captureTarget = true;
        }
        if (m == Mode.AUTO) {
            seeded = false;
        }
        mode = m;
    }

    public synchronized Mode mode() {
        return mode;
    }

    public synchronized void setEvBias(float ev) {
        evBias = Math.max(-2f, Math.min(2f, ev));
    }

    public synchronized float evBias() {
        return evBias;
    }

    private boolean manualActive() {
        return limits.manualSensor && seeded && mode != Mode.AUTO;
    }

    /** Notifica conmutación de sensor físico (.5x <-> 1x <-> 3x <-> Frontal) para re-anclar el suavizador sin destellos de ganancia. */
    public synchronized void onSensorSwitched() {
        hasFw = false;
    }

    /** Valores reales que aplicó la cámara al fotograma (de CaptureResult). */
    public synchronized void onCaptureResult(long exposureNs, int iso, float aperture) {
        if (exposureNs <= 0 || iso <= 0) {
            return;
        }
        double le = log2((double) exposureNs * iso);
        boolean apertureSwitched = lastAperture > 0f && aperture > 0f && Math.abs(aperture - lastAperture) > 0.01f;
        if (manualActive()) {
            if (apertureSwitched) {
                // Cambio de lente en la cámara lógica: mantener el brillo compensando el diafragma.
                logE += 2.0 * log2(aperture / lastAperture);
            }
        } else {
            fwLogE = le;
            if (!hasFw || apertureSwitched) {
                // Al cambiar de lente físico (distinto f-number), el ISP ajusta exposureNs*iso inmediatamente
                // para compensar la apertura física; sincronizamos fwSmoothLogE para evitar saltos de ganancia digital.
                fwSmoothLogE = le;
                hasFw = true;
            }
            if (!seeded) {
                logE = le;
                seeded = mode == Mode.CINEMATIC;
            }
        }
        if (aperture > 0f) {
            lastAperture = aperture;
        }
    }

    public synchronized void onStats(FrameStats s, long timestampNs) {
        float dt = lastStatsNs == 0 ? 0.033f : Math.min(0.1f, (timestampNs - lastStatsNs) / 1e9f);
        lastStatsNs = timestampNs;
        if (dt <= 0f) {
            return;
        }
        if (captureTarget) {
            // Mantiene la intención de brillo del AE del firmware en el momento de pasar a manual.
            targetLog = clamp(s.logMeanLuma, (float) Math.log(0.09), (float) Math.log(0.25));
            captureTarget = false;
        }
        float error = meteringError(s, evBias, targetLog);

        // Highlight-Weighted Metering moderado: solo atenúa cuando hay recorte real severo (>6%),
        // evitando oscurecer la escena general en interiores o con luces puntuales normales.
        float hlTarget = s.clipFraction > 0.06f ? -Math.min(0.25f, (s.clipFraction - 0.06f) * 3.5f) : 0f;
        float hlDelta = hlTarget - highlightBias;
        if (hlDelta < 0f) {
            highlightBias += Math.max(hlDelta, -4.5f * dt);
        } else {
            highlightBias += Math.min(hlDelta, 2.5f * dt);
        }

        boolean urgentHighlightRecovery = s.clipFraction > 0.015f || s.highlightPressure > 0.03f || fastConvergenceFrames > 0;
        if (fastConvergenceFrames > 0) {
            fastConvergenceFrames--;
        }

        if (mode == Mode.CINEMATIC) {
            if (manualActive()) {
                if (urgentHighlightRecovery && error < -0.15f) {
                    // Recuperación agresiva de altas luces quemadas (acorta Shutter Speed o baja ISO en <150 ms)
                    logE += clamp(error * 4.5f * dt, -6.5f * dt, 3.0f * dt);
                    moving = true;
                } else if (fastConvergenceFrames > 0) {
                    logE += clamp(error * 3.8f * dt, -5.5f * dt, 4.0f * dt);
                } else {
                    logE += cinematicStep(error, dt);
                }
            } else if (hasFw) {
                float rate = urgentHighlightRecovery ? FAST_MAX_EV_PER_S * 1.8f : CINE_MAX_EV_PER_S;
                float gain = urgentHighlightRecovery ? 4.5f : CINE_GAIN_PER_S;
                fwSmoothLogE += clamp((float) (fwLogE - fwSmoothLogE) * gain * dt, -rate * dt, rate * dt);
            }
        } else if (mode == Mode.AUTO && hasFw) {
            float rate = urgentHighlightRecovery ? FAST_MAX_EV_PER_S * 2.2f : FAST_MAX_EV_PER_S;
            fwSmoothLogE += clamp((float) (fwLogE - fwSmoothLogE), -rate * dt, rate * dt);
        }
    }

    /** Diferencia en EV entre la exposición deseada y la medida. Positivo = falta luz. */
    static float meteringError(FrameStats s, float evBias, float targetLog) {
        double target = targetLog + evBias * Math.log(2);
        float e = (float) ((target - s.logMeanLuma) / Math.log(2));
        if (s.clipFraction > 0.01f) {
            e -= Math.min(1.5f, (s.clipFraction - 0.01f) * 15f);
        }
        if (s.highlightPressure > 0.015f) {
            e -= Math.min(1.5f, (s.highlightPressure - 0.015f) * 12f);
        }
        if (s.p98 > 0.94f) {
            e -= (s.p98 - 0.94f) * 6.0f;
        }
        return clamp(e, -4f, 4f);
    }

    private float cinematicStep(float error, float dt) {
        float abs = Math.abs(error);
        if (!moving && abs < CINE_START_EV) {
            return 0f;
        }
        moving = abs > CINE_STOP_EV;
        if (!moving) {
            return 0f;
        }
        float maxRate = (error < 0) ? CINE_MAX_EV_PER_S : 0.65f;
        return clamp(error * CINE_GAIN_PER_S * dt, -maxRate * dt, maxRate * dt);
    }

    private long manualExposureNs = 0L;
    private int manualIso = 0;

    public synchronized void setManualExposure(long exposureNs, int iso) {
        this.manualExposureNs = Math.max(0, exposureNs);
        this.manualIso = Math.max(0, iso);
    }

    public synchronized long manualExposureNs() {
        return manualExposureNs;
    }

    public synchronized int manualIso() {
        return manualIso;
    }

    public synchronized Output output() {
        Output o = new Output();
        o.aeLock = mode == Mode.LOCKED;
        o.aeCompensationEv = evBias + (mode != Mode.LOCKED ? highlightBias : 0f);
        if (limits.manualSensor && (manualExposureNs > 0 || manualIso > 0)) {
            o.manual = true;
            o.userManualPro = true;
            if (manualExposureNs > 0 && manualIso > 0) {
                o.exposureNs = manualExposureNs;
                o.iso = manualIso;
            } else if (manualExposureNs > 0) {
                o.exposureNs = manualExposureNs;
                double e = Math.pow(2.0, hasFw ? (fwLogE + evBias) : (logE + evBias));
                int targetIso = (int) Math.round(e / manualExposureNs);
                o.iso = Math.max(limits.minIso, Math.min(limits.maxIso, targetIso));
            } else {
                o.iso = manualIso;
                double e = Math.pow(2.0, hasFw ? (fwLogE + evBias) : (logE + evBias));
                long targetT = Math.round(e / manualIso);
                o.exposureNs = Math.max(limits.minExposureNs, Math.min(limits.maxExposureNs, targetT));
            }
            return o;
        }
        if (manualActive()) {
            long[] plan = plan(logE, limits);
            o.manual = true;
            o.userManualPro = false;
            o.exposureNs = plan[0];
            o.iso = (int) plan[1];
        }
        return o;
    }

    /**
     * Ganancia lineal para el shader. Compensa los micro-saltos discretos del AE del firmware
     * sin introducir destellos al conmutar de lente física.
     */
    public synchronized float digitalGain(long exposureNs, int iso) {
        if (manualActive() || !hasFw || exposureNs <= 0 || iso <= 0) {
            return 1f;
        }
        double diff = fwSmoothLogE - log2((double) exposureNs * iso);
        return (float) Math.pow(2.0, clamp((float) diff, -0.05f, 0.08f));
    }

    synchronized double currentLogE() {
        return logE;
    }

    synchronized float currentTargetLog() {
        return targetLog;
    }

    /** Reparte la exposición total entre tiempo e ISO. Devuelve {exposureNs, iso}. */
    static long[] plan(double logE, Limits l) {
        double e = Math.pow(2.0, logE);
        long frameNs = (long) (1e9 / l.fps);
        long tMax = Math.min(l.maxExposureNs, frameNs);
        int isoMax = Math.max(l.minIso, Math.min(l.maxIso, l.isoCap));
        double t = Math.min(frameNs * (double) l.shutterFraction, tMax);
        double iso = e / t;
        if (iso < l.minIso) {
            iso = l.minIso;
            t = Math.max(l.minExposureNs, e / l.minIso);
        } else if (iso > ARTIFICIAL_LIGHT_ISO) {
            long step = 1_000_000_000L / (2L * l.mainsHz);
            if (tMax >= step) {
                t = (tMax / step) * step;
                iso = e / t;
            }
            if (iso > isoMax) {
                iso = isoMax;
                t = Math.min(tMax, e / isoMax);
            }
        }
        iso = Math.max(l.minIso, Math.min(isoMax, iso));
        t = Math.max(l.minExposureNs, Math.min(tMax, t));
        return new long[] {Math.round(t), Math.round(iso)};
    }

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
