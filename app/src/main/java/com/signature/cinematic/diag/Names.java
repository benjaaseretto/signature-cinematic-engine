package com.signature.cinematic.diag;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.DynamicRangeProfiles;
import android.media.MediaCodecInfo.CodecProfileLevel;

/** Nombres legibles para constantes de Camera2 y MediaCodec. */
final class Names {
    private Names() {}

    static String capability(int c) {
        switch (c) {
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE: return "BACKWARD_COMPATIBLE";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR: return "MANUAL_SENSOR";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING: return "MANUAL_POST_PROCESSING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW: return "RAW";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING: return "PRIVATE_REPROCESSING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS: return "READ_SENSOR_SETTINGS";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE: return "BURST_CAPTURE";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING: return "YUV_REPROCESSING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT: return "DEPTH_OUTPUT";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO: return "CONSTRAINED_HIGH_SPEED_VIDEO";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING: return "MOTION_TRACKING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA: return "LOGICAL_MULTI_CAMERA";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME: return "MONOCHROME";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA: return "SECURE_IMAGE_DATA";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA: return "SYSTEM_CAMERA";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING: return "OFFLINE_PROCESSING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR: return "ULTRA_HIGH_RESOLUTION_SENSOR";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING: return "REMOSAIC_REPROCESSING";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT: return "DYNAMIC_RANGE_TEN_BIT";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE: return "STREAM_USE_CASE";
            case CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES: return "COLOR_SPACE_PROFILES";
            default: return "CAP_" + c;
        }
    }

