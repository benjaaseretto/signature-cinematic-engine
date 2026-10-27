package com.signature.cinematic.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Surface;

import com.signature.cinematic.cinema.CinematicProcessor;
import com.signature.cinematic.color.ChartCapture;
import com.signature.cinematic.color.ChartSampler;
import com.signature.cinematic.color.ColorChecker;
import com.signature.cinematic.color.ColorCalibrationService;
import com.signature.cinematic.color.ColorPipeline;
import com.signature.cinematic.color.ColorProfile;
import com.signature.cinematic.color.Illuminant;
import com.signature.cinematic.color.IspTonemap;
import com.signature.cinematic.control.ExposureController;
import com.signature.cinematic.control.FrameStats;
import com.signature.cinematic.diag.ReportWriter;
import com.signature.cinematic.gl.GlRenderer;
import com.signature.cinematic.metrics.MetricsLogger;
import com.signature.cinematic.record.PhotoSaver;
import com.signature.cinematic.record.Recorder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Une cámara, GPU, controladores de exposición/color, codificador y métricas. */
public final class VideoEngine implements GlRenderer.FrameHook, CameraController.Listener {
    /** Callbacks hacia la interfaz; siempre en el hilo principal. */
    public interface Ui {
        void onMetrics(String text, float audioDb);

        void onRecordingSaved(Uri video, boolean ok);

        void onError(String message);

        default void onPhotoSaved(Uri photo, Bitmap thumbnail) {
        }

        default void onPhotoFailed(String message) {
        }

        default void onLiveHud(int iso, long exposureNs, float aperture, float zoom, String physicalId,
                float audioDb, float[] histogram, float clipFraction) {
        }
    }

    private static final int WARMUP_FRAMES = 30;
    /** El bloqueo AE/AWB llega al sensor con varios cuadros de retraso. */
    private static final int LOCK_SETTLE_FRAMES = 10;

    private final Context context;
    private final CameraManager manager;
    private final Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraController camera;
    private final GlRenderer gl;
    private final FrameMeta.Ring ring = new FrameMeta.Ring(32);
    private final FrameMeta frameMeta = new FrameMeta();
    private final FrameMeta lastMeta = new FrameMeta();
    private final MetricsLogger metrics;
    private final ColorCalibrationService calibration;
    private final ColorPipeline pipeline;
    private final CinematicProcessor cinematic;

    public final CameraCaps back;
    public final CameraCaps front;

    private CameraCaps caps;
    private VideoMode mode;
    private CaptureProfile captureProfile = CaptureProfile.A_NATURAL;
    private volatile ExposureController exposure;
    private volatile ExposureController.Mode desiredMode = ExposureController.Mode.CINEMATIC;
    private volatile Recorder recorder;
    private volatile Recorder warmRecorder;
    private volatile boolean locked;
    private long framesSinceStart;
    private long lastHudNs;
    private volatile float[] lastHistogram = new float[32];
    private volatile float lastClip;
    private volatile float tonePad;
    private volatile float colorPad;
    private volatile float softSkin;
    private volatile float glow;
    private volatile float grain;
    private String lastPhysicalId;
    private boolean thermalLimited;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private CameraController.Still still;

    public VideoEngine(Context context, Ui ui) throws CameraAccessException {
        this.context = context.getApplicationContext();
        this.ui = ui;
        manager = context.getSystemService(CameraManager.class);
        metrics = new MetricsLogger(context);
        calibration = new ColorCalibrationService(new File(context.getFilesDir(), "calibration"),
                ColorChecker.CLASSIC_2005_D50);
        pipeline = new ColorPipeline(calibration);
        cinematic = new CinematicProcessor(this.context);
        camera = new CameraController(manager, this);
        gl = new GlRenderer(this);
        back = pick(CameraCharacteristics.LENS_FACING_BACK);
        front = pick(CameraCharacteristics.LENS_FACING_FRONT);
    }

