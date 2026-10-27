package com.signature.cinematic.control;

/** Acceso de prueba a la parte de paquete de ExposureController. */
public final class ExposureControllerAccess {
    private final ExposureController controller;

    public ExposureControllerAccess(boolean manualSensor) {
        ExposureController.Limits l = new ExposureController.Limits();
        l.manualSensor = manualSensor;
        l.fps = 30f;
        controller = new ExposureController(l);
    }

    public static float meteringError(FrameStats s, float evBias, float targetLog) {
        return ExposureController.meteringError(s, evBias, targetLog);
    }

    /** @param isoAt180 ISO necesario con obturador a 180°; negativo = pleno sol */
    public static long[] plan(double isoAt180, float fps) {
        ExposureController.Limits l = new ExposureController.Limits();
        l.fps = fps;
        double e = isoAt180 > 0 ? (1e9 / fps / 2.0) * isoAt180 : 100_000.0 * 100;
        return ExposureController.plan(Math.log(e) / Math.log(2), l);
    }

    /** Exposición total en ns·ISO con límites de perfil. */
    public static long[] planProfile(double exposureIso, float fps, float shutterFraction, int isoCap) {
        ExposureController.Limits l = new ExposureController.Limits();
        l.fps = fps;
        l.shutterFraction = shutterFraction;
        l.isoCap = isoCap;
        return ExposureController.plan(Math.log(exposureIso) / Math.log(2), l);
    }

    public void seed(long exposureNs, int iso) {
        controller.onCaptureResult(exposureNs, iso, 0f);
        controller.setMode(ExposureController.Mode.CINEMATIC);
    }

    public void result(long exposureNs, int iso, float aperture) {
        controller.onCaptureResult(exposureNs, iso, aperture);
    }

    public void stats(float logMeanLuma, long timestampNs) {
        controller.onStats(new FrameStats(logMeanLuma, 0f, 0.5f), timestampNs);
    }

    public double logE() {
        return controller.currentLogE();
    }

    public float target() {
        return controller.currentTargetLog();
    }
}