    static String hardwareLevel(Integer l) {
        if (l == null) return "?";
        switch (l) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
            default: return "LEVEL_" + l;
        }
    }

    static String facing(Integer f) {
        if (f == null) return "?";
        switch (f) {
            case CameraCharacteristics.LENS_FACING_BACK: return "trasera";
            case CameraCharacteristics.LENS_FACING_FRONT: return "frontal";
            default: return "externa";
        }
    }

    static String dynamicRange(long p) {
        if (p == DynamicRangeProfiles.STANDARD) return "STANDARD";
        if (p == DynamicRangeProfiles.HLG10) return "HLG10";
        if (p == DynamicRangeProfiles.HDR10) return "HDR10";
        if (p == DynamicRangeProfiles.HDR10_PLUS) return "HDR10_PLUS";
        if (p == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF) return "DOLBY_VISION_10B_HDR_REF";
        if (p == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_REF_PO) return "DOLBY_VISION_10B_HDR_REF_PO";
        if (p == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM) return "DOLBY_VISION_10B_HDR_OEM";
        if (p == DynamicRangeProfiles.DOLBY_VISION_10B_HDR_OEM_PO) return "DOLBY_VISION_10B_HDR_OEM_PO";
        if (p == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF) return "DOLBY_VISION_8B_HDR_REF";
        if (p == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_REF_PO) return "DOLBY_VISION_8B_HDR_REF_PO";
        if (p == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM) return "DOLBY_VISION_8B_HDR_OEM";
        if (p == DynamicRangeProfiles.DOLBY_VISION_8B_HDR_OEM_PO) return "DOLBY_VISION_8B_HDR_OEM_PO";
        return "DR_" + p;
    }

    static String format(int f) {
        switch (f) {
            case ImageFormat.YUV_420_888: return "YUV_420_888";
            case ImageFormat.PRIVATE: return "PRIVATE";
            case ImageFormat.JPEG: return "JPEG";
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR";
            case ImageFormat.RAW10: return "RAW10";
            case ImageFormat.RAW12: return "RAW12";
            case ImageFormat.RAW_PRIVATE: return "RAW_PRIVATE";
            case ImageFormat.YCBCR_P010: return "YCBCR_P010";
            case ImageFormat.HEIC: return "HEIC";
            case ImageFormat.DEPTH16: return "DEPTH16";
            case ImageFormat.DEPTH_JPEG: return "DEPTH_JPEG";
            case ImageFormat.JPEG_R: return "JPEG_R";
            case ImageFormat.Y8: return "Y8";
            case ImageFormat.NV21: return "NV21";
            default: return String.format("0x%x", f);
        }
    }

    static String stabilization(int m) {
        switch (m) {
            case CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF: return "OFF";
            case CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON: return "ON";
            case CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION: return "PREVIEW_STABILIZATION";
            default: return "VS_" + m;
        }
    }

    static String processingMode(int m) {
        switch (m) {
            case 0: return "OFF";
            case 1: return "FAST";
            case 2: return "HIGH_QUALITY";
            case 3: return "MINIMAL/ZSL";
            case 4: return "ZERO_SHUTTER_LAG";
            default: return "M_" + m;
        }
    }

    static String tonemapMode(int m) {
        switch (m) {
            case CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE: return "CONTRAST_CURVE";
            case CameraMetadata.TONEMAP_MODE_FAST: return "FAST";
            case CameraMetadata.TONEMAP_MODE_HIGH_QUALITY: return "HIGH_QUALITY";
            case CameraMetadata.TONEMAP_MODE_GAMMA_VALUE: return "GAMMA_VALUE";
            case CameraMetadata.TONEMAP_MODE_PRESET_CURVE: return "PRESET_CURVE";
            default: return "T_" + m;
        }
    }

    static String aeMode(int m) {
        switch (m) {
            case CameraMetadata.CONTROL_AE_MODE_OFF: return "OFF";
            case CameraMetadata.CONTROL_AE_MODE_ON: return "ON";
            case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH: return "ON_AUTO_FLASH";
            case CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH: return "ON_ALWAYS_FLASH";
            case CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE: return "ON_AUTO_FLASH_REDEYE";
            case CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH: return "ON_EXTERNAL_FLASH";
            case CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY: return "ON_LOW_LIGHT_BOOST";
            default: return "AE_" + m;
        }
    }

    static String afMode(int m) {
        switch (m) {
            case CameraMetadata.CONTROL_AF_MODE_OFF: return "OFF";
            case CameraMetadata.CONTROL_AF_MODE_AUTO: return "AUTO";
            case CameraMetadata.CONTROL_AF_MODE_MACRO: return "MACRO";
            case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO: return "CONTINUOUS_VIDEO";
            case CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE: return "CONTINUOUS_PICTURE";
            case CameraMetadata.CONTROL_AF_MODE_EDOF: return "EDOF";
            default: return "AF_" + m;
        }
    }

    static String streamUseCase(long u) {
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_DEFAULT) return "DEFAULT";
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW) return "PREVIEW";
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_STILL_CAPTURE) return "STILL_CAPTURE";
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD) return "VIDEO_RECORD";
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL) return "PREVIEW_VIDEO_STILL";
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL) return "VIDEO_CALL";
        if (u == CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_CROPPED_RAW) return "CROPPED_RAW";
        return "UC_" + u;
    }

    static String hevcProfile(int p) {
        switch (p) {
            case CodecProfileLevel.HEVCProfileMain: return "Main";
            case CodecProfileLevel.HEVCProfileMain10: return "Main10";
            case CodecProfileLevel.HEVCProfileMainStill: return "MainStill";
            case CodecProfileLevel.HEVCProfileMain10HDR10: return "Main10HDR10";
            case CodecProfileLevel.HEVCProfileMain10HDR10Plus: return "Main10HDR10Plus";
            default: return "HEVC_" + p;
        }
    }

    static String avcProfile(int p) {
        switch (p) {
            case CodecProfileLevel.AVCProfileBaseline: return "Baseline";
            case CodecProfileLevel.AVCProfileMain: return "Main";
            case CodecProfileLevel.AVCProfileHigh: return "High";
            case CodecProfileLevel.AVCProfileHigh10: return "High10";
            case CodecProfileLevel.AVCProfileConstrainedBaseline: return "ConstrainedBaseline";
            case CodecProfileLevel.AVCProfileConstrainedHigh: return "ConstrainedHigh";
            default: return "AVC_" + p;
        }
    }

    static String dolbyProfile(int p) {
        switch (p) {
            case CodecProfileLevel.DolbyVisionProfileDvheDtr: return "dvhe.04";
            case CodecProfileLevel.DolbyVisionProfileDvheSt: return "dvhe.05";
            case CodecProfileLevel.DolbyVisionProfileDvheStn: return "dvhe.07";
            case CodecProfileLevel.DolbyVisionProfileDvheDth: return "dvhe.08";
            case CodecProfileLevel.DolbyVisionProfileDvav110: return "dvav1.10";
            default: return "DV_" + p;
        }
    }

    static String av1Profile(int p) {
        switch (p) {
            case CodecProfileLevel.AV1ProfileMain8: return "Main8";
            case CodecProfileLevel.AV1ProfileMain10: return "Main10";
            case CodecProfileLevel.AV1ProfileMain10HDR10: return "Main10HDR10";
            case CodecProfileLevel.AV1ProfileMain10HDR10Plus: return "Main10HDR10Plus";
            default: return "AV1_" + p;
        }
    }

    static String thermal(int s) {
        switch (s) {
            case 0: return "NONE";
            case 1: return "LIGHT";
            case 2: return "MODERATE";
            case 3: return "SEVERE";
            case 4: return "CRITICAL";
            case 5: return "EMERGENCY";
            case 6: return "SHUTDOWN";
            default: return "T_" + s;
        }
    }
}
