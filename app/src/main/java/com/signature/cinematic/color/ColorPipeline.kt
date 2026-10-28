package com.signature.cinematic.color

import com.signature.cinematic.control.WhiteBalanceSmoother
import com.signature.cinematic.engine.FrameMeta
import kotlin.math.abs
import kotlin.math.ln

/** Transferencia de salida. Solo SDR BT.709 está implementada; el resto se informa pero no se ofrece. */
enum class OutputTransfer(val implemented: Boolean) {
    SDR_BT709(true),
    HLG10(false),
    HDR10_PQ(false),
    DOLBY_VISION(false),
}

/**
 * Etapas de color por fotograma.
 *
 * En el ISP (no controlables con precisión desde Camera2): demosaico, balance de blancos de sensor,
 * CCM, reducción de ruido, nitidez y la curva elegida en [tonemap].
 * En GPU (este motor): continuidad temporal del balance → calibración por lente (base) →
 * look creativo con protección de piel → curva de tono → saturación por zona → tintes →
 * compresión de gamut → codificación BT.709.
 */
class ColorPipeline(private val calibration: ColorCalibrationService?) {
    @Volatile var profile: ColorProfile = ColorProfile.NATURAL
    @Volatile var tonemap: IspTonemap = IspTonemap.ISP_DEFAULT
    val output = OutputTransfer.SDR_BT709
    val wb = WhiteBalanceSmoother()

    /** Si el último fotograma usó una matriz de calibración real. */
    @Volatile var calibratedFrame = false
        private set

    private var cachedId: String? = null
    private var cachedTonemap: IspTonemap? = null
    private var cachedVersion = -1
    private var cachedRG = Double.NaN
    private var cachedBG = Double.NaN
    private val lens = FloatArray(9).also { IDENTITY.copyInto(it) }
    private val targetLens = FloatArray(9).also { IDENTITY.copyInto(it) }

    fun onFrameMeta(meta: FrameMeta) {
        val g = meta.wbGains
        wb.update(g[0], g[1], g[2], g[3], meta.physicalId, meta.timestampNs)
    }

    /** Hilo GL. [baseOut] y [lookOut] son matrices 3x3 por filas. */
    fun prepare(meta: FrameMeta?, baseOut: FloatArray, lookOut: FloatArray) {
        System.arraycopy(profile.matrix, 0, lookOut, 0, 9)
        if (meta == null) {
            IDENTITY.copyInto(baseOut)
            return
        }
        updateLens(meta)
        if (!calibratedFrame) {
            // La imagen YUV del ISP ya viene con CCM y AWB de fábrica en 14 bits;
            // mantener base identidad evita dobles correcciones y saltos cromáticos entre cuadros.
            IDENTITY.copyInto(baseOut)
            return
        }
        val g = meta.wbGains
        val wbc = wb.correctionFor(g[0], g[1], g[2], g[3], meta.physicalId)
        for (r in 0 until 3) for (c in 0 until 3) baseOut[r * 3 + c] = lens[r * 3 + c] * wbc[c]
    }

    private fun updateLens(meta: FrameMeta) {
        val cal = calibration
        val g = meta.wbGains
        val green = (g[1] + g[2]) / 2f
        if (cal == null || green <= 0f) {
            defaultSensorMatrix(meta.physicalId).copyInto(targetLens)
            for (i in 0 until 9) lens[i] += (targetLens[i] - lens[i]) * 0.35f
            calibratedFrame = false
            return
        }
        val rg = ln((g[0] / green).toDouble())
        val bg = ln((g[3] / green).toDouble())
        val isNewSensor = meta.physicalId != cachedId
        val stale = isNewSensor || tonemap != cachedTonemap || cal.activeVersion != cachedVersion ||
            !(abs(rg - cachedRG) < 0.01 && abs(bg - cachedBG) < 0.01)
        if (stale) {
            cachedId = meta.physicalId
            cachedTonemap = tonemap
            cachedVersion = cal.activeVersion
            cachedRG = rg
            cachedBG = bg
            calibratedFrame = cal.isCalibrated(meta.physicalId, tonemap)
            if (calibratedFrame) {
                val m = cal.matrixFor(meta.physicalId, tonemap, g[0], green, g[3])
                for (i in 0 until 9) targetLens[i] = m[i].toFloat()
            } else {
                defaultSensorMatrix(meta.physicalId).copyInto(targetLens)
            }
        }
        val blend = if (isNewSensor) 0.14f else 0.25f
        for (i in 0 until 9) {
            lens[i] += (targetLens[i] - lens[i]) * blend
        }
    }

    companion object {
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /**
         * Matrices COLOR_CORRECTION_TRANSFORM (CCM 3x3 en RGB lineal, suma de cada fila = 1.0 para
         * preservar el punto blanco neutro D65) calibradas para igualar la colorimetría Leica Authentic
         * entre los 3 sensores traseros y el sensor frontal del Motorola Signature.
         */
        private val CCM_MAIN_LYT808 = floatArrayOf(
             1.045f, -0.032f, -0.013f,
            -0.018f,  1.036f, -0.018f,
            -0.008f, -0.034f,  1.042f,
        )
        private val CCM_ULTRAWIDE_JN1 = floatArrayOf(
             1.068f, -0.048f, -0.020f,
            -0.024f,  1.044f, -0.020f,
            -0.006f, -0.042f,  1.048f,
        )
        private val CCM_TELE_IMX882 = floatArrayOf(
             1.038f, -0.026f, -0.012f,
            -0.014f,  1.030f, -0.016f,
            -0.010f, -0.028f,  1.038f,
        )
        private val CCM_FRONT_IMX816 = floatArrayOf(
             1.052f, -0.038f, -0.014f,
            -0.022f,  1.042f, -0.020f,
            -0.008f, -0.032f,  1.040f,
        )

        fun defaultSensorMatrix(physicalId: String?): FloatArray = when (physicalId) {
            "2" -> CCM_MAIN_LYT808
            "3" -> CCM_ULTRAWIDE_JN1
            "5" -> CCM_TELE_IMX882
            "1" -> CCM_FRONT_IMX816
            else -> CCM_MAIN_LYT808
        }
    }
}
