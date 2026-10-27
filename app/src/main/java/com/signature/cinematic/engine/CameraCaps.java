package com.signature.cinematic.engine;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.List;

/** Lo que el motor necesita saber de una cámara, leído de CameraCharacteristics. */
public final class CameraCaps {
    private static final int[][] CANDIDATES = {
        {3840, 2160, 24}, {3840, 2160, 30}, {3840, 2160, 60}, {7680, 4320, 30},
        {1920, 1080, 24}, {1920, 1080, 30}, {1920, 1080, 60},
    };

    public String id;
    public boolean front;
    public int sensorOrientation;
    public boolean manualSensor;
    public boolean readSensorSettings;
    public boolean realtimeTimestamps;
    public boolean videoRecordUseCase;
    public boolean ois;
    public boolean eisOn;
    public boolean eisPreview;
    public boolean nrHighQuality;
    public int[] nrModes = new int[0];
    public int[] edgeModes = new int[0];
    public boolean tonemapPresetSrgb;
    public boolean tonemapHighQuality;
    public boolean aberrationHighQuality;
    public boolean lowLightBoost;
    public boolean distortionHighQuality;
    public float mainFocalMm = 24f;
    public float mainAperture = 1.6f;
    /** Claves propias de Motorola / Qualcomm declaradas en esta cámara para activar optimizaciones de hardware. */
    public final java.util.Set<String> vendorRequestKeys = new java.util.HashSet<>();
    public final java.util.Set<String> vendorSessionKeys = new java.util.HashSet<>();
    public boolean afContinuousVideo;
    public boolean logicalMultiCamera;
    public boolean zslCapable;
    public int maxAfRegions;
    public int maxAeRegions;
    public int maxAwbRegions;
    public final java.util.Set<String> physicalIds = new java.util.HashSet<>();
    public boolean aeLock;
    public boolean awbLock;
    public Rect activeArray;
    public Range<Float> zoomRange = new Range<>(1f, 1f);
    public Range<Integer> aeCompRange = new Range<>(0, 0);
    public float aeCompStep = 1f / 3f;
    public long minExposureNs = 100_000L;
    public long maxExposureNs = 33_333_333L;
    public int minIso = 100;
    public int maxIso = 3200;
    /** Zoom de cada lente física respecto a la principal (p. ej. 0.55, 1, 3). */
    public final List<Float> lensZooms = new ArrayList<>();
    public final List<VideoMode> modes = new ArrayList<>();
    /** Stream 4:3 de vista previa para foto (≤1920) y para Retrato (≤3840, se lee entero en la GPU). */
    public VideoMode photoPreview;
    public VideoMode portraitPreview;
    public Size jpegSize;
    /** null si la cámara no entrega RAW_SENSOR. */
    public Size rawSize;
    public boolean flash;
    public boolean afContinuousPicture;

