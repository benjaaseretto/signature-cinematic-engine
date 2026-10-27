package com.signature.cinematic.engine;

import android.annotation.SuppressLint;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.util.Size;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;

import com.signature.cinematic.color.IspTonemap;
import com.signature.cinematic.control.ExposureController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Sesión Camera2 con un único stream hacia la GPU y petición de video construida desde los ajustes. */
public final class CameraController {
    private static final String TAG = "CameraController";

    public enum Stabilization { OFF, STANDARD, ENHANCED }

    public enum Flash { OFF, AUTO, ON }

    public interface Listener {
        void onFrameMeta(FrameMeta meta);

        void onCameraError(String message);
    }

    /** Salidas de foto que se suman al stream de la GPU. raw puede ser null. */
    public static final class Still {
        public final Size jpeg;
        public final Size raw;

        public Still(Size jpeg, Size raw) {
            this.jpeg = jpeg;
            this.raw = raw;
        }
    }

    /** Se llama en el hilo de cámara. dng es null si la sesión no tiene RAW. */
    public interface PictureCallback {
        void onPicture(byte[] jpeg, byte[] dng);

        void onPictureFailed(String reason);
    }

    private static final long PRECAPTURE_TIMEOUT_MS = 1200;
    private static final long PICTURE_TIMEOUT_MS = 6000;

    /** Estado deseado de la petición repetitiva. Solo se modifica desde el hilo de cámara. */
    private static final class Settings {
        Stabilization stabilization = Stabilization.STANDARD;
        float zoom = 1f;
        ExposureController.Output exposure = new ExposureController.Output();
        boolean awbLock;
        boolean focusLock;
        MeteringRectangle focusRegion;
        boolean thermalLimited;
        Processing noiseReduction = Processing.HIGH_QUALITY;
        Processing edge = Processing.FAST;
        IspTonemap tonemap = IspTonemap.ISP_DEFAULT;
        Flash flash = Flash.OFF;
        boolean torch;
        boolean nightBoost;
        int awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    }

    private final CameraManager manager;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("camera");
    private final Handler handler;
    private final Executor executor;
    private final FrameMeta scratch = new FrameMeta();
    private final Settings settings = new Settings();

    private CameraCaps caps;
    private VideoMode mode;
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface target;
    private boolean triggerAf;
    private boolean triggerAePrecapture;
    private boolean cancelAfFirst;
    private Still still;
    private ImageReader jpegReader;
    private ImageReader rawReader;
    private CameraCharacteristics characteristics;
    private volatile int lastAeState = -1;
    private PictureCallback pending;
    private byte[] pendingJpeg;
    private Image pendingRaw;
    private TotalCaptureResult pendingResult;
    private int pendingOrientation;
    private int pictureSeq;

    public CameraController(CameraManager manager, Listener listener) {
        this.manager = manager;
        this.listener = listener;
        thread.start();
        handler = new Handler(thread.getLooper());
        executor = handler::post;
    }

    public Handler handler() {
        return handler;
    }

    public void open(CameraCaps caps, VideoMode mode, Surface target) {
        open(caps, mode, target, null);
    }

