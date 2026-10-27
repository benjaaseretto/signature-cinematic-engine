package com.signature.cinematic.diag;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.ColorSpace;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceProfiles;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.EncoderProfiles;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MicrophoneInfo;
import android.opengl.GLES30;
import android.os.Build;
import android.os.PowerManager;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Display;
import android.view.WindowManager;

import com.signature.cinematic.gl.EglCore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Inspección de capacidades reales: Camera2, MediaCodec, GPU, pantalla, audio y térmica.
 * Solo usa API públicas; lo que dependa del ISP o del firmware de Motorola queda marcado como no accesible.
 */
public final class CapabilityProbe {
    private static final int[][] TEST_MODES = {
        {7680, 4320, 30}, {3840, 2160, 60}, {3840, 2160, 30}, {1920, 1080, 120}, {1920, 1080, 60}, {1920, 1080, 30},
    };

    private final Context context;
    private final CameraManager cameras;
    public final List<String> confirmed = new ArrayList<>();
    public final List<String> unavailable = new ArrayList<>();
    public final List<String> undetermined = new ArrayList<>();

    public CapabilityProbe(Context context) {
        this.context = context;
        this.cameras = context.getSystemService(CameraManager.class);
    }

    public JSONObject run() throws Exception {
        JSONObject root = new JSONObject();
        root.put("generated_at", System.currentTimeMillis());
        root.put("device", device());
        root.put("cameras", cameraSection());
        root.put("encoders", encoderSection());
        root.put("gpu", gpuSection());
        root.put("display", displaySection());
        root.put("audio", audioSection());
        root.put("thermal", thermalSection());
        undetermined.add("Acceso directo a buffers internos del ISP y parámetros privados de Motorola: no expuestos por API pública.");
        undetermined.add("Latencia, consumo y temperatura sostenidos: se miden durante la grabación (registro de métricas).");
        JSONObject summary = new JSONObject();
        summary.put("confirmado", new JSONArray(confirmed));
        summary.put("no_disponible", new JSONArray(unavailable));
        summary.put("no_determinado", new JSONArray(undetermined));
        root.put("summary", summary);
        return root;
    }