    public static CameraCaps read(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics cc = manager.getCameraCharacteristics(id);
        CameraCaps c = new CameraCaps();
        c.id = id;
        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        c.front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        c.sensorOrientation = orZero(cc.get(CameraCharacteristics.SENSOR_ORIENTATION));
        int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        c.manualSensor = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        c.readSensorSettings = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS);
        c.videoRecordUseCase = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE)
                && hasLong(cc.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES),
                        CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD);
        Integer ts = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        c.realtimeTimestamps = ts != null && ts == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
        c.ois = has(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        int[] vs = cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        c.eisOn = has(vs, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
        c.eisPreview = has(vs, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION);
        int[] nr = cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        c.nrModes = nr != null ? nr : new int[0];
        c.nrHighQuality = has(nr, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        int[] edge = cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
        c.edgeModes = edge != null ? edge : new int[0];
        int[] tm = cc.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        c.tonemapPresetSrgb = has(tm, CameraMetadata.TONEMAP_MODE_PRESET_CURVE);
        c.tonemapHighQuality = has(tm, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY);
        c.aberrationHighQuality = has(cc.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        c.lowLightBoost = has(cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                6 /* CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY (API 35) */);
        c.distortionHighQuality = has(cc.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY);
        double eq = equivalentFocal(cc);
        if (eq > 0) {
            c.mainFocalMm = Math.round(eq);
        }
        float[] aps = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (aps != null && aps.length > 0) {
            c.mainAperture = aps[0];
        }
        for ( android.hardware.camera2.CaptureRequest.Key<?> k : cc.getAvailableCaptureRequestKeys()) {
            String n = k.getName();
            if (n.startsWith("com.lenovo.") || n.startsWith("org.codeaurora.") || n.startsWith("org.quic.")) {
                c.vendorRequestKeys.add(n);
            }
        }
        for ( android.hardware.camera2.CaptureRequest.Key<?> k : cc.getAvailableSessionKeys()) {
            String n = k.getName();
            if (n.startsWith("com.lenovo.") || n.startsWith("org.codeaurora.") || n.startsWith("org.quic.")) {
                c.vendorSessionKeys.add(n);
            }
        }
        c.afContinuousVideo = has(cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
        c.logicalMultiCamera = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
        c.zslCapable = has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING)
                || has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING);
        c.maxAfRegions = orZero(cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
        c.maxAeRegions = orZero(cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE));
        c.maxAwbRegions = orZero(cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB));
        if (c.logicalMultiCamera) {
            try {
                c.physicalIds.addAll(cc.getPhysicalCameraIds());
                for (String pid : c.physicalIds) {
                    CameraCharacteristics pcc = manager.getCameraCharacteristics(pid);
                    if (has(pcc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                        c.ois = true;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        c.aeLock = Boolean.TRUE.equals(cc.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
        c.awbLock = Boolean.TRUE.equals(cc.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
        c.activeArray = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Range<Float> zoom = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        if (zoom != null) {
            c.zoomRange = zoom;
        }
        Range<Integer> comp = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (comp != null) {
            c.aeCompRange = comp;
        }
        if (cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) != null) {
            c.aeCompStep = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).floatValue();
        }
        Range<Long> exp = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exp != null) {
            c.minExposureNs = exp.getLower();
            c.maxExposureNs = exp.getUpper();
        }
        Range<Integer> iso = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso != null) {
            c.minIso = iso.getLower();
            c.maxIso = iso.getUpper();
        }
        c.flash = Boolean.TRUE.equals(cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
        c.afContinuousPicture = has(cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        c.readLensZooms(manager, cc);
        c.readModes(cc);
        c.readPhoto(cc, has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW));
        return c;
    }

    private void readLensZooms(CameraManager manager, CameraCharacteristics logical) throws CameraAccessException {
        lensZooms.clear();
        if (front) {
            lensZooms.add(1f);
            return;
        }
        // Matriz de lentes y recortes de alta resolución del Motorola Signature:
        // .5x (13mm Ultra Gran Angular) · 1x (24mm Principal) · 2x (48mm In-Sensor Crop) · 3x (70mm Teleobjetivo) · 6x (140mm Tele Crop)
        lensZooms.add(0.5f);
        lensZooms.add(1f);
        lensZooms.add(2f);
        lensZooms.add(3f);
        lensZooms.add(6f);
    }

    private static double equivalentFocal(CameraCharacteristics cc) {
        SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] f = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (phys == null || f == null || f.length == 0) {
            return -1;
        }
        return f[0] * 43.27 / Math.hypot(phys.getWidth(), phys.getHeight());
    }

    private void readModes(CameraCharacteristics cc) {
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Range<Integer>[] fpsRanges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (map == null || fpsRanges == null) {
            return;
        }
        Size[] surfaceSizes = map.getOutputSizes(SurfaceTexture.class);
        Size[] privSizes = map.getOutputSizes(ImageFormat.PRIVATE);
        MediaCodecList codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (int[] m : CANDIDATES) {
            Size s = new Size(m[0], m[1]);
            boolean inSurface = contains(surfaceSizes, s);
            boolean inPriv = contains(privSizes, s);
            if (!inSurface && !inPriv) {
                continue;
            }
            long minDur = inSurface ? map.getOutputMinFrameDuration(SurfaceTexture.class, s)
                    : map.getOutputMinFrameDuration(ImageFormat.PRIVATE, s);
            if (minDur > 0 && 1e9 / minDur < m[2] - 0.5) {
                continue;
            }
            int lower = fpsLowerBound(fpsRanges, m[2]);
            if (lower < 0) {
                continue;
            }
            Boolean portrait = encoderOrientation(codecs, m[0], m[1], m[2]);
            if (portrait != null) {
                modes.add(new VideoMode(m[0], m[1], m[2], lower, portrait));
            }
        }
    }

    private void readPhoto(CameraCharacteristics cc, boolean rawCapable) {
        StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Range<Integer>[] fpsRanges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (map == null || fpsRanges == null) {
            return;
        }
        int lower = fpsLowerBound(fpsRanges, 30);
        if (lower < 0) {
            return;
        }
        Size[] priv = map.getOutputSizes(SurfaceTexture.class);
        Size preview = largest43(map, priv, 1920);
        Size portrait = largest43(map, priv, 3840);
        if (preview != null) {
            photoPreview = new VideoMode(preview.getWidth(), preview.getHeight(), 30, lower, false);
        }
        if (portrait != null) {
            portraitPreview = new VideoMode(portrait.getWidth(), portrait.getHeight(), 30, lower, false);
        }
        jpegSize = largest43(null, map.getOutputSizes(ImageFormat.JPEG), Integer.MAX_VALUE);
        if (jpegSize == null) {
            jpegSize = largest(map.getOutputSizes(ImageFormat.JPEG));
        }
        if (rawCapable) {
            rawSize = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
        }
    }

    /** Mayor tamaño 4:3 con lado largo ≤ maxLong; si se pasa el mapa, debe sostener 30 fps. */
    private static Size largest43(StreamConfigurationMap map, Size[] sizes, int maxLong) {
        Size best = null;
        if (sizes == null) {
            return null;
        }
        for (Size s : sizes) {
            int l = Math.max(s.getWidth(), s.getHeight());
            int sh = Math.min(s.getWidth(), s.getHeight());
            if (l > maxLong || Math.abs(l * 3 - sh * 4) > l / 100) {
                continue;
            }
            if (map != null) {
                long d = map.getOutputMinFrameDuration(SurfaceTexture.class, s);
                if (d <= 0 || d > 33_400_000L) {
                    continue;
                }
            }
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    private static Size largest(Size[] sizes) {
        Size best = null;
        if (sizes != null) {
            for (Size s : sizes) {
                if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                    best = s;
                }
            }
        }
        return best;
    }

    /** null si ningún codificador HEVC/AVC por hardware acepta el modo; true si acepta el cuadro vertical. */
    static Boolean encoderOrientation(MediaCodecList codecs, int w, int h, int fps) {
        boolean landscape = false;
        for (String mime : new String[] {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC}) {
            for (MediaCodecInfo info : codecs.getCodecInfos()) {
                if (!info.isEncoder() || !info.isHardwareAccelerated() || !supports(info, mime)) {
                    continue;
                }
                MediaCodecInfo.VideoCapabilities vc = info.getCapabilitiesForType(mime).getVideoCapabilities();
                if (vc == null) {
                    continue;
                }
                if (vc.areSizeAndRateSupported(h, w, fps)) {
                    return true;
                }
                landscape |= vc.areSizeAndRateSupported(w, h, fps);
            }
        }
        return landscape ? Boolean.FALSE : null;
    }

    private static boolean supports(MediaCodecInfo info, String mime) {
        for (String t : info.getSupportedTypes()) {
            if (t.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    /** Límite inferior del rango de AE que termina en fps; prefiere el fijo [fps,fps]. -1 si no hay. */
    private static int fpsLowerBound(Range<Integer>[] ranges, int fps) {
        int best = -1;
        for (Range<Integer> r : ranges) {
            if (r.getUpper() == fps && r.getLower() > best) {
                best = r.getLower();
            }
        }
        return best;
    }

    private static boolean contains(Size[] sizes, Size s) {
        if (sizes == null) {
            return false;
        }
        for (Size x : sizes) {
            if (x.equals(s)) {
                return true;
            }
        }
        return false;
    }

    public static boolean has(int[] arr, int v) {
        if (arr == null) {
            return false;
        }
        for (int x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLong(long[] arr, long v) {
        if (arr == null) {
            return false;
        }
        for (long x : arr) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static int orZero(Integer v) {
        return v != null ? v : 0;
    }
}