    /** @param still salidas de foto; null para video */
    @SuppressLint("MissingPermission")
    public void open(CameraCaps caps, VideoMode mode, Surface target, Still still) {
        handler.post(() -> {
            closeInternal();
            this.caps = caps;
            this.mode = mode;
            this.still = still;
            try {
                characteristics = manager.getCameraCharacteristics(caps.id);
                manager.openCamera(caps.id, executor, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice d) {
                        device = d;
                        createSession(target);
                    }

                    @Override
                    public void onDisconnected(CameraDevice d) {
                        d.close();
                        device = null;
                    }

                    @Override
                    public void onError(CameraDevice d, int error) {
                        d.close();
                        device = null;
                        listener.onCameraError("Error de cámara " + error);
                    }
                });
            } catch (CameraAccessException | SecurityException e) {
                listener.onCameraError(e.toString());
            }
        });
    }

    private void createSession(Surface target) {
        try {
            closeReaders();
            if (still != null) {
                jpegReader = ImageReader.newInstance(still.jpeg.getWidth(), still.jpeg.getHeight(), ImageFormat.JPEG, 5);
                jpegReader.setOnImageAvailableListener(this::onJpeg, handler);
                if (still.raw != null) {
                    rawReader = ImageReader.newInstance(still.raw.getWidth(), still.raw.getHeight(),
                            ImageFormat.RAW_SENSOR, 3);
                    rawReader.setOnImageAvailableListener(this::onRaw, handler);
                }
            }
            SessionConfiguration sc = sessionConfig(target);
            if (!device.isSessionConfigurationSupported(sc) && rawReader != null) {
                // Algunas cámaras lógicas no combinan RAW con los otros streams: se sigue sin RAW.
                rawReader.close();
                rawReader = null;
                sc = sessionConfig(target);
            }
            if (!device.isSessionConfigurationSupported(sc)) {
                listener.onCameraError("Modo " + mode + " no admitido por la cámara " + caps.id);
                return;
            }
            device.createCaptureSession(sc);
            this.target = target;
        } catch (CameraAccessException | IllegalArgumentException | UnsupportedOperationException e) {
            listener.onCameraError(e.toString());
        }
    }

    private SessionConfiguration sessionConfig(Surface target) throws CameraAccessException {
        List<OutputConfiguration> outs = new ArrayList<>();
        OutputConfiguration oc = new OutputConfiguration(target);
        if (caps.videoRecordUseCase && still == null) {
            oc.setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD);
        }
        outs.add(oc);
        if (jpegReader != null) {
            outs.add(new OutputConfiguration(jpegReader.getSurface()));
        }
        if (rawReader != null) {
            outs.add(new OutputConfiguration(rawReader.getSurface()));
        }
        SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs,
                executor, new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        applyRepeating();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession s) {
                        listener.onCameraError("La cámara rechazó " + mode + " con estos ajustes");
                    }
                });
        return sc;
    }

    public boolean hasRaw() {
        return rawReader != null;
    }

    private MeteringRectangle defaultHighlightWeightedMeteringRegion() {
        if (caps == null || caps.activeArray == null) {
            return new MeteringRectangle(0, 0, 1000, 1000, MeteringRectangle.METERING_WEIGHT_MAX);
        }
        android.graphics.Rect a = caps.activeArray;
        int w = (int) (a.width() * 0.60f);
        int h = (int) (a.height() * 0.50f);
        int x = a.left + (a.width() - w) / 2;
        int y = a.top + (int) (a.height() * 0.20f);
        return new MeteringRectangle(Math.max(a.left, x), Math.max(a.top, y),
                Math.min(a.width(), w), Math.min(a.height(), h), MeteringRectangle.METERING_WEIGHT_MAX);
    }

    private CaptureRequest.Builder buildRequest(Surface surface) throws CameraAccessException {
        CaptureRequest.Builder b;
        if (still != null && caps != null && caps.zslCapable) {
            try {
                b = device.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } catch (CameraAccessException | IllegalArgumentException e) {
                b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
        } else {
            b = device.createCaptureRequest(
                    still != null ? CameraDevice.TEMPLATE_PREVIEW : CameraDevice.TEMPLATE_RECORD);
            if (still != null) {
                b.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
            }
        }
        b.addTarget(surface);
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, strictFpsRange(mode.fps));
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO);

        long frameDurationNs = 1_000_000_000L / mode.fps;
        ExposureController.Output e = settings.exposure;
        boolean useManualSensor = e.manual && caps.manualSensor && e.userManualPro;
        if (useManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, Math.min(e.exposureNs, (long) (frameDurationNs * 0.98)));
            b.set(CaptureRequest.SENSOR_SENSITIVITY, e.iso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode());
            int idx = Math.round(e.aeCompensationEv / caps.aeCompStep);
            idx = Math.max(caps.aeCompRange.getLower(), Math.min(caps.aeCompRange.getUpper(), idx));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
            if (caps.aeLock) {
                b.set(CaptureRequest.CONTROL_AE_LOCK, e.aeLock);
            }
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, settings.awbMode);
        if (caps.awbLock) {
            b.set(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock);
        }

        if (settings.focusRegion != null && caps.maxAfRegions > 0) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] {settings.focusRegion});
        }
        if (settings.focusRegion != null && caps.maxAeRegions > 0 && !useManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] {settings.focusRegion});
        }
        if (settings.focusRegion != null && caps.maxAwbRegions > 0 && !settings.awbLock) {
            b.set(CaptureRequest.CONTROL_AWB_REGIONS, new MeteringRectangle[] {settings.focusRegion});
        }
        if (settings.focusLock || settings.focusRegion != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
        } else if (still != null && caps.afContinuousPicture) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        } else if (caps.afContinuousVideo) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        }

        if (caps.ois) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        // En foto el EIS recortaría el encuadre respecto de la JPEG: solo OIS.
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                still != null ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF : stabilizationMode());
        if (settings.torch && caps.flash) {
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
        }

        // Detección de rostros continua para exposición prioritaria (Face-Priority AE estilo iPhone Smart HDR)
        b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE);

        boolean hq = !settings.thermalLimited && mode.fps <= 30;
        if (caps.front) {
            // Estética iPhone 17 Pro para frontal: microcontraste orgánico, sin halos digitales ni sobre-enfoque
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST);
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        } else {
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode(hq));
            b.set(CaptureRequest.EDGE_MODE, edgeMode(hq));
        }
        if (settings.tonemap == IspTonemap.SRGB && caps.tonemapPresetSrgb) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE);
            b.set(CaptureRequest.TONEMAP_PRESET_CURVE, CameraMetadata.TONEMAP_PRESET_CURVE_SRGB);
        } else {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST);
        }
        if (caps.aberrationHighQuality) {
            b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, hq
                    ? CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                    : CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST);
        }
        if (caps.distortionHighQuality) {
            b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, hq
                    ? CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY : CameraMetadata.DISTORTION_CORRECTION_MODE_FAST);
        }
        b.set(CaptureRequest.SHADING_MODE, hq
                ? CameraMetadata.SHADING_MODE_HIGH_QUALITY : CameraMetadata.SHADING_MODE_FAST);
        b.set(CaptureRequest.HOT_PIXEL_MODE, hq
                ? CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY : CameraMetadata.HOT_PIXEL_MODE_FAST);
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, settings.zoom);
        applySafeHalHints(b);
        return b;
    }

    /**
     * Pistas seguras para la transición entre lentes físicas (SAT) que no alteran la topología
     * del grafo CHI en ConfigureStreams (evita fallos del proveedor nativo en sesiones 30p/4:3).
     */
    private void applySafeHalHints(CaptureRequest.Builder b) {
        // Deshabilitar SmartEIS mesh-warping de Motorola para evitar distorsión de esquinas y bordes
        setVendorInt(b, "com.lenovo.moto.control.vstab_smarteis_enable", 0);
        // Forzar modo EIS estándar (afín sin deformación no lineal) en Qualcomm Chi-CDK
        setVendorInt(b, "org.quic.camera.eis.mode", 1);

        // Zero Shutter Lag (ZSL) nativo en HAL de Motorola Signature (vantage / SM8845)
        if (still != null) {
            setVendorInt(b, "com.lenovo.moto.control.capture_zsl_mode", 1);
            setVendorByte(b, "org.codeaurora.qcamera3.sessionParameters.EnableOfflineHALZSL", (byte) 1);
        }

        if (caps != null && !caps.front) {
            // Activar SAT (Sensor Array Transit / Multi-Camera Seamless Switching) en Motorola Signature
            // sin forzar SATFallback (W2UW / T2W), para que 1x use siempre el sensor principal Sony LYT-828 ("3")
            // y 3x use el teleobjetivo periscópico ("5") a máxima resolución óptica nativa.
            setVendorByte(b, "com.lenovo.moto.control.is_sat_mode", (byte) 1);
            setVendorInt(b, "com.lenovo.moto.control.video_sat_4k30fps_support", 1);
            setVendorInt(b, "com.lenovo.moto.control.video_sat_4k60fps_support", 1);
            setVendorInt(b, "com.lenovo.moto.SATFallback.W2UW", 0);
            setVendorInt(b, "com.lenovo.moto.SATFallback.T2W", 0);

            // En sesiones de foto (still != null), habilitar In-Sensor Crop remosaic y Macro dinámico;
            // en sesiones de video (still == null), mantener la topología de lectura del sensor estable
            // para evitar stalls del ISP al cruzar umbrales de zoom durante la grabación.
            if (still != null) {
                boolean inSensorCrop = (settings.zoom >= 1.85f && settings.zoom < 2.5f) || (settings.zoom >= 5.5f);
                setVendorByte(b, "com.lenovo.moto.in_sensor_crop.enable", (byte) (inSensorCrop ? 1 : 0));
                setVendorByte(b, "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom", (byte) (inSensorCrop ? 1 : 0));

                boolean macroUw = settings.zoom <= 0.85f;
                setVendorByte(b, "com.lenovo.moto.clientapp.is_macro_mode", (byte) (macroUw ? 1 : 0));
            }
        }
    }

    private void setVendorInt(CaptureRequest.Builder b, String name, int val) {
        if (caps == null || !caps.vendorRequestKeys.contains(name)) {
            return;
        }
        try {
            b.set(new CaptureRequest.Key<>(name, Integer.class), val);
            return;
        } catch (RuntimeException ignored) {
        }
        try {
            b.set(new CaptureRequest.Key<>(name, int[].class), new int[] {val});
            return;
        } catch (RuntimeException ignored) {
        }
        try {
            b.set(new CaptureRequest.Key<>(name, Byte.class), (byte) val);
        } catch (RuntimeException ignored) {
        }
    }

    private void setVendorByte(CaptureRequest.Builder b, String name, byte val) {
        if (caps == null || !caps.vendorRequestKeys.contains(name)) {
            return;
        }
        try {
            b.set(new CaptureRequest.Key<>(name, Byte.class), val);
            return;
        } catch (RuntimeException ignored) {
        }
        try {
            b.set(new CaptureRequest.Key<>(name, byte[].class), new byte[] {val});
            return;
        } catch (RuntimeException ignored) {
        }
        try {
            b.set(new CaptureRequest.Key<>(name, Integer.class), (int) val);
        } catch (RuntimeException ignored) {
        }
    }

    private void setVendorFloat(CaptureRequest.Builder b, String name, float val) {
        if (caps == null || !caps.vendorRequestKeys.contains(name)) {
            return;
        }
        try {
            b.set(new CaptureRequest.Key<>(name, Float.class), val);
            return;
        } catch (RuntimeException ignored) {
        }
        try {
            b.set(new CaptureRequest.Key<>(name, float[].class), new float[] {val});
        } catch (RuntimeException ignored) {
        }
    }

    private Range<Integer> strictFpsRange(int targetFps) {
        if (characteristics != null) {
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (ranges != null) {
                for (Range<Integer> r : ranges) {
                    if (r.getUpper() == targetFps && r.getLower() == targetFps) {
                        return r;
                    }
                }
                Range<Integer> best = null;
                for (Range<Integer> r : ranges) {
                    if (r.getUpper() == targetFps) {
                        if (best == null || r.getLower() > best.getLower()) {
                            best = r;
                        }
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        }
        return new Range<>(targetFps, targetFps);
    }

    private int aeMode() {
        if (still != null && settings.nightBoost && caps.lowLightBoost && !settings.torch
                && (!caps.flash || settings.flash == Flash.OFF)) {
            return 6; // CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY (API 35)
        }
        if (still == null || !caps.flash || settings.torch) {
            return CameraMetadata.CONTROL_AE_MODE_ON;
        }
        switch (settings.flash) {
            case AUTO:
                return CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
            case ON:
                return CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
            default:
                return CameraMetadata.CONTROL_AE_MODE_ON;
        }
    }

    /** Modo pedido por el perfil si el HAL lo ofrece; si no, FAST. HQ solo sin calor y a ≤30 fps. */
    private int noiseReductionMode(boolean hqAllowed) {
        int want;
        switch (settings.noiseReduction) {
            case OFF:
                want = CameraMetadata.NOISE_REDUCTION_MODE_OFF;
                break;
            case MINIMAL:
                want = CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL;
                break;
            case HIGH_QUALITY:
                want = hqAllowed ? CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        : CameraMetadata.NOISE_REDUCTION_MODE_FAST;
                break;
            default:
                want = CameraMetadata.NOISE_REDUCTION_MODE_FAST;
        }
        return CameraCaps.has(caps.nrModes, want) ? want : CameraMetadata.NOISE_REDUCTION_MODE_FAST;
    }

    private int edgeMode(boolean hqAllowed) {
        int want;
        switch (settings.edge) {
            case OFF:
                want = CameraMetadata.EDGE_MODE_OFF;
                break;
            case HIGH_QUALITY:
                want = hqAllowed ? CameraMetadata.EDGE_MODE_HIGH_QUALITY : CameraMetadata.EDGE_MODE_FAST;
                break;
            default:
                want = CameraMetadata.EDGE_MODE_FAST;
        }
        return CameraCaps.has(caps.edgeModes, want) ? want : CameraMetadata.EDGE_MODE_FAST;
    }

    private int stabilizationMode() {
        switch (settings.stabilization) {
            case ENHANCED:
            case STANDARD:
                // En el Motorola Signature PREVIEW_STABILIZATION estabiliza con giroscopio tanto el visor como el video grabado.
                if (caps.eisPreview) {
                    return CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
                }
                return caps.eisOn ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                        : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
            default:
                return CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF;
        }
    }

    private void applyRepeating() {
        if (session == null || device == null || target == null) {
            return;
        }
        try {
            if (cancelAfFirst) {
                cancelAfFirst = false;
                CaptureRequest.Builder cancelB = buildRequest(target);
                cancelB.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                cancelB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
                session.capture(cancelB.build(), captureCallback, handler);
            }
            CaptureRequest.Builder b = buildRequest(target);
            session.setRepeatingRequest(b.build(), captureCallback, handler);
            if (triggerAf || triggerAePrecapture) {
                CaptureRequest.Builder trigB = buildRequest(target);
                if (triggerAf) {
                    triggerAf = false;
                    trigB.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                }
                if (triggerAePrecapture) {
                    triggerAePrecapture = false;
                    trigB.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                }
                session.capture(trigB.build(), captureCallback, handler);
            }
        } catch (CameraAccessException | IllegalStateException e) {
            Log.w(TAG, "applyRepeating", e);
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest request, TotalCaptureResult r) {
            Long ts = r.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null) {
                return;
            }
            scratch.timestampNs = ts;
            scratch.exposureNs = orZero(r.get(CaptureResult.SENSOR_EXPOSURE_TIME));
            Integer iso = r.get(CaptureResult.SENSOR_SENSITIVITY);
            Integer boost = r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
            scratch.iso = iso == null ? 0 : (boost == null ? iso : iso * boost / 100);
            Float ap = r.get(CaptureResult.LENS_APERTURE);
            scratch.aperture = ap == null ? 0f : ap;
            Float z = r.get(CaptureResult.CONTROL_ZOOM_RATIO);
            scratch.zoom = z == null ? 1f : z;
            RggbChannelVector g = r.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if (g != null) {
                scratch.wbGains[0] = g.getRed();
                scratch.wbGains[1] = g.getGreenEven();
                scratch.wbGains[2] = g.getGreenOdd();
                scratch.wbGains[3] = g.getBlue();
            }
            scratch.physicalId = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
            Integer ae = r.get(CaptureResult.CONTROL_AE_STATE);
            if (ae != null) {
                lastAeState = ae;
            }
            listener.onFrameMeta(scratch);
        }
    };

    // ------------------------------------------------------------ foto (hilo de cámara)

    /**
     * Toma una foto a resolución completa con el stream JPEG (y RAW si la sesión lo tiene).
     * Con flash corre antes la secuencia de precaptura para que el AE mida con el destello.
     */
    public void takePicture(Flash flash, int jpegOrientation, PictureCallback cb) {
        handler.post(() -> {
            if (session == null || jpegReader == null || device == null) {
                cb.onPictureFailed("La cámara no está lista para fotos");
                return;
            }
            if (pending != null) {
                cb.onPictureFailed("Todavía se está guardando la foto anterior");
                return;
            }
            pending = cb;
            pendingJpeg = null;
            pendingResult = null;
            pendingOrientation = jpegOrientation;
            int seq = ++pictureSeq;
            handler.postDelayed(() -> {
                if (pending != null && seq == pictureSeq) {
                    failPicture("La cámara no entregó la foto a tiempo");
                }
            }, PICTURE_TIMEOUT_MS);
            settings.flash = flash;
            boolean useFlash = flash != Flash.OFF && caps.flash && !settings.exposure.manual;
            if (!useFlash) {
                captureStill();
                return;
            }
            try {
                CaptureRequest.Builder b = buildRequest(target);
                b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                session.capture(b.build(), captureCallback, handler);
            } catch (CameraAccessException | IllegalStateException e) {
                failPicture(e.toString());
                return;
            }
            long start = SystemClock.uptimeMillis();
            boolean[] seen = {false};
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (pending == null || seq != pictureSeq) {
                        return;
                    }
                    int s = lastAeState;
                    long t = SystemClock.uptimeMillis() - start;
                    if (s == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) {
                        seen[0] = true;
                    }
                    boolean settled = s == CameraMetadata.CONTROL_AE_STATE_CONVERGED
                            || s == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED
                            || s == CameraMetadata.CONTROL_AE_STATE_LOCKED;
                    if ((seen[0] && s != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE)
                            || (!seen[0] && settled && t > 400) || t > PRECAPTURE_TIMEOUT_MS) {
                        captureStill();
                    } else {
                        handler.postDelayed(this, 30);
                    }
                }
            });
        });
    }

    private void captureStill() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(target);
            b.addTarget(jpegReader.getSurface());
            if (rawReader != null) {
                b.addTarget(rawReader.getSurface());
            }
            boolean useFlash = settings.flash != Flash.OFF && caps.flash && !settings.exposure.manual;
            b.set(CaptureRequest.CONTROL_ENABLE_ZSL, !useFlash);
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode());
            int idx = Math.round(settings.exposure.aeCompensationEv / caps.aeCompStep);
            idx = Math.max(caps.aeCompRange.getLower(), Math.min(caps.aeCompRange.getUpper(), idx));
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
            b.set(CaptureRequest.CONTROL_AWB_MODE, settings.awbMode);
            if (caps.awbLock) {
                b.set(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock);
            }
            if (settings.focusRegion != null && caps.maxAfRegions > 0) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new MeteringRectangle[] {settings.focusRegion});
            }
            if (settings.focusRegion != null && caps.maxAeRegions > 0) {
                b.set(CaptureRequest.CONTROL_AE_REGIONS, new MeteringRectangle[] {settings.focusRegion});
            }
            if (settings.focusRegion != null && caps.maxAwbRegions > 0) {
                b.set(CaptureRequest.CONTROL_AWB_REGIONS, new MeteringRectangle[] {settings.focusRegion});
            }
            if (settings.focusLock || settings.focusRegion != null) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            } else if (caps.afContinuousPicture) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            if (caps.ois) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
            }
            if (CameraCaps.has(caps.nrModes, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)) {
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            }
            if (CameraCaps.has(caps.edgeModes, CameraMetadata.EDGE_MODE_FAST)) {
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST);
            } else if (CameraCaps.has(caps.edgeModes, CameraMetadata.EDGE_MODE_OFF)) {
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
            }
            if (caps.distortionHighQuality) {
                b.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
            }
            if (caps.aberrationHighQuality) {
                b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                        CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            }
            b.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ);
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY);
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, settings.zoom);
            applySafeHalHints(b);
            b.set(CaptureRequest.JPEG_ORIENTATION, pendingOrientation);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest req, TotalCaptureResult r) {
                    pendingResult = r;
                    maybeDeliver();
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession s, CaptureRequest req, CaptureFailure f) {
                    failPicture("Captura fallida (" + f.getReason() + ")");
                }
            }, handler);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            failPicture(e.toString());
        }
    }

    private void onJpeg(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) {
            return;
        }
        try {
            if (pending == null) {
                return;
            }
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            pendingJpeg = new byte[buf.remaining()];
            buf.get(pendingJpeg);
        } finally {
            img.close();
        }
        maybeDeliver();
    }

    private void onRaw(ImageReader r) {
        Image img = r.acquireNextImage();
        if (img == null) {
            return;
        }
        if (pending == null) {
            img.close();
            return;
        }
        if (pendingRaw != null) {
            pendingRaw.close();
        }
        pendingRaw = img;
        maybeDeliver();
    }

    private void maybeDeliver() {
        if (pending == null || pendingJpeg == null || pendingResult == null) {
            return;
        }
        if (rawReader != null && pendingRaw == null) {
            return;
        }
        byte[] dng = null;
        if (pendingRaw != null) {
            try (DngCreator dc = new DngCreator(characteristics, pendingResult)) {
                dc.setOrientation(exifOrientation(pendingOrientation));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                dc.writeImage(out, pendingRaw);
                dng = out.toByteArray();
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "DNG", e);
            } finally {
                pendingRaw.close();
                pendingRaw = null;
            }
        }
        PictureCallback cb = pending;
        byte[] jpeg = pendingJpeg;
        clearPending();
        applyRepeating();
        cb.onPicture(jpeg, dng);
    }

    private void failPicture(String reason) {
        PictureCallback cb = pending;
        clearPending();
        applyRepeating();
        if (cb != null) {
            cb.onPictureFailed(reason);
        }
    }

    private void clearPending() {
        pending = null;
        pendingJpeg = null;
        pendingResult = null;
        if (pendingRaw != null) {
            pendingRaw.close();
            pendingRaw = null;
        }
    }

    /** Grados horarios → etiqueta EXIF de orientación. */
    public static int exifOrientation(int degrees) {
        switch ((degrees % 360 + 360) % 360) {
            case 90:
                return 6;
            case 180:
                return 3;
            case 270:
                return 8;
            default:
                return 1;
        }
    }

    private void closeReaders() {
        clearPending();
        if (jpegReader != null) {
            jpegReader.close();
            jpegReader = null;
        }
        if (rawReader != null) {
            rawReader.close();
            rawReader = null;
        }
    }

    // ------------------------------------------------------------ cambios de ajustes (cualquier hilo)

    private long lastExposureSubmitNs = 0;
    private long lastZoomSubmitNs = 0;
    private boolean zoomApplyScheduled = false;
    private final Runnable flushPendingZoom = () -> {
        zoomApplyScheduled = false;
        lastZoomSubmitNs = SystemClock.elapsedRealtimeNanos();
        applyRepeating();
    };

    private static int lensBandForZoom(float z) {
        if (z < 0.95f) return 0;
        if (z < 2.85f) return 1;
        return 2;
    }

    public void setExposure(ExposureController.Output out) {
        handler.post(() -> {
            ExposureController.Output cur = settings.exposure;
            long now = SystemClock.elapsedRealtimeNanos();
            boolean force = cur.userManualPro != out.userManualPro || cur.aeLock != out.aeLock;
            boolean deltaSignificant = Math.abs(cur.aeCompensationEv - out.aeCompensationEv) > 0.05f
                    || (out.userManualPro && (Math.abs(cur.exposureNs - out.exposureNs) > cur.exposureNs / 32
                            || Math.abs(cur.iso - out.iso) > Math.max(2, cur.iso / 32)));
            boolean intervalElapsed = (now - lastExposureSubmitNs) >= 33_000_000L && (now - lastZoomSubmitNs) >= 16_000_000L;
            if (force || (deltaSignificant && intervalElapsed)) {
                lastExposureSubmitNs = now;
                settings.exposure = out;
                applyRepeating();
            } else {
                settings.exposure = out;
            }
        });
    }

    public void setZoom(float zoom) {
        handler.post(() -> {
            if (caps == null) {
                return;
            }
            float clamped = Math.max(caps.zoomRange.getLower(), Math.min(caps.zoomRange.getUpper(), zoom));
            if (Math.abs(clamped - settings.zoom) < 0.0025f) {
                return;
            }
            boolean bandCrossed = lensBandForZoom(clamped) != lensBandForZoom(settings.zoom);
            settings.zoom = clamped;
            long now = SystemClock.elapsedRealtimeNanos();
            if (bandCrossed || (now - lastZoomSubmitNs) >= 15_000_000L) {
                if (zoomApplyScheduled) {
                    handler.removeCallbacks(flushPendingZoom);
                    zoomApplyScheduled = false;
                }
                lastZoomSubmitNs = now;
                applyRepeating();
            } else if (!zoomApplyScheduled) {
                zoomApplyScheduled = true;
                long waitMs = Math.max(1L, (15_000_000L - (now - lastZoomSubmitNs)) / 1_000_000L);
                handler.postDelayed(flushPendingZoom, waitMs);
            }
        });
    }

    /** Se aplica en la próxima sesión o petición; no reconfigura la sesión. */
    public void setProcessing(Processing noiseReduction, Processing edge, IspTonemap tonemap) {
        handler.post(() -> {
            settings.noiseReduction = noiseReduction;
            settings.edge = edge;
            settings.tonemap = tonemap;
            applyRepeating();
        });
    }

    /** Linterna continua (flash en video). */
    public void setTorch(boolean on) {
        handler.post(() -> {
            settings.torch = on;
            applyRepeating();
        });
    }

    /** Modo nocturno del AE (low light boost) en la vista previa de foto, si el HAL lo declara. */
    public void setNightBoost(boolean on) {
        handler.post(() -> {
            settings.nightBoost = on;
            applyRepeating();
        });
    }

    /** Modo de flash de la vista previa de foto (el AE informa si haría falta destello). */
    public void setFlash(Flash f) {
        handler.post(() -> {
            settings.flash = f;
            applyRepeating();
        });
    }

    public void setStabilization(Stabilization s) {
        handler.post(() -> {
            settings.stabilization = s;
            applyRepeating();
        });
    }

    /** Enfoque y medición en un punto normalizado del encuadre del sensor (0..1). */
    public void focusAt(float sensorX, float sensorY) {
        handler.post(() -> {
            if (caps == null || caps.activeArray == null) {
                return;
            }
            int left = caps.activeArray.left;
            int top = caps.activeArray.top;
            int w = caps.activeArray.width();
            int h = caps.activeArray.height();
            int size = Math.min(w, h) / 7;
            int cx = left + Math.round(sensorX * w);
            int cy = top + Math.round(sensorY * h);
            int x = Math.max(left, Math.min(left + w - size, cx - size / 2));
            int y = Math.max(top, Math.min(top + h - size, cy - size / 2));
            settings.focusRegion = new MeteringRectangle(x, y, size, size, MeteringRectangle.METERING_WEIGHT_MAX);
            cancelAfFirst = true;
            triggerAf = true;
            triggerAePrecapture = !settings.exposure.manual;
            lastExposureSubmitNs = 0;
            applyRepeating();
        });
    }

    /** Vuelve al enfoque continuo (se desbloquea también el foco). */
    public void resetFocus() {
        handler.post(() -> {
            settings.focusRegion = null;
            settings.focusLock = false;
            applyRepeating();
        });
    }

    public void setLocks(boolean focusLock, boolean awbLock) {
        handler.post(() -> {
            settings.focusLock = focusLock;
            settings.awbLock = awbLock;
            if (focusLock && settings.focusRegion == null) {
                triggerAf = true;
            }
            applyRepeating();
        });
    }

    public void setThermalLimited(boolean limited) {
        handler.post(() -> {
            if (settings.thermalLimited != limited) {
                settings.thermalLimited = limited;
                applyRepeating();
            }
        });
    }

    public void setAwbMode(int mode) {
        handler.post(() -> {
            if (settings.awbMode != mode) {
                settings.awbMode = mode;
                applyRepeating();
            }
        });
    }

    public void close() {
        handler.post(this::closeInternal);
    }

    public void shutdown() {
        handler.post(() -> {
            closeInternal();
            thread.quitSafely();
        });
    }

    private void closeInternal() {
        if (session != null) {
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (pending != null) {
            failPicture("Se cerró la cámara");
        }
        closeReaders();
        target = null;
    }

    private static long orZero(Long v) {
        return v != null ? v : 0L;
    }
}
