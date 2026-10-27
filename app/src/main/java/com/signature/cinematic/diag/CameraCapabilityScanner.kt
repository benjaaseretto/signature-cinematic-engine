package com.signature.cinematic.diag

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import com.signature.cinematic.engine.CaptureProfile
import com.signature.cinematic.engine.VideoMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tabla de compatibilidad cámara × resolución × fps × rango dinámico × formato × estabilización ×
 * codificador × orientación. La parte de cámara se valida con CameraDeviceSetup (sin abrir la cámara);
 * la del codificador con MediaCodecInfo. Nada se marca como admitido sin una de esas dos confirmaciones.
 */
class CameraCapabilityScanner(context: Context) {
    private val cameras: CameraManager = context.getSystemService(CameraManager::class.java)!!

    enum class Status(val label: String) {
        CONFIRMED("CONFIRMADO"),
        CAMERA_ONLY("SOLO CÁMARA"),
        ENCODER_ONLY("SOLO CODIFICADOR"),
        UNVERIFIED("NO VERIFICABLE"),
        NO("NO"),
    }

    class Row(
        val camera: String,
        val physicalOf: String?,
        val facing: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val dynamicRange: String,
        val format: String,
        val stabilization: String,
        val session: String,
        val cameraOk: Boolean?,
        val cameraNote: String,
        val encoder: String,
        val orientation: String,
        val encoderOk: Boolean,
        val zoomRange: String?,
    ) {
        val status: Status
            get() = when {
                cameraOk == true && encoderOk -> Status.CONFIRMED
                cameraOk == null && encoderOk -> Status.UNVERIFIED
                cameraOk == true -> Status.CAMERA_ONLY
                cameraOk == false && encoderOk -> Status.ENCODER_ONLY
                else -> Status.NO
            }
    }

    private class Encoder(val label: String, val mime: String, val tenBit: Boolean, val info: MediaCodecInfo)

    private class CameraCheck(val ok: Boolean?, val note: String, val zoom: String?)

    private val encoders: List<Encoder> by lazy { listEncoders() }

    fun scan(): List<Row> {
        val rows = mutableListOf<Row>()
        for (id in cameras.cameraIdList) {
            val cc = cameras.getCameraCharacteristics(id)
            scanCamera(id, null, id, cc, rows)
            for (pid in cc.physicalCameraIds) {
                scanCamera(pid, id, id, cameras.getCameraCharacteristics(pid), rows)
            }
        }
        return rows
    }