    private JSONObject device() throws Exception {
        JSONObject d = new JSONObject();
        d.put("manufacturer", Build.MANUFACTURER);
        d.put("model", Build.MODEL);
        d.put("device", Build.DEVICE);
        d.put("soc_manufacturer", Build.SOC_MANUFACTURER);
        d.put("soc_model", Build.SOC_MODEL);
        d.put("android", Build.VERSION.RELEASE);
        d.put("sdk", Build.VERSION.SDK_INT);
        d.put("build", Build.DISPLAY);
        d.put("security_patch", Build.VERSION.SECURITY_PATCH);
        ActivityManager am = context.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        d.put("ram_total_mb", mi.totalMem / (1024 * 1024));
        d.put("cpu_cores", Runtime.getRuntime().availableProcessors());
        d.put("app_heap_max_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        return d;
    }

    // ---------------------------------------------------------------- Cámaras

    private JSONArray cameraSection() throws Exception {
        JSONArray out = new JSONArray();
        List<String> ids = new ArrayList<>(Arrays.asList(cameras.getCameraIdList()));
        List<String> physicalOnly = new ArrayList<>();
        for (String id : ids) {
            for (String p : cameras.getCameraCharacteristics(id).getPhysicalCameraIds()) {
                if (!ids.contains(p) && !physicalOnly.contains(p)) {
                    physicalOnly.add(p);
                }
            }
        }
        for (String id : ids) {
            out.put(camera(id, false));
        }
        for (String id : physicalOnly) {
            out.put(camera(id, true));
        }
        return out;
    }

    private JSONObject camera(String id, boolean physicalOnly) {
        JSONObject c = new JSONObject();
        try {
            CameraCharacteristics cc = cameras.getCameraCharacteristics(id);
            String facing = Names.facing(cc.get(CameraCharacteristics.LENS_FACING));
            String label = "Cámara " + id + " (" + facing + (physicalOnly ? ", física" : "") + ")";
            c.put("id", id);
            c.put("facing", facing);
            c.put("physical_only", physicalOnly);
            c.put("hardware_level", Names.hardwareLevel(cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)));
            c.put("info_version", cc.get(CameraCharacteristics.INFO_VERSION));

            int[] caps = orEmpty(cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES));
            JSONArray capNames = new JSONArray();
            for (int cap : caps) {
                capNames.put(Names.capability(cap));
            }
            c.put("capabilities", capNames);
            c.put("physical_ids", new JSONArray(cc.getPhysicalCameraIds()));

            c.put("sensor_orientation", cc.get(CameraCharacteristics.SENSOR_ORIENTATION));
            SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (phys != null) {
                c.put("sensor_physical_mm", phys.getWidth() + "x" + phys.getHeight());
            }
            Size pixels = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (pixels != null) {
                c.put("pixel_array", pixels.toString());
            }
            Rect active = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (active != null) {
                c.put("active_array", active.flattenToString());
            }
            c.put("focal_lengths_mm", floats(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
            c.put("apertures", floats(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)));
            c.put("equivalent_focal_mm", equivalentFocal(cc));
            c.put("min_focus_diopters", cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE));

            int[] ois = orEmpty(cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION));
            int[] vstab = orEmpty(cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES));
            c.put("ois_modes", ints(ois));
            JSONArray vs = new JSONArray();
            for (int m : vstab) {
                vs.put(Names.stabilization(m));
            }
            c.put("video_stabilization_modes", vs);

            JSONArray ae = new JSONArray();
            for (int m : orEmpty(cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES))) {
                ae.put(Names.aeMode(m));
            }
            c.put("ae_modes", ae);
            JSONArray af = new JSONArray();
            for (int m : orEmpty(cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES))) {
                af.put(Names.afMode(m));
            }
            c.put("af_modes", af);
            c.put("awb_modes", ints(orEmpty(cc.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES))));
            c.put("max_regions_ae_awb_af", cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) + "/"
                    + cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) + "/"
                    + cc.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF));
            Range<Integer> comp = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational step = cc.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            c.put("ae_compensation", (comp != null ? comp.toString() : "?") + " x " + step);
            c.put("ae_lock", cc.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE));
            c.put("awb_lock", cc.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE));
            JSONArray fps = new JSONArray();
            Range<Integer>[] fpsRanges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (fpsRanges != null) {
                for (Range<Integer> r : fpsRanges) {
                    fps.put(r.toString());
                }
            }
            c.put("ae_target_fps_ranges", fps);

            Range<Long> exp = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> iso = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            c.put("exposure_time_ns", exp != null ? exp.toString() : null);
            c.put("iso_range", iso != null ? iso.toString() : null);
            c.put("max_analog_iso", cc.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY));
            c.put("max_frame_duration_ns", cc.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION));
            Integer ts = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            c.put("timestamp_source", ts != null && ts == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                    ? "REALTIME" : "UNKNOWN");

            JSONArray nr = new JSONArray();
            for (int m : orEmpty(cc.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES))) {
                nr.put(Names.processingMode(m));
            }
            c.put("noise_reduction_modes", nr);
            JSONArray edge = new JSONArray();
            for (int m : orEmpty(cc.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES))) {
                edge.put(Names.processingMode(m));
            }
            c.put("edge_modes", edge);
            JSONArray tm = new JSONArray();
            int[] tonemap = orEmpty(cc.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES));
            for (int m : tonemap) {
                tm.put(Names.tonemapMode(m));
            }
            c.put("tonemap_modes", tm);
            c.put("tonemap_max_curve_points", cc.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS));
            c.put("distortion_correction_modes",
                    ints(orEmpty(cc.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES))));
            c.put("aberration_modes",
                    ints(orEmpty(cc.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES))));
            c.put("face_detect_modes",
                    ints(orEmpty(cc.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES))));
            Boolean autoframing = cc.get(CameraCharacteristics.CONTROL_AUTOFRAMING_AVAILABLE);
            c.put("autoframing", autoframing);
            c.put("flash", cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));

            Range<Float> zoom = cc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            c.put("zoom_ratio_range", zoom != null ? zoom.toString() : null);
            c.put("max_digital_zoom", cc.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM));

            colorCalibration(c, cc);
            JSONArray drp = dynamicRanges(c, cc);
            c.put("color_space_profiles", colorSpaces(cc));
            long[] useCases = cc.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES);
            JSONArray uc = new JSONArray();
            if (useCases != null) {
                for (long u : useCases) {
                    uc.put(Names.streamUseCase(u));
                }
            }
            c.put("stream_use_cases", uc);

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                c.put("outputs", outputs(map));
                c.put("high_speed_video", highSpeed(map));
            }
            if (!physicalOnly) {
                c.put("encoder_profiles", encoderProfiles(id));
                c.put("session_checks", sessionChecks(id, cc, map, drp, vstab));
            }
            summarizeCamera(label, cc, caps, ois, vstab, tonemap, zoom, map, physicalOnly);
        } catch (Throwable t) {
            try {
                c.put("error", t.toString());
            } catch (Exception ignored) {
                // El objeto ya contiene lo que se pudo leer.
            }
        }
        return c;
    }

    private static Object equivalentFocal(CameraCharacteristics cc) {
        SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] f = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (phys == null || f == null || f.length == 0) {
            return null;
        }
        double diag = Math.hypot(phys.getWidth(), phys.getHeight());
        return Math.round(f[0] * 43.27 / diag * 10) / 10.0;
    }

    private void colorCalibration(JSONObject c, CameraCharacteristics cc) throws Exception {
        JSONObject cal = new JSONObject();
        cal.put("reference_illuminant1", cc.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
        cal.put("reference_illuminant2", cc.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));
        cal.put("color_transform1", transform(cc.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)));
        cal.put("forward_matrix1", transform(cc.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)));
        cal.put("calibration_transform1", transform(cc.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)));
        cal.put("white_level", cc.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        cal.put("cfa", cc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT));
        c.put("color_calibration_dng", cal);
    }

    private static JSONArray transform(ColorSpaceTransform t) throws Exception {
        if (t == null) {
            return null;
        }
        JSONArray a = new JSONArray();
        for (int r = 0; r < 3; r++) {
            for (int col = 0; col < 3; col++) {
                a.put(t.getElement(col, r).floatValue());
            }
        }
        return a;
    }

    private JSONArray dynamicRanges(JSONObject c, CameraCharacteristics cc) throws Exception {
        JSONArray out = new JSONArray();
        DynamicRangeProfiles drp = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
        if (drp != null) {
            for (Long p : drp.getSupportedProfiles()) {
                JSONObject o = new JSONObject();
                o.put("profile", Names.dynamicRange(p));
                o.put("value", p);
                o.put("extra_latency", drp.isExtraLatencyPresent(p));
                JSONArray with = new JSONArray();
                for (Long q : drp.getProfileCaptureRequestConstraints(p)) {
                    with.put(Names.dynamicRange(q));
                }
                o.put("combinable_with", with);
                out.put(o);
            }
        }
        c.put("dynamic_range_profiles", out);
        Long rec = cc.get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE);
        c.put("recommended_10bit_profile", rec != null ? Names.dynamicRange(rec) : null);
        return out;
    }

    private static JSONArray colorSpaces(CameraCharacteristics cc) {
        JSONArray out = new JSONArray();
        ColorSpaceProfiles csp = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_COLOR_SPACE_PROFILES);
        if (csp != null) {
            Set<ColorSpace.Named> spaces = csp.getSupportedColorSpaces(ImageFormat.PRIVATE);
            for (ColorSpace.Named n : spaces) {
                out.put(n.name());
            }
        }
        return out;
    }

    private JSONArray outputs(StreamConfigurationMap map) throws Exception {
        JSONArray out = new JSONArray();
        for (int f : map.getOutputFormats()) {
            JSONObject o = new JSONObject();
            o.put("format", Names.format(f));
            JSONArray sizes = new JSONArray();
            Size[] list = map.getOutputSizes(f);
            if (list != null) {
                for (Size s : list) {
                    long d = map.getOutputMinFrameDuration(f, s);
                    sizes.put(s + "@" + (d > 0 ? Math.round(1e9 / d) : 0));
                }
            }
            o.put("sizes_maxfps", sizes);
            Size[] hi = map.getHighResolutionOutputSizes(f);
            if (hi != null && hi.length > 0) {
                o.put("high_resolution", new JSONArray(Arrays.asList(hi)).toString());
            }
            out.put(o);
        }
        return out;
    }

    private static JSONArray highSpeed(StreamConfigurationMap map) throws Exception {
        JSONArray out = new JSONArray();
        for (Size s : map.getHighSpeedVideoSizes()) {
            JSONObject o = new JSONObject();
            o.put("size", s.toString());
            JSONArray r = new JSONArray();
            for (Range<Integer> fr : map.getHighSpeedVideoFpsRangesFor(s)) {
                r.put(fr.toString());
            }
            o.put("fps", r);
            out.put(o);
        }
        return out;
    }

    private static final int[] QUALITIES = {
        CamcorderProfile.QUALITY_8KUHD, CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P,
        CamcorderProfile.QUALITY_HIGH_SPEED_2160P, CamcorderProfile.QUALITY_HIGH_SPEED_1080P,
    };
    private static final String[] QUALITY_NAMES = {"8K", "2160p", "1080p", "HS_2160p", "HS_1080p"};

    private static JSONArray encoderProfiles(String id) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < QUALITIES.length; i++) {
            EncoderProfiles ep;
            try {
                ep = CamcorderProfile.getAll(id, QUALITIES[i]);
            } catch (Exception e) {
                ep = null;
            }
            if (ep == null) {
                continue;
            }
            for (EncoderProfiles.VideoProfile v : ep.getVideoProfiles()) {
                if (v == null) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("quality", QUALITY_NAMES[i]);
                o.put("mime", v.getMediaType());
                o.put("size", v.getWidth() + "x" + v.getHeight());
                o.put("fps", v.getFrameRate());
                o.put("bitrate", v.getBitrate());
                o.put("profile", v.getProfile());
                o.put("hdr_format", v.getHdrFormat());
                o.put("bit_depth", v.getBitDepth());
                out.put(o);
            }
        }
        return out;
    }

    /** Prueba la configuración exacta del motor (un stream hacia la GPU) sin abrir la cámara. */
    private JSONArray sessionChecks(String id, CameraCharacteristics cc, StreamConfigurationMap map,
                                    JSONArray drp, int[] vstab) throws Exception {
        JSONArray out = new JSONArray();
        if (!cameras.isCameraDeviceSetupSupported(id)) {
            undetermined.add("Cámara " + id + ": el HAL no permite validar sesiones sin abrir la cámara (CameraDeviceSetup).");
            return out;
        }
        CameraDevice.CameraDeviceSetup setup = cameras.getCameraDeviceSetup(id);
        List<Long> profiles = new ArrayList<>();
        profiles.add(DynamicRangeProfiles.STANDARD);
        for (int i = 0; i < drp.length(); i++) {
            long p = drp.getJSONObject(i).getLong("value");
            if (p != DynamicRangeProfiles.STANDARD) {
                profiles.add(p);
            }
        }
        int[] stabs = vstab.length > 0 ? vstab : new int[] {CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF};
        for (int[] m : TEST_MODES) {
            Size size = new Size(m[0], m[1]);
            if (map == null || !contains(map.getOutputSizes(SurfaceTexture.class), size)) {
                continue;
            }
            for (long profile : profiles) {
                for (int stab : stabs) {
                    JSONObject o = new JSONObject();
                    o.put("mode", m[0] + "x" + m[1] + "@" + m[2]);
                    o.put("dynamic_range", Names.dynamicRange(profile));
                    o.put("stabilization", Names.stabilization(stab));
                    try {
                        OutputConfiguration oc = new OutputConfiguration(size, SurfaceTexture.class);
                        oc.setDynamicRangeProfile(profile);
                        SessionConfiguration sc = new SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR, List.of(oc));
                        CaptureRequest.Builder b = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(m[2], m[2]));
                        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stab);
                        sc.setSessionParameters(b.build());
                        boolean ok = setup.isSessionConfigurationSupported(sc);
                        o.put("supported", ok);
                        if (ok) {
                            CameraCharacteristics sch = setup.getSessionCharacteristics(sc);
                            Range<Float> z = sch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                            o.put("zoom_ratio_range", z != null ? z.toString() : null);
                        }
                    } catch (Throwable t) {
                        o.put("supported", JSONObject.NULL);
                        o.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                    out.put(o);
                }
            }
        }
        return out;
    }

    private void summarizeCamera(String label, CameraCharacteristics cc, int[] caps, int[] ois, int[] vstab,
                                 int[] tonemap, Range<Float> zoom, StreamConfigurationMap map, boolean physicalOnly) {
        mark(label + ": control manual de sensor (ISO/tiempo)", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR));
        mark(label + ": lectura de ajustes por fotograma", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS));
        mark(label + ": RAW", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW));
        mark(label + ": salida de 10 bits (HDR)", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT));
        mark(label + ": cámara lógica multi-lente", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA));
        mark(label + ": cámara lenta (alta velocidad)", has(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO));
        mark(label + ": OIS", has(ois, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON));
        mark(label + ": EIS del sistema (ON)", has(vstab, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON));
        mark(label + ": estabilización PREVIEW_STABILIZATION", has(vstab, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION));
        mark(label + ": curva tonal propia en el ISP", has(tonemap, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE));
        if (!physicalOnly && zoom != null) {
            mark(label + ": zoom por debajo de 1x (cambio de lente sin cortar)", zoom.getLower() < 0.99f);
        }
        if (map != null) {
            Size[] st = map.getOutputSizes(SurfaceTexture.class);
            mark(label + ": salida 3840x2160 a 60 fps hacia GPU", fpsAtLeast(map, st, 3840, 2160, 60));
            mark(label + ": salida 7680x4320 a 30 fps hacia GPU", fpsAtLeast(map, st, 7680, 4320, 30));
            mark(label + ": salida YCBCR_P010", map.isOutputSupportedFor(ImageFormat.YCBCR_P010));
        }
        DynamicRangeProfiles drp = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES);
        Set<Long> p = drp != null ? drp.getSupportedProfiles() : Set.of();
        mark(label + ": HLG10", p.contains(DynamicRangeProfiles.HLG10));
        mark(label + ": HDR10", p.contains(DynamicRangeProfiles.HDR10));
        mark(label + ": HDR10+", p.contains(DynamicRangeProfiles.HDR10_PLUS));
        boolean dv = false;
        for (Long v : p) {
            dv |= Names.dynamicRange(v).startsWith("DOLBY");
        }
        mark(label + ": perfil Dolby Vision en Camera2", dv);
    }

    private static boolean fpsAtLeast(StreamConfigurationMap map, Size[] sizes, int w, int h, int fps) {
        Size s = new Size(w, h);
        if (!contains(sizes, s)) {
            return false;
        }
        long d = map.getOutputMinFrameDuration(SurfaceTexture.class, s);
        return d > 0 && 1e9 / d >= fps - 0.5;
    }

    // ---------------------------------------------------------------- Codificadores

    private JSONArray encoderSection() throws Exception {
        JSONArray out = new JSONArray();
        boolean hevcHw = false;
        boolean main10 = false;
        boolean hdr10 = false;
        boolean hdr10plus = false;
        boolean dvEnc = false;
        boolean av1Enc = false;
        boolean hevc4k60 = false;
        boolean hevc8k30 = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!type.startsWith("video/")) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("name", info.getName());
                o.put("type", type);
                o.put("hardware", info.isHardwareAccelerated());
                o.put("vendor", info.isVendor());
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                JSONArray pl = new JSONArray();
                for (MediaCodecInfo.CodecProfileLevel l : caps.profileLevels) {
                    String name;
                    switch (type) {
                        case MediaFormat.MIMETYPE_VIDEO_HEVC: name = Names.hevcProfile(l.profile); break;
                        case MediaFormat.MIMETYPE_VIDEO_AVC: name = Names.avcProfile(l.profile); break;
                        case MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION: name = Names.dolbyProfile(l.profile); break;
                        case MediaFormat.MIMETYPE_VIDEO_AV1: name = Names.av1Profile(l.profile); break;
                        default: name = String.valueOf(l.profile);
                    }
                    pl.put(name + "@" + l.level);
                    boolean hw = info.isHardwareAccelerated();
                    if (hw && type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        main10 |= l.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
                        hdr10 |= l.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10;
                        hdr10plus |= l.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus;
                    }
                }
                o.put("profile_levels", pl);
                MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
                if (vc != null) {
                    o.put("bitrate_range", vc.getBitrateRange().toString());
                    o.put("width_range", vc.getSupportedWidths().toString());
                    o.put("height_range", vc.getSupportedHeights().toString());
                    boolean k60 = vc.areSizeAndRateSupported(3840, 2160, 60);
                    boolean k8 = vc.areSizeAndRateSupported(7680, 4320, 30);
                    o.put("4k60", k60);
                    o.put("4k120", vc.areSizeAndRateSupported(3840, 2160, 120));
                    o.put("8k30", k8);
                    o.put("1080p240", vc.areSizeAndRateSupported(1920, 1080, 240));
                    List<MediaCodecInfo.VideoCapabilities.PerformancePoint> pp = vc.getSupportedPerformancePoints();
                    if (pp != null) {
                        JSONArray pps = new JSONArray();
                        for (MediaCodecInfo.VideoCapabilities.PerformancePoint p : pp) {
                            pps.put(p.toString());
                        }
                        o.put("performance_points", pps);
                    }
                    if (info.isHardwareAccelerated() && type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                        hevcHw = true;
                        hevc4k60 |= k60;
                        hevc8k30 |= k8;
                    }
                }
                MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
                if (ec != null) {
                    o.put("bitrate_cq", ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ));
                    o.put("bitrate_vbr", ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR));
                    o.put("bitrate_cbr", ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR));
                    o.put("quality_range", ec.getQualityRange().toString());
                }
                o.put("hdr_editing", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing));
                o.put("qp_bounds", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds));
                o.put("surface_input", has(caps.colorFormats, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface));
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION)) {
                    dvEnc = true;
                }
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_AV1) && info.isHardwareAccelerated()) {
                    av1Enc = true;
                }
                out.put(o);
            }
        }
        mark("Codificador HEVC por hardware", hevcHw);
        mark("HEVC 4K60 en el codificador", hevc4k60);
        mark("HEVC 8K30 en el codificador", hevc8k30);
        mark("HEVC Main10 (10 bits)", main10);
        mark("HEVC Main10 HDR10", hdr10);
        mark("HEVC Main10 HDR10+", hdr10plus);
        mark("Codificador Dolby Vision accesible por MediaCodec", dvEnc);
        mark("Codificador AV1 por hardware", av1Enc);
        return out;
    }

    // ---------------------------------------------------------------- GPU, pantalla, audio, térmica

    private JSONObject gpuSection() throws Exception {
        JSONObject g = new JSONObject();
        EglCore egl = null;
        android.opengl.EGLSurface pb = null;
        try {
            egl = new EglCore();
            pb = egl.createPbuffer(1, 1);
            egl.makeCurrent(pb);
            g.put("renderer", GLES30.glGetString(GLES30.GL_RENDERER));
            g.put("vendor", GLES30.glGetString(GLES30.GL_VENDOR));
            g.put("version", GLES30.glGetString(GLES30.GL_VERSION));
            g.put("glsl", GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION));
            String ext = GLES30.glGetString(GLES30.GL_EXTENSIONS);
            String eglExt = egl.eglExtensions();
            String[] wanted = {
                "GL_OES_EGL_image_external_essl3", "GL_EXT_YUV_target", "GL_EXT_color_buffer_half_float",
                "GL_EXT_color_buffer_float", "GL_EXT_texture_norm16", "GL_OES_texture_float_linear",
            };
            String[] wantedEgl = {
                "EGL_EXT_gl_colorspace_bt2020_pq", "EGL_EXT_gl_colorspace_bt2020_hlg", "EGL_EXT_gl_colorspace_bt2020_linear",
                "EGL_EXT_gl_colorspace_display_p3", "EGL_ANDROID_recordable", "EGL_ANDROID_presentation_time",
                "EGL_EXT_pixel_format_float",
            };
            JSONObject e = new JSONObject();
            for (String w : wanted) {
                e.put(w, ext != null && ext.contains(w));
            }
            for (String w : wantedEgl) {
                e.put(w, eglExt != null && eglExt.contains(w));
            }
            g.put("extensions", e);
            mark("GPU: textura externa de cámara en GLSL ES 3", ext != null && ext.contains("GL_OES_EGL_image_external_essl3"));
            mark("GPU: render a superficie HDR PQ (EGL bt2020_pq)", eglExt != null && eglExt.contains("EGL_EXT_gl_colorspace_bt2020_pq"));
            mark("GPU: render a superficie HDR HLG (EGL bt2020_hlg)", eglExt != null && eglExt.contains("EGL_EXT_gl_colorspace_bt2020_hlg"));
        } catch (Throwable t) {
            g.put("error", t.toString());
        } finally {
            if (egl != null) {
                egl.releaseSurface(pb);
                egl.release();
            }
        }
        return g;
    }

    private JSONObject displaySection() throws Exception {
        JSONObject d = new JSONObject();
        Display display = context.getSystemService(WindowManager.class).getDefaultDisplay();
        d.put("wide_color_gamut", display.isWideColorGamut());
        d.put("hdr", display.isHdr());
        Display.HdrCapabilities hc = display.getHdrCapabilities();
        if (hc != null) {
            d.put("hdr_types", ints(hc.getSupportedHdrTypes()));
            d.put("max_luminance", hc.getDesiredMaxLuminance());
        }
        JSONArray modes = new JSONArray();
        for (Display.Mode m : display.getSupportedModes()) {
            modes.put(m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + "@" + m.getRefreshRate());
        }
        d.put("modes", modes);
        ColorSpace pref = display.getPreferredWideGamutColorSpace();
        d.put("preferred_wide_gamut", pref != null ? pref.getName() : null);
        return d;
    }

    private JSONObject audioSection() throws Exception {
        JSONObject a = new JSONObject();
        AudioManager am = context.getSystemService(AudioManager.class);
        JSONArray mics = new JSONArray();
        try {
            for (MicrophoneInfo m : am.getMicrophones()) {
                JSONObject o = new JSONObject();
                o.put("description", m.getDescription());
                o.put("location", m.getLocation());
                o.put("directionality", m.getDirectionality());
                mics.put(o);
            }
        } catch (Exception e) {
            a.put("microphones_error", e.toString());
        }
        a.put("microphones", mics);
        int maxChannels = 0;
        for (AudioDeviceInfo dev : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                for (int ch : dev.getChannelCounts()) {
                    maxChannels = Math.max(maxChannels, ch);
                }
            }
        }
        a.put("builtin_mic_max_channels", maxChannels);
        a.put("unprocessed_source", am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        mark("Audio: micrófonos integrados múltiples (" + mics.length() + ")", mics.length() >= 2);
        mark("Audio: captura estéreo del micrófono integrado", maxChannels >= 2 || mics.length() >= 2);
        return a;
    }

    private JSONObject thermalSection() throws Exception {
        JSONObject t = new JSONObject();
        PowerManager pm = context.getSystemService(PowerManager.class);
        t.put("status", Names.thermal(pm.getCurrentThermalStatus()));
        t.put("headroom_10s", pm.getThermalHeadroom(10));
        return t;
    }

    // ---------------------------------------------------------------- utilidades

    private void mark(String feature, boolean ok) {
        (ok ? confirmed : unavailable).add(feature);
    }

    private static boolean has(int[] arr, int v) {
        if (arr == null) return false;
        for (int x : arr) {
            if (x == v) return true;
        }
        return false;
    }

    private static boolean contains(Size[] sizes, Size s) {
        if (sizes == null) return false;
        for (Size x : sizes) {
            if (x.equals(s)) return true;
        }
        return false;
    }

    private static int[] orEmpty(int[] a) {
        return a != null ? a : new int[0];
    }

    private static JSONArray ints(int[] a) {
        JSONArray j = new JSONArray();
        if (a != null) {
            for (int v : a) j.put(v);
        }
        return j;
    }

    private static JSONArray floats(float[] a) throws Exception {
        JSONArray j = new JSONArray();
        if (a != null) {
            for (float v : a) j.put((double) v);
        }
        return j;
    }
}
