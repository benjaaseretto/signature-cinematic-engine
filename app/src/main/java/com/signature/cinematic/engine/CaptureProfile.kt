package com.signature.cinematic.engine

import com.signature.cinematic.color.IspTonemap
import com.signature.cinematic.control.ExposureController

/** Nivel de procesamiento pedido al ISP; se baja a FAST si el dispositivo no ofrece el nivel o hay calor. */
enum class Processing { OFF, MINIMAL, FAST, HIGH_QUALITY }

enum class StabilizationLevel { OFF, STANDARD, ENHANCED }

/**
 * Perfil de captura: modo de sensor, obturación, límites de ISO, procesamiento del ISP y look por defecto.
 * [shutterFraction] es la fracción de la duración de cuadro (0.5 = 180°). El controlador alarga el
 * obturador hasta la duración de cuadro antes de subexponer cuando se alcanza [isoCap].
 */
class CaptureProfile(
    val id: String,
    val name: String,
    /** Modos aceptables en orden de preferencia: {ancho, alto, fps}. */
    val modes: List<IntArray>,
    val shutterFraction: Float,
    val isoCap: Int,
    val noiseReduction: Processing,
    val edge: Processing,
    val stabilization: StabilizationLevel,
    val exposureMode: ExposureController.Mode,
    val colorProfileId: String,
    val tonemap: IspTonemap,
    /** El perfil necesita una sesión que el motor todavía no implementa. */
    val implemented: Boolean,
    val description: String,
) {
    /** Primer modo del perfil que el dispositivo admite, o null. */
    fun resolve(available: List<VideoMode>): VideoMode? {
        if (!implemented) return null
        for (m in modes) {
            available.firstOrNull { it.width == m[0] && it.height == m[1] && it.fps == m[2] }?.let { return it }
        }
        return null
    }

    companion object {
        private fun m(w: Int, h: Int, fps: Int) = intArrayOf(w, h, fps)

        @JvmField
        val A_NATURAL = CaptureProfile(
            "A", "A · Natural 4K30",
            listOf(m(3840, 2160, 30), m(1920, 1080, 30)),
            shutterFraction = 0.5f, isoCap = 3200,
            noiseReduction = Processing.HIGH_QUALITY, edge = Processing.FAST,
            stabilization = StabilizationLevel.STANDARD,
            exposureMode = ExposureController.Mode.CINEMATIC,
            colorProfileId = "pro", tonemap = IspTonemap.ISP_DEFAULT, implemented = true,
            description = "Obturador 1/60 (180°), piel calibrada, estabilización y balance suave.",
        )

        @JvmField
        val B_ACTION = CaptureProfile(
            "B", "B · Acción 4K60",
            listOf(m(3840, 2160, 60), m(1920, 1080, 60)),
            shutterFraction = 0.5f, isoCap = 6400,
            noiseReduction = Processing.FAST, edge = Processing.FAST,
            stabilization = StabilizationLevel.ENHANCED,
            exposureMode = ExposureController.Mode.AUTO,
            colorProfileId = "natural", tonemap = IspTonemap.ISP_DEFAULT, implemented = true,
            description = "1/120 con buena luz; con poca luz se alarga hasta 1/60 antes de subexponer.",
        )

        @JvmField
        val C_CINEMATIC = CaptureProfile(
            "C", "C · Cinematográfico 24p",
            listOf(m(3840, 2160, 24), m(3840, 2160, 30), m(1920, 1080, 24), m(1920, 1080, 30)),
            shutterFraction = 0.5f, isoCap = 3200,
            noiseReduction = Processing.HIGH_QUALITY, edge = Processing.OFF,
            stabilization = StabilizationLevel.STANDARD,
            exposureMode = ExposureController.Mode.CINEMATIC,
            colorProfileId = "flat", tonemap = IspTonemap.SRGB, implemented = true,
            description = "1/48 (24p) o 1/60 (30p), balance bloqueable, curva sRGB del ISP, nitidez reducida.",
        )

        @JvmField
        val D_NIGHT = CaptureProfile(
            "D", "D · Nocturno",
            listOf(m(3840, 2160, 30), m(1920, 1080, 30)),
            shutterFraction = 1.0f, isoCap = 6400,
            noiseReduction = Processing.HIGH_QUALITY, edge = Processing.FAST,
            stabilization = StabilizationLevel.STANDARD,
            exposureMode = ExposureController.Mode.CINEMATIC,
            colorProfileId = "natural", tonemap = IspTonemap.ISP_DEFAULT, implemented = true,
            description = "Obturación hasta la duración de cuadro, ISO limitado, NR del ISP en alta calidad. " +
                "La reducción temporal propia está pendiente.",
        )

        @JvmField
        val E_DETAIL = CaptureProfile(
            "E", "E · Máximo detalle 8K30",
            listOf(m(7680, 4320, 30)),
            shutterFraction = 0.5f, isoCap = 1600,
            noiseReduction = Processing.MINIMAL, edge = Processing.FAST,
            stabilization = StabilizationLevel.OFF,
            exposureMode = ExposureController.Mode.CINEMATIC,
            colorProfileId = "natural", tonemap = IspTonemap.SRGB, implemented = true,
            description = "Procesamiento mínimo; se vigila la temperatura y se avisa si el sistema limita.",
        )

        @JvmField
        val F_SLOW_MOTION = CaptureProfile(
            "F", "F · Cámara lenta",
            listOf(m(3840, 2160, 120), m(1920, 1080, 120), m(1920, 1080, 240)),
            shutterFraction = 0.5f, isoCap = 6400,
            noiseReduction = Processing.FAST, edge = Processing.FAST,
            stabilization = StabilizationLevel.STANDARD,
            exposureMode = ExposureController.Mode.AUTO,
            colorProfileId = "natural", tonemap = IspTonemap.ISP_DEFAULT, implemented = false,
            description = "Requiere sesión de alta velocidad (createConstrainedHighSpeedCaptureSession): " +
                "no implementada. El escáner informa si el hardware la admite.",
        )

        @JvmField
        val ALL = listOf(A_NATURAL, B_ACTION, C_CINEMATIC, D_NIGHT, E_DETAIL, F_SLOW_MOTION)

        @JvmStatic
        fun selectable(available: List<VideoMode>) = ALL.filter { it.resolve(available) != null }

        @JvmStatic
        fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: A_NATURAL
    }
}