    private fun scanCamera(id: String, physicalOf: String?, logicalId: String, cc: CameraCharacteristics, rows: MutableList<Row>) {
        val map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val facing = Names.facing(cc.get(CameraCharacteristics.LENS_FACING))
        val fpsRanges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val highSpeed = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        val drProfiles = buildList {
            add(DynamicRangeProfiles.STANDARD)
            cc.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
                ?.filter { it != DynamicRangeProfiles.STANDARD }?.sorted()?.let { addAll(it) }
        }
        val stabs = if (physicalOf != null) intArrayOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        else cc.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.takeIf { it.isNotEmpty() } ?: intArrayOf(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        val setup = if (cameras.isCameraDeviceSetupSupported(logicalId)) cameras.getCameraDeviceSetup(logicalId) else null

        for (res in RESOLUTIONS) {
            val size = Size(res[0], res[1])
            for (fps in FPS) {
                val hs = fps >= 120
                if (hs && !(highSpeed && highSpeedOk(map, size, fps))) continue
                for (dr in drProfiles) {
                    if (hs && dr != DynamicRangeProfiles.STANDARD) continue
                    val tenBit = dr != DynamicRangeProfiles.STANDARD
                    for (format in formatsFor(tenBit, hs)) {
                        if (!hs && !regularOk(map, format, size, fps, fpsRanges)) continue
                        for (stab in stabs) {
                            val check = checkSession(setup, physicalOf?.let { id }, format, size, fps, dr, stab, hs)
                            val dolby = Names.dynamicRange(dr).startsWith("DOLBY")
                            val encs = encoders.filter { it.tenBit == tenBit && dolby == (it.mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) }
                                .map { it.label }.distinct().ifEmpty { listOf("ninguno") }
                            for (enc in encs) for (portrait in booleanArrayOf(false, true)) {
                                val w = if (portrait) res[1] else res[0]
                                val h = if (portrait) res[0] else res[1]
                                val encOk = encoders.any { it.label == enc && it.tenBit == tenBit && supports(it, w, h, fps) }
                                rows += Row(
                                    camera = id, physicalOf = physicalOf, facing = facing,
                                    width = res[0], height = res[1], fps = fps,
                                    dynamicRange = Names.dynamicRange(dr), format = formatName(format),
                                    stabilization = Names.stabilization(stab),
                                    session = if (hs) "ALTA_VELOCIDAD" else if (physicalOf != null) "FÍSICA" else "REGULAR",
                                    cameraOk = check.ok, cameraNote = check.note,
                                    encoder = enc, orientation = if (portrait) "vertical" else "horizontal",
                                    encoderOk = encOk, zoomRange = check.zoom,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatsFor(tenBit: Boolean, highSpeed: Boolean) = when {
        highSpeed -> intArrayOf(ImageFormat.PRIVATE)
        tenBit -> intArrayOf(ImageFormat.PRIVATE, ImageFormat.YCBCR_P010)
        else -> intArrayOf(ImageFormat.PRIVATE, ImageFormat.YUV_420_888)
    }

    private fun formatName(f: Int) = when (f) {
        ImageFormat.PRIVATE -> "PRIVATE (GPU/codificador)"
        else -> Names.format(f)
    }

    private fun sizesFor(map: StreamConfigurationMap, format: Int): Array<Size> =
        (if (format == ImageFormat.PRIVATE) map.getOutputSizes(SurfaceTexture::class.java) else map.getOutputSizes(format))
            ?: emptyArray()

    private fun regularOk(map: StreamConfigurationMap, format: Int, size: Size, fps: Int, ranges: Array<out Range<Int>>): Boolean {
        if (size !in sizesFor(map, format)) return false
        val minDur = if (format == ImageFormat.PRIVATE) map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        else map.getOutputMinFrameDuration(format, size)
        if (minDur <= 0 || 1e9 / minDur < fps - 0.5) return false
        return ranges.any { it.upper == fps }
    }

    private fun highSpeedOk(map: StreamConfigurationMap, size: Size, fps: Int): Boolean = runCatching {
        size in map.highSpeedVideoSizes && map.getHighSpeedVideoFpsRangesFor(size).any { it.upper == fps }
    }.getOrDefault(false)

    private val sessionCache = HashMap<String, CameraCheck>()

    private fun checkSession(
        setup: CameraDevice.CameraDeviceSetup?, physicalId: String?, format: Int, size: Size, fps: Int,
        dr: Long, stab: Int, highSpeed: Boolean,
    ): CameraCheck {
        if (setup == null) return CameraCheck(null, "el HAL no expone CameraDeviceSetup", null)
        val key = "${setup.id}/$physicalId/$format/$size/$fps/$dr/$stab/$highSpeed"
        return sessionCache.getOrPut(key) {
            val withUseCase = !highSpeed && physicalId == null
            runCatching { trySession(setup, physicalId, format, size, fps, dr, stab, highSpeed, withUseCase) }
                .recoverCatching { first ->
                    if (!withUseCase) throw first
                    trySession(setup, physicalId, format, size, fps, dr, stab, highSpeed, false)
                }
                .getOrElse { t -> CameraCheck(null, "${t.javaClass.simpleName}: ${t.message}", null) }
        }
    }

    private fun trySession(
        setup: CameraDevice.CameraDeviceSetup, physicalId: String?, format: Int, size: Size, fps: Int,
        dr: Long, stab: Int, highSpeed: Boolean, videoUseCase: Boolean,
    ): CameraCheck {
        val oc = if (format == ImageFormat.PRIVATE) OutputConfiguration(size, SurfaceTexture::class.java)
        else OutputConfiguration(format, size)
        oc.dynamicRangeProfile = dr
        if (physicalId != null) oc.setPhysicalCameraId(physicalId)
        if (videoUseCase) oc.streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        val type = if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        val sc = SessionConfiguration(type, listOf(oc))
        val b = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(if (highSpeed) 30.coerceAtMost(fps) else fps, fps))
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stab)
        sc.setSessionParameters(b.build())
        val ok = setup.isSessionConfigurationSupported(sc)
        val zoom = if (ok && physicalId == null) runCatching {
            setup.getSessionCharacteristics(sc).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.toString()
        }.getOrNull() else null
        val note = when {
            !ok -> "rechazada por el HAL"
            !videoUseCase && !highSpeed && physicalId == null -> "sin stream use case VIDEO_RECORD"
            else -> ""
        }
        return CameraCheck(ok, note, zoom)
    }

    private fun listEncoders(): List<Encoder> {
        val out = mutableListOf<Encoder>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || !info.isHardwareAccelerated) continue
            for (type in info.supportedTypes) {
                val pl = runCatching { info.getCapabilitiesForType(type).profileLevels }.getOrNull() ?: continue
                when (type.lowercase()) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC -> {
                        out += Encoder("HEVC 8 bits", type, false, info)
                        if (pl.any { it.profile == CodecProfileLevel.HEVCProfileMain10 || it.profile == CodecProfileLevel.HEVCProfileMain10HDR10 }) {
                            out += Encoder("HEVC Main10", type, true, info)
                        }
                    }
                    MediaFormat.MIMETYPE_VIDEO_AVC -> out += Encoder("AVC 8 bits", type, false, info)
                    MediaFormat.MIMETYPE_VIDEO_AV1 -> {
                        out += Encoder("AV1 8 bits", type, false, info)
                        if (pl.any { it.profile == CodecProfileLevel.AV1ProfileMain10 || it.profile == CodecProfileLevel.AV1ProfileMain10HDR10 }) {
                            out += Encoder("AV1 Main10", type, true, info)
                        }
                    }
                    MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> out += Encoder("Dolby Vision", type, true, info)
                }
            }
        }
        return out
    }

    private fun supports(e: Encoder, w: Int, h: Int, fps: Int): Boolean = runCatching {
        e.info.getCapabilitiesForType(e.mime).videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) == true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- salidas

    fun toCsv(rows: List<Row>): String = buildString {
        appendLine("camara,fisica_de,orientacion_lente,resolucion,fps,rango_dinamico,formato,estabilizacion,sesion," +
            "camara_ok,nota_camara,codificador,orientacion,codificador_ok,estado,zoom")
        for (r in rows) {
            appendLine(listOf(r.camera, r.physicalOf ?: "", r.facing, "${r.width}x${r.height}", r.fps, r.dynamicRange,
                r.format, r.stabilization, r.session, r.cameraOk?.toString() ?: "?", r.cameraNote, r.encoder,
                r.orientation, r.encoderOk, r.status.label, r.zoomRange ?: "").joinToString(",") { csv(it.toString()) })
        }
    }

    fun toJson(rows: List<Row>): JSONArray = JSONArray(rows.map { r ->
        JSONObject()
            .put("camera", r.camera).put("physical_of", r.physicalOf ?: JSONObject.NULL).put("facing", r.facing)
            .put("width", r.width).put("height", r.height).put("fps", r.fps)
            .put("dynamic_range", r.dynamicRange).put("format", r.format).put("stabilization", r.stabilization)
            .put("session", r.session).put("camera_ok", r.cameraOk ?: JSONObject.NULL).put("camera_note", r.cameraNote)
            .put("encoder", r.encoder).put("orientation", r.orientation).put("encoder_ok", r.encoderOk)
            .put("status", r.status.label).put("zoom_range", r.zoomRange ?: JSONObject.NULL)
    })

    /** Qué perfiles A–F se pueden usar en la cámara trasera principal, según la tabla. */
    fun profileSummary(rows: List<Row>, backLogicalId: String?): String = buildString {
        appendLine("## Perfiles de captura (cámara $backLogicalId, SDR, PRIVATE, HEVC 8 bits)")
        for (p in CaptureProfile.ALL) {
            val found = p.modes.firstNotNullOfOrNull { m ->
                val match = rows.filter {
                    it.camera == backLogicalId && it.physicalOf == null && it.width == m[0] && it.height == m[1] &&
                        it.fps == m[2] && it.dynamicRange == "STANDARD" && it.format.startsWith("PRIVATE") &&
                        it.encoder == "HEVC 8 bits"
                }
                when {
                    match.any { it.status == Status.CONFIRMED } -> "${m[0]}x${m[1]}@${m[2]} confirmado"
                    match.any { it.status == Status.UNVERIFIED } -> "${m[0]}x${m[1]}@${m[2]} declarado, sin verificar"
                    else -> null
                }
            }
            val state = when {
                !p.implemented -> "no implementado en el motor" + (found?.let { " (hardware: $it)" } ?: "")
                found != null -> found
                else -> "no disponible"
            }
            appendLine("- ${p.name}: $state")
        }
    }

    fun statusCounts(rows: List<Row>): String =
        Status.values().joinToString(" · ") { s -> "${s.label}: ${rows.count { it.status == s }}" }

    companion object {
        private val RESOLUTIONS = listOf(intArrayOf(7680, 4320), intArrayOf(3840, 2160), intArrayOf(1920, 1080), intArrayOf(1280, 720))
        private val FPS = intArrayOf(24, 30, 60, 120, 240)

        private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** Modos que la tabla confirma para una cámara, en el formato que usa el motor. */
        @JvmStatic
        fun confirmedModes(rows: List<Row>, cameraId: String): List<VideoMode> = rows
            .filter { it.camera == cameraId && it.physicalOf == null && it.status == Status.CONFIRMED &&
                it.dynamicRange == "STANDARD" && it.format.startsWith("PRIVATE") && it.session == "REGULAR" }
            .groupBy { Triple(it.width, it.height, it.fps) }
            .map { (k, v) -> VideoMode(k.first, k.second, k.third, k.third, v.any { it.orientation == "vertical" }) }
    }
}