    /** Cámara de cada lado priorizando REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA y mayor rango de zoom. */
    private CameraCaps pick(int facing) throws CameraAccessException {
        CameraCaps best = null;
        for (String id : manager.getCameraIdList()) {
            Integer f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f == null || f != facing) {
                continue;
            }
            CameraCaps c = CameraCaps.read(manager, id);
            if (c.modes.isEmpty()) {
                continue;
            }
            float span = c.zoomRange.getUpper() / c.zoomRange.getLower();
            if (best == null
                    || (c.logicalMultiCamera && !best.logicalMultiCamera)
                    || (c.logicalMultiCamera == best.logicalMultiCamera
                            && span > best.zoomRange.getUpper() / best.zoomRange.getLower())) {
                best = c;
            }
        }
        return best;
    }

    public CameraCaps caps() {
        return caps;
    }

    public VideoMode mode() {
        return mode;
    }

    public CaptureProfile captureProfile() {
        return captureProfile;
    }

    public ColorCalibrationService calibration() {
        return calibration;
    }

    /** Abre con el modo dado conservando los ajustes del perfil de captura actual. */
    public void open(CameraCaps c, VideoMode m) {
        open(c, m, captureProfile);
    }

    public void open(CameraCaps c, VideoMode m, CaptureProfile p) {
        open(c, m, p, null);
    }

    /**
     * Foto: vista previa 4:3 + JPEG a resolución completa (+ RAW si se pide y existe).
     * Retrato: vista previa 4:3 grande con el desenfoque de Cine; la foto es ese cuadro.
     * @return false si la cámara no tiene un stream 4:3 utilizable
     */
    public boolean openPhoto(CameraCaps c, boolean portrait, boolean raw) {
        VideoMode m = portrait ? c.portraitPreview : c.photoPreview;
        if (m == null || (!portrait && c.jpegSize == null)) {
            return false;
        }
        open(c, m, CaptureProfile.A_NATURAL,
                portrait ? null : new CameraController.Still(c.jpegSize, raw ? c.rawSize : null));
        return true;
    }

    public boolean isPhotoSession() {
        return still != null;
    }

    private void open(CameraCaps c, VideoMode m, CaptureProfile p, CameraController.Still s) {
        still = s;
        caps = c;
        mode = m;
        captureProfile = p;
        ExposureController.Limits l = new ExposureController.Limits();
        l.manualSensor = c.manualSensor && c.readSensorSettings;
        l.minExposureNs = c.minExposureNs;
        l.maxExposureNs = c.maxExposureNs;
        l.minIso = c.minIso;
        l.maxIso = c.maxIso;
        l.fps = m.fps;
        l.shutterFraction = p.getShutterFraction();
        l.isoCap = p.getIsoCap();
        exposure = new ExposureController(l);
        exposure.setMode(ExposureController.Mode.AUTO);
        exposure.setEvBias(0.0f);
        pipeline.getWb().setEnabled(true);
        pipeline.getWb().resetForNewSession();
        IspTonemap tm = p.getTonemap() == IspTonemap.SRGB && c.tonemapPresetSrgb ? IspTonemap.SRGB : IspTonemap.ISP_DEFAULT;
        pipeline.setTonemap(tm);
        camera.setProcessing(p.getNoiseReduction(), p.getEdge(), tm);
        framesSinceStart = 0;
        metrics.reset(m.fps, false);
        if (cinematic.getEnabled() && !cinematicAllowed(m)) {
            setCinematic(false);
            ui.onError("Modo Cine no disponible en " + m.label() + ": se desactivó");
        }
        gl.setTargetFps(m.fps);
        Surface target = gl.start(m.width, m.height, c.sensorOrientation, c.front);
        camera.open(c, m, target, s);
        scheduleWarmRecorder(c, m, s == null);
    }

    /**
     * Conmuta en caliente entre cámara Frontal y Trasera durante una grabación activa
     * manteniendo vivo el MediaCodec/Muxer/AudioCapture y el sintetizador de cuadros (Zero-Drop Frame Pacer).
     */
    public void switchCameraWhileRecording(CameraCaps newCaps) {
        if (newCaps == null || mode == null || !isRecording()) {
            return;
        }
        VideoMode targetMode = mode;
        for (VideoMode vm : newCaps.modes) {
            if (vm.width == mode.width && vm.height == mode.height && vm.fps == mode.fps) {
                targetMode = vm;
                break;
            }
        }
        caps = newCaps;
        ExposureController.Limits l = new ExposureController.Limits();
        l.manualSensor = newCaps.manualSensor && newCaps.readSensorSettings;
        l.minExposureNs = newCaps.minExposureNs;
        l.maxExposureNs = newCaps.maxExposureNs;
        l.minIso = newCaps.minIso;
        l.maxIso = newCaps.maxIso;
        l.fps = mode.fps;
        l.shutterFraction = captureProfile.getShutterFraction();
        l.isoCap = captureProfile.getIsoCap();
        exposure = new ExposureController(l);
        exposure.setMode(desiredMode);
        exposure.setEvBias(0.0f);
        pipeline.getWb().resetForNewSession();
        IspTonemap tm = captureProfile.getTonemap() == IspTonemap.SRGB && newCaps.tonemapPresetSrgb
                ? IspTonemap.SRGB : IspTonemap.ISP_DEFAULT;
        pipeline.setTonemap(tm);
        camera.setProcessing(captureProfile.getNoiseReduction(), captureProfile.getEdge(), tm);
        gl.setTargetFps(mode.fps);
        Surface target = gl.start(targetMode.width, targetMode.height, newCaps.sensorOrientation, newCaps.front);
        camera.open(newCaps, targetMode, target, null);
    }

    private void scheduleWarmRecorder(CameraCaps c, VideoMode m, boolean isVideoSession) {
        io.execute(() -> {
            Recorder prev = warmRecorder;
            warmRecorder = null;
            if (prev != null) {
                prev.releaseWarmUp();
            }
            if (!isVideoSession || c == null || m == null) {
                return;
            }
            try {
                Recorder r = new Recorder(context, m, c.realtimeTimestamps, c.front, 1);
                r.warmUp();
                warmRecorder = r;
            } catch (IOException | RuntimeException ignored) {
            }
        });
    }

    /** Giro horario que hay que aplicar a la JPEG del sensor para verla derecha. */
    public int jpegOrientation(int deviceOrientation) {
        if (caps == null) {
            return 0;
        }
        return caps.front ? (caps.sensorOrientation + deviceOrientation) % 360
                : (caps.sensorOrientation - deviceOrientation + 360) % 360;
    }

    /**
     * Foto con el stream JPEG; el estilo actual y la proporción se aplican al guardar.
     * @param ratio lado largo / lado corto (4/3, 16/9 o 1)
     */
    public void takePhoto(CameraController.Flash flash, int deviceOrientation, float ratio) {
        ColorProfile style = profile();
        boolean styled = style != ColorProfile.NATURAL;
        float tp = tonePad;
        float cp = colorPad;
        float ss = softSkin;
        float glw = glow;
        float grn = grain;
        boolean isFront = caps != null && caps.front;
        camera.takePicture(flash, jpegOrientation(deviceOrientation), new CameraController.PictureCallback() {
            @Override
            public void onPicture(byte[] jpeg, byte[] dng) {
                io.execute(() -> {
                    try {
                        byte[] out = PhotoSaver.process(context, jpeg, style, ratio, styled, tp, cp, ss, glw, grn, isFront);
                        Uri uri = PhotoSaver.saveJpeg(context, out, "");
                        if (dng != null) {
                            PhotoSaver.saveDng(context, dng);
                        }
                        Bitmap thumb = PhotoSaver.thumbnail(out, 256);
                        main.post(() -> ui.onPhotoSaved(uri, thumb));
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        main.post(() -> ui.onPhotoFailed("No se pudo guardar la foto: " + e.getMessage()));
                    }
                });
            }

            @Override
            public void onPictureFailed(String reason) {
                main.post(() -> ui.onPhotoFailed(reason));
            }
        });
    }

    /** Retrato o foto durante video: guarda el cuadro graduado actual a máxima resolución de GPU. */
    public void takePortrait(int deviceOrientation, float ratio) {
        String tag = cinematic.getEnabled() ? "Retrato ƒ" + cinematic.getFNumber() : "Frame 4K";
        String software = "Signature Camera · " + tag + " · " + profile().name;
        int exif = CameraController.exifOrientation(deviceOrientation);
        boolean isFront = caps != null && caps.front;
        gl.requestSnapshot(bmp -> io.execute(() -> {
            try {
                byte[] out = PhotoSaver.encodeUpright(context, bmp, ratio, exif, software, false);
                bmp.recycle();
                Uri uri = PhotoSaver.saveJpeg(context, out, cinematic.getEnabled() ? "_RETRATO" : "_LIVE");
                Bitmap thumb = PhotoSaver.thumbnail(out, 256);
                main.post(() -> ui.onPhotoSaved(uri, thumb));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                main.post(() -> ui.onPhotoFailed("No se pudo guardar la captura: " + e.getMessage()));
            }
        }));
    }

    public void setFlash(CameraController.Flash f) {
        camera.setFlash(f);
    }

    public void setTorch(boolean on) {
        camera.setTorch(on);
    }

    public void setNightBoost(boolean on) {
        camera.setNightBoost(on);
    }

    public boolean hasRaw() {
        return camera.hasRaw();
    }

    public static CameraController.Stabilization toCamera(StabilizationLevel s) {
        switch (s) {
            case OFF:
                return CameraController.Stabilization.OFF;
            case ENHANCED:
                return CameraController.Stabilization.ENHANCED;
            default:
                return CameraController.Stabilization.STANDARD;
        }
    }

    public void close() {
        Recorder warm = warmRecorder;
        warmRecorder = null;
        if (warm != null) {
            io.execute(warm::releaseWarmUp);
        }
        camera.close();
    }

    public void release() {
        io.shutdown();
        camera.shutdown();
        gl.setCinematic(null);
        cinematic.release();
        gl.release();
    }

    /** El cuadro graduado del modo Cine llega hasta 4K: en 8K se perdería detalle. */
    public static boolean cinematicAllowed(VideoMode m) {
        return m != null && Math.max(m.width, m.height) <= 3840;
    }

    /** @return false si el modo actual no admite Cine */
    public boolean setCinematic(boolean on) {
        if (on && !cinematicAllowed(mode)) {
            return false;
        }
        cinematic.setEnabled(on);
        gl.setCinematic(on ? cinematic : null);
        return true;
    }

    public boolean isCinematic() {
        return cinematic.getEnabled();
    }

    public void setFNumber(float f) {
        cinematic.setFNumber(f);
    }

    public float fNumber() {
        return cinematic.getFNumber();
    }

    /** Vuelve al foco automático de Cine (persona o centro). */
    public void setCinematicAutoFocus() {
        cinematic.setAutoFocus();
    }

    public void setPreviewSurface(Surface s, int w, int h) {
        gl.setPreviewSurface(s, w, h);
    }

    public void clearPreviewSurface() {
        gl.clearPreviewSync();
    }

    public void setProfile(ColorProfile p) {
        pipeline.setProfile(p);
        gl.setProfile(p);
    }

    public void setStyleParams(float tonePad, float colorPad, float softSkin, float glow, float grain) {
        this.tonePad = tonePad;
        this.colorPad = colorPad;
        this.softSkin = softSkin;
        this.glow = glow;
        this.grain = grain;
        gl.setStyleParams(tonePad, colorPad, softSkin, glow, grain);
    }

    public void setDceStrength(float s) {
        gl.setDceStrength(s);
    }

    public float getDceStrength() {
        return gl.getDceStrength();
    }

    public ColorProfile profile() {
        return pipeline.getProfile();
    }

    public void setExposureMode(ExposureController.Mode m) {
        desiredMode = m;
        pipeline.getWb().setEnabled(still == null);
        ExposureController e = exposure;
        if (e != null && framesSinceStart >= WARMUP_FRAMES && !locked) {
            e.setMode(m);
        }
    }

    public ExposureController.Mode exposureMode() {
        return desiredMode;
    }

    public void setEvBias(float ev) {
        ExposureController e = exposure;
        if (e != null) {
            e.setEvBias(ev);
        }
    }

    public void setManualExposure(long exposureNs, int iso) {
        ExposureController e = exposure;
        if (e != null) {
            e.setManualExposure(exposureNs, iso);
            camera.setExposure(e.output());
        }
    }

    public void setAwbMode(int mode) {
        camera.setAwbMode(mode);
    }

    public void setZoom(float z) {
        camera.setZoom(z);
    }

    public void triggerLensTransition(float strength) {
        gl.triggerLensTransition(strength);
    }

    public void freezeLastFrame() {
        gl.freezeLastFrame();
    }

    public void notifyNewCameraActive() {
        gl.notifyNewCameraActive();
    }

    public void setStabilization(CameraController.Stabilization s) {
        camera.setStabilization(s);
    }

    /** Toque en la vista (0..1, origen arriba a la izquierda, imagen derecha). */
    public void focusAt(float viewX, float viewY) {
        if (caps == null) {
            return;
        }
        gl.setFocusPoint(viewX, viewY);
        gl.setStatsInterval(1);
        ExposureController e = exposure;
        if (e != null) {
            e.triggerFastConvergence();
        }
        if (cinematic.getEnabled()) {
            cinematic.setFocusPoint(viewX, viewY);
        }
        float x = caps.front ? 1f - viewX : viewX;
        float sx;
        float sy;
        switch (caps.sensorOrientation) {
            case 90:
                sx = viewY;
                sy = 1f - x;
                break;
            case 270:
                sx = 1f - viewY;
                sy = x;
                break;
            case 180:
                sx = 1f - x;
                sy = 1f - viewY;
                break;
            default:
                sx = x;
                sy = viewY;
        }
        camera.focusAt(sx, sy);
    }

    /** Bloqueo AE/AF/AWB (pulsación larga). */
    public void setLocked(boolean lock) {
        locked = lock;
        ExposureController e = exposure;
        if (e != null) {
            e.setMode(lock ? ExposureController.Mode.LOCKED : desiredMode);
        }
        camera.setLocks(lock, lock);
        if (!lock) {
            camera.resetFocus();
            gl.setFocusPoint(0.5f, 0.5f);
        }
    }

    public boolean isLocked() {
        return locked;
    }

    /**
     * Mide la carta con la salida del ISP sin la pipeline de color, con exposición y balance bloqueados.
     * El resultado llega en el hilo principal (null si no hay metadatos de cámara).
     */
    public void captureChart(ChartSampler sampler, Illuminant illuminant, String conditions,
            Consumer<ChartCapture> done) {
        setLocked(true);
        double[] gains = new double[3];
        long[] expo = new long[2];
        int[] n = new int[1];
        int[] settle = {LOCK_SETTLE_FRAMES};
        gl.setRawFrameSink((rgba, w, h, ts) -> {
            if (settle[0]-- > 0) {
                return true;
            }
            sampler.addFrame(rgba, w, h);
            synchronized (lastMeta) {
                gains[0] += lastMeta.wbGains[0];
                gains[1] += (lastMeta.wbGains[1] + lastMeta.wbGains[2]) / 2f;
                gains[2] += lastMeta.wbGains[3];
                expo[0] += lastMeta.exposureNs;
                expo[1] += lastMeta.iso;
            }
            n[0]++;
            if (!sampler.getDone()) {
                return true;
            }
            String pid;
            synchronized (lastMeta) {
                pid = lastMeta.physicalId != null ? lastMeta.physicalId : caps.id;
            }
            ChartCapture capture = gains[1] <= 0 ? null : new ChartCapture(pid, illuminant, pipeline.getTonemap(),
                    sampler.patches(), sampler.clipped(), sampler.getNeutralPerFrame(),
                    new double[] {gains[0] / n[0], gains[1] / n[0], gains[2] / n[0]},
                    expo[0] / n[0], (int) (expo[1] / n[0]),
                    conditions + " · " + mode + " · perfil " + captureProfile.getId());
            main.post(() -> {
                setLocked(false);
                done.accept(capture);
            });
            return false;
        });
    }

    public boolean isRecording() {
        return recorder != null;
    }

    public void startRecording(int orientationHint) {
        startRecording(orientationHint, 1);
    }

    /** @param timelapse 1 = tiempo real; n = se guarda 1 de cada n cuadros y el video dura n veces menos */
    public void startRecording(int orientationHint, int timelapse) {
        startRecording(orientationHint, timelapse, 1);
    }

    /** @param slowdown 1 = tiempo real; n = el archivo se reproduce n veces más lento (cámara lenta) */
    public void startRecording(int orientationHint, int timelapse, int slowdown) {
        if (recorder != null || mode == null || still != null) {
            return;
        }
        Recorder r = null;
        if (timelapse == 1 && warmRecorder != null) {
            r = warmRecorder;
            warmRecorder = null;
        } else {
            Recorder warm = warmRecorder;
            warmRecorder = null;
            if (warm != null) {
                io.execute(warm::releaseWarmUp);
            }
            r = new Recorder(context, mode, caps.realtimeTimestamps, caps.front, timelapse);
        }
        r.setSlowdown(slowdown);
        try {
            Surface s = r.prepare(orientationHint);
            gl.setEncoderDecimation(timelapse);
            gl.setEncoderSurface(s, r.width, r.height, !mode.encoderPortrait);
            r.start();
            pipeline.getWb().setEnabled(true);
            metrics.reset(mode.fps, true);
            recorder = r;
        } catch (IOException | RuntimeException e) {
            gl.setEncoderDecimation(1);
            recorder = null;
            ui.onError("No se pudo iniciar la grabación: " + e.getMessage());
        }
    }

    public void stopRecording() {
        Recorder r = recorder;
        if (r == null) {
            return;
        }
        camera.setTorch(false);
        gl.clearEncoderSync();
        gl.setEncoderDecimation(1);
        recorder = null;
        String csv = metrics.csv();
        metrics.reset(mode.fps, false);
        scheduleWarmRecorder(caps, mode, still == null);
        new Thread(() -> {
            Uri uri = r.stop();
            try {
                String name = "metricas_" + System.currentTimeMillis() + ".csv";
                ReportWriter.saveToDownloads(context, name, "text/csv", csv);
            } catch (Exception ignored) {
                // Las métricas son auxiliares: no bloquean el guardado del video.
            }
            main.post(() -> ui.onRecordingSaved(uri, uri != null));
        }, "stop-recording").start();
    }

    // ------------------------------------------------------------ GlRenderer.FrameHook (hilo GL)

    @Override
    public float prepareFrame(long timestampNs, float[] baseOut, float[] lookOut) {
        ExposureController e = exposure;
        if (!ring.find(timestampNs, frameMeta)) {
            pipeline.prepare(null, baseOut, lookOut);
            return 1f;
        }
        pipeline.prepare(frameMeta, baseOut, lookOut);
        return e != null ? e.digitalGain(frameMeta.exposureNs, frameMeta.iso) : 1f;
    }

    @Override
    public void onStats(FrameStats stats, long timestampNs) {
        lastHistogram = stats.bins;
        lastClip = stats.clipFraction;
        ExposureController e = exposure;
        if (e == null) {
            return;
        }
        e.onStats(stats, timestampNs);
        camera.setExposure(e.output());
    }

    @Override
    public void onFrameRendered(long timestampNs, long renderNs, boolean recorded) {
        framesSinceStart++;
        ExposureController e = exposure;
        if (framesSinceStart == WARMUP_FRAMES && e != null && !locked) {
            e.setMode(desiredMode);
        }
        if (timestampNs - lastHudNs >= 120_000_000L) {
            lastHudNs = timestampNs;
            int iso = frameMeta.iso;
            long expNs = frameMeta.exposureNs;
            float ap = frameMeta.aperture > 0f ? frameMeta.aperture : (caps != null ? caps.mainAperture : 1.6f);
            float z = frameMeta.zoom;
            String pid = frameMeta.physicalId;
            Recorder r = recorder;
            float db = r != null ? r.audioLevelDb() : -90f;
            float[] hist = lastHistogram;
            float clip = lastClip;
            main.post(() -> ui.onLiveHud(iso, expNs, ap, z, pid, db, hist, clip));
        }
        float gain = e != null ? e.digitalGain(frameMeta.exposureNs, frameMeta.iso) : 1f;
        if (metrics.onFrame(timestampNs, renderNs, frameMeta.exposureNs, frameMeta.iso, gain,
                frameMeta.physicalId, frameMeta.zoom)) {
            adaptToThermal(metrics.thermalStatus());
            String text = metrics.overlay() + "\n" + captureProfile.getId() + " · "
                    + (pipeline.getCalibratedFrame() ? "color calibrado v" + calibration.getActiveVersion()
                            : "sin calibrar") + " · " + pipeline.getTonemap()
                    + (cinematic.getEnabled() ? "\n" + cinematic.summary() : "");
            Recorder r = recorder;
            float db = r != null ? r.audioLevelDb() : -90f;
            main.post(() -> ui.onMetrics(text, db));
        }
    }

    /** Ante calor se recortan primero los procesos opcionales, nunca el archivo ni el audio. */
    private void adaptToThermal(int status) {
        boolean hot = status >= PowerManager.THERMAL_STATUS_SEVERE;
        boolean cool = status <= PowerManager.THERMAL_STATUS_MODERATE;
        if (hot && !thermalLimited) {
            thermalLimited = true;
            camera.setThermalLimited(true);
            gl.setStatsInterval(4);
            cinematic.setMinIntervalMs(250L);
        } else if (cool && thermalLimited) {
            thermalLimited = false;
            camera.setThermalLimited(false);
            gl.setStatsInterval(2);
            cinematic.setMinIntervalMs(0L);
        }
    }

    @Override
    public long encoderTimeNs(long timestampNs) {
        Recorder r = recorder;
        return r != null ? r.presentationNs(timestampNs) : -1L;
    }

    // ------------------------------------------------------------ CameraController.Listener (hilo cámara)

    @Override
    public void onFrameMeta(FrameMeta meta) {
        ring.put(meta);
        gl.notifyNewCameraActive();
        if (meta.physicalId != null && lastPhysicalId != null && !meta.physicalId.equals(lastPhysicalId)) {
            // El HAL acaba de conmutar de sensor físico (.5x <-> 1x <-> 3x): fusión óptica suave y puente de luminancia
            gl.notifySensorSwitch();
            gl.triggerLensTransition(0.85f);
            ExposureController exp = exposure;
            if (exp != null) {
                exp.onSensorSwitched();
            }
        }
        if (meta.physicalId != null) {
            lastPhysicalId = meta.physicalId;
        }
        synchronized (lastMeta) {
            lastMeta.copyFrom(meta);
        }
        ExposureController e = exposure;
        if (e != null) {
            e.onCaptureResult(meta.exposureNs, meta.iso, meta.aperture);
        }
        pipeline.onFrameMeta(meta);
    }

    @Override
    public void onCameraError(String message) {
        main.post(() -> ui.onError(message));
    }
}
