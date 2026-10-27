package com.signature.cinematic.color

import com.signature.cinematic.color.ColorScience.Lab
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

enum class Illuminant(val label: String) {
    DAYLIGHT("Luz día (≈5500–6500 K)"),
    WARM("Luz cálida (≈2700–3200 K)"),
    LED("LED (≈4000–5000 K)"),
}

/** Curva de tono del ISP con la que se capturó: la calibración solo vale para la misma curva. */
enum class IspTonemap { SRGB, ISP_DEFAULT }

/** Medición de la carta en una toma: promedio de varios fotogramas, RGB lineal (salida del ISP decodificada). */
class ChartCapture(
    val physicalId: String,
    val illuminant: Illuminant,
    val tonemap: IspTonemap,
    /** 24 parches, RGB lineal. */
    val patches: List<DoubleArray>,
    /** Parches con algún canal recortado (≥ 0.98 codificado): se excluyen del ajuste. */
    val clipped: BooleanArray,
    /** RGB lineal del neutro 5 en cada fotograma, para la estabilidad temporal. */
    val neutralPerFrame: List<DoubleArray>,
    /** Ganancias AWB del ISP (R, G, B) promediadas. */
    val wbGains: DoubleArray,
    val exposureNs: Long,
    val iso: Int,
    val conditions: String,
) {
    val logRG get() = ln(wbGains[0] / wbGains[1])
    val logBG get() = ln(wbGains[2] / wbGains[1])
}

class ErrorStats(
    val mean: Double,
    val p95: Double,
    val max: Double,
    val skin: Double,
    val gray: Double,
    val perPatch: DoubleArray,
) {
    fun summary() = "ΔE00 medio %.2f · p95 %.2f · máx %.2f · piel %.2f · grises %.2f".format(mean, p95, max, skin, gray)
}

class FitResult(val matrix: DoubleArray, val before: ErrorStats, val after: ErrorStats, val correctedLab: List<Lab>)

object CalibrationMath {
    /** Piel ×3 y neutros ×2: lo que más se nota si sale mal. */
    fun weights(clipped: BooleanArray? = null) = DoubleArray(ColorChecker.PATCH_COUNT) { i ->
        when {
            clipped != null && clipped[i] -> 0.0
            i in ColorChecker.SKIN -> 3.0
            i in ColorChecker.NEUTRALS -> 2.0
            else -> 1.0
        }
    }

    fun targetLinear(ref: ColorChecker) = ref.patches.map { ColorScience.labToLinearSrgb(it) }

    /** Escala la medición para que el neutro 8 tenga la luminancia de la referencia (la exposición no es color). */
    fun exposureScale(measured: List<DoubleArray>, ref: ColorChecker): Double {
        val refY = ColorScience.luminance(ColorScience.labToLinearSrgb(ref.patches[ColorChecker.EXPOSURE_PATCH]))
        val y = ColorScience.luminance(measured[ColorChecker.EXPOSURE_PATCH])
        return if (y > 1e-6) refY / y else 1.0
    }

    fun correctedLab(matrix: DoubleArray, measured: List<DoubleArray>, ref: ColorChecker): List<Lab> {
        val corrected = measured.map { ColorScience.mul(matrix, it) }
        val k = exposureScale(corrected, ref)
        return corrected.map { c -> ColorScience.linearSrgbToLab(DoubleArray(3) { c[it] * k }) }
    }

    fun evaluate(matrix: DoubleArray, measured: List<DoubleArray>, ref: ColorChecker): ErrorStats =
        stats(correctedLab(matrix, measured, ref).mapIndexed { i, lab -> ColorScience.deltaE2000(ref.patches[i], lab) })

    fun stats(de: List<Double>): ErrorStats {
        val sorted = de.sorted()
        val p95 = sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
        return ErrorStats(
            mean = de.average(),
            p95 = p95,
            max = sorted.last(),
            skin = ColorChecker.SKIN.map { de[it] }.average(),
            gray = ColorChecker.NEUTRALS.map { de[it] }.average(),
            perPatch = de.toDoubleArray(),
        )
    }

    fun fit(capture: ChartCapture, ref: ColorChecker): FitResult {
        val k = exposureScale(capture.patches, ref)
        val measured = capture.patches.map { p -> DoubleArray(3) { p[it] * k } }
        val matrix = ColorScience.fitMatrix(measured, targetLinear(ref), weights(capture.clipped))
        return FitResult(
            matrix = matrix,
            before = evaluate(ColorScience.identity(), capture.patches, ref),
            after = evaluate(matrix, capture.patches, ref),
            correctedLab = correctedLab(matrix, capture.patches, ref),
        )
    }

    /** Dispersión temporal: RMS del ΔE00 de cada fotograma del neutro 5 respecto de su color medio. */
    fun temporalStd(neutralPerFrame: List<DoubleArray>): Double {
        if (neutralPerFrame.size < 2) return 0.0
        val mean = DoubleArray(3) { c -> neutralPerFrame.sumOf { it[c] } / neutralPerFrame.size }
        val meanLab = ColorScience.linearSrgbToLab(mean)
        return sqrt(neutralPerFrame.sumOf {
            val d = ColorScience.deltaE2000(meanLab, ColorScience.linearSrgbToLab(it))
            d * d
        } / neutralPerFrame.size)
    }

    /** ΔE00 medio entre las versiones corregidas de la misma carta vista por dos lentes. */
    fun interLensDeltaE(a: List<Lab>, b: List<Lab>): Double =
        a.indices.map { ColorScience.deltaE2000(a[it], b[it]) }.average()

    /**
     * Matriz para las ganancias AWB actuales: la calibración del iluminante más cercano, o una mezcla
     * de las dos más cercanas (pesos por inversa del cuadrado de la distancia en log-cromaticidad).
     */
    fun interpolate(points: List<Pair<DoubleArray, DoubleArray>>, logRG: Double, logBG: Double): DoubleArray {
        if (points.isEmpty()) return ColorScience.identity()
        if (points.size == 1) return points[0].second
        val byDist = points.map { (wb, m) ->
            val d = (wb[0] - logRG) * (wb[0] - logRG) + (wb[1] - logBG) * (wb[1] - logBG)
            d to m
        }.sortedBy { it.first }.take(2)
        if (byDist[0].first < 1e-6) return byDist[0].second
        val w0 = 1 / byDist[0].first
        val w1 = 1 / byDist[1].first
        val out = DoubleArray(9) { (byDist[0].second[it] * w0 + byDist[1].second[it] * w1) / (w0 + w1) }
        for (r in 0 until 3) {
            val s = out[r * 3] + out[r * 3 + 1] + out[r * 3 + 2]
            for (c in 0 until 3) out[r * 3 + c] /= s
        }
        return out
    }
}
