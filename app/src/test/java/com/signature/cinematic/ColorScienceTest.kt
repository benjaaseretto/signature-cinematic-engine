package com.signature.cinematic

import com.signature.cinematic.color.CalibrationMath
import com.signature.cinematic.color.ChartCapture
import com.signature.cinematic.color.ChartSampler
import com.signature.cinematic.color.ColorChecker
import com.signature.cinematic.color.ColorProfile
import com.signature.cinematic.color.ColorScience
import com.signature.cinematic.color.ColorScience.Lab
import com.signature.cinematic.color.Illuminant
import com.signature.cinematic.color.IspTonemap
import com.signature.cinematic.engine.CaptureProfile
import com.signature.cinematic.engine.VideoMode
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.system.exitProcess

private var failures = 0

private fun check(ok: Boolean, what: String) {
    if (!ok) {
        failures++
        println("FALLO: $what")
    }
}

fun main() {
    deltaE2000Reference()
    labRoundTrip()
    matrixFitRecovers()
    clippedPatchExcluded()
    chartSampler()
    interpolation()
    temporalStability()
    captureProfiles()
    profilesBlackLevels()
    if (failures > 0) {
        println("$failures fallos")
        exitProcess(1)
    }
    println("OK (color)")
}

/** Pares de Sharma, Wu y Dalal (2005), tabla 1. */
private fun deltaE2000Reference() {
    val pairs = listOf(
        Triple(Lab(50.0, 2.6772, -79.7751), Lab(50.0, 0.0, -82.7485), 2.0425),
        Triple(Lab(50.0, 3.1571, -77.2803), Lab(50.0, 0.0, -82.7485), 2.8615),
        Triple(Lab(50.0, 2.8361, -74.0200), Lab(50.0, 0.0, -82.7485), 3.4412),
        Triple(Lab(50.0, -1.3802, -84.2814), Lab(50.0, 0.0, -82.7485), 1.0000),
        Triple(Lab(50.0, 0.0, 0.0), Lab(50.0, -1.0, 2.0), 2.3669),
        Triple(Lab(50.0, 2.5, 0.0), Lab(73.0, 25.0, -18.0), 27.1492),
        Triple(Lab(60.2574, -34.0099, 36.2677), Lab(60.4626, -34.1751, 39.4387), 1.2644),
    )
    for ((a, b, expected) in pairs) {
        val d = ColorScience.deltaE2000(a, b)
        check(abs(d - expected) < 1e-3, "ΔE00 $a vs $b = $d (esperado $expected)")
        check(abs(ColorScience.deltaE2000(b, a) - d) < 1e-9, "ΔE00 simétrico")
    }
    check(ColorScience.deltaE2000(Lab(40.0, 10.0, -5.0), Lab(40.0, 10.0, -5.0)) == 0.0, "ΔE00 nulo en el mismo color")
}

private fun labRoundTrip() {
    for (lab in ColorChecker.CLASSIC_2005_D50.patches) {
        val back = ColorScience.linearSrgbToLab(ColorScience.labToLinearSrgb(lab))
        check(ColorScience.deltaE2000(lab, back) < 1e-3, "ida y vuelta Lab → sRGB → Lab: $lab → $back")
    }
    val white = ColorScience.labToLinearSrgb(ColorChecker.CLASSIC_2005_D50.patches[18])
    check(white.max() - white.min() < 0.03, "el blanco de la carta queda neutro en sRGB D65: ${white.toList()}")
}

private val TRUE_MATRIX = doubleArrayOf(
    1.20, -0.15, -0.05,
    -0.10, 1.15, -0.05,
    0.02, -0.22, 1.20,
)

private fun syntheticCapture(exposure: Double, clipped: BooleanArray = BooleanArray(24)): ChartCapture {
    val inv = ColorScience.invert3(TRUE_MATRIX)!!
    val target = CalibrationMath.targetLinear(ColorChecker.CLASSIC_2005_D50)
    val measured = target.map { t -> ColorScience.mul(inv, t).map { it * exposure }.toDoubleArray() }
    return ChartCapture("2", Illuminant.DAYLIGHT, IspTonemap.SRGB, measured, clipped,
        List(10) { measured[ChartSampler.NEUTRAL_5] }, doubleArrayOf(2.0, 1.0, 1.6), 16_666_667L, 200, "sintético")
}

private fun matrixFitRecovers() {
    val r = CalibrationMath.fit(syntheticCapture(0.7), ColorChecker.CLASSIC_2005_D50)
    for (i in 0 until 9) check(abs(r.matrix[i] - TRUE_MATRIX[i]) < 1e-6, "recupera la matriz [$i] = ${r.matrix[i]}")
    check(r.after.mean < 0.01, "ΔE00 medio tras el ajuste: ${r.after.mean}")
    check(r.before.mean > 1.0, "sin matriz hay error apreciable: ${r.before.mean}")
    for (row in 0 until 3) {
        check(abs(r.matrix[row * 3] + r.matrix[row * 3 + 1] + r.matrix[row * 3 + 2] - 1) < 1e-9, "fila $row conserva el blanco")
    }
}

private fun clippedPatchExcluded() {
    val clipped = BooleanArray(24).also { it[15] = true }
    val good = syntheticCapture(0.7, clipped)
    val corrupted = good.patches.toMutableList().also { it[15] = doubleArrayOf(1.0, 1.0, 0.2) }
    val cap = ChartCapture(good.physicalId, good.illuminant, good.tonemap, corrupted, clipped, good.neutralPerFrame,
        good.wbGains, good.exposureNs, good.iso, good.conditions)
    val r = CalibrationMath.fit(cap, ColorChecker.CLASSIC_2005_D50)
    for (i in 0 until 9) check(abs(r.matrix[i] - TRUE_MATRIX[i]) < 1e-6, "parche recortado no afecta [$i]")
}

private fun chartSampler() {
    val w = 270
    val h = 480
    val left = 0.06f
    val right = 0.94f
    val gh = (right - left) * w / 1.5f / h
    val top = 0.5f - gh / 2
    val bottom = 0.5f + gh / 2
    val target = CalibrationMath.targetLinear(ColorChecker.CLASSIC_2005_D50)
    val img = ByteArray(w * h * 4)
    fun enc(v: Double): Int {
        val c = v.coerceIn(0.0, 1.0)
        val g = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
        return (g * 255).roundToInt()
    }
    for (y in 0 until h) for (x in 0 until w) {
        val u = (x + 0.5f) / w
        val v = (y + 0.5f) / h
        if (u < left || u >= right || v < top || v >= bottom) continue
        val col = ((u - left) / (right - left) * 6).toInt().coerceIn(0, 5)
        val row = ((v - top) / (bottom - top) * 4).toInt().coerceIn(0, 3)
        val t = target[row * 6 + col]
        val o = (y * w + x) * 4
        for (c in 0 until 3) img[o + c] = enc(t[c] * 0.8).toByte()
        img[o + 3] = 0xff.toByte()
    }
    val s = ChartSampler(left, top, right, bottom, 3)
    repeat(5) { s.addFrame(img, w, h) }
    check(s.frames == 3 && s.done, "deja de acumular al llegar a los fotogramas pedidos")
    check(s.neutralPerFrame.size == 3, "neutro por fotograma")
    val stats = CalibrationMath.evaluate(ColorScience.identity(), s.patches(), ColorChecker.CLASSIC_2005_D50)
    check(stats.mean < 0.6, "muestreo de la carta sintética: ΔE00 medio ${stats.mean}")
    check(s.clipped().none { it }, "sin recortes en la carta sintética")
}

private fun interpolation() {
    val a = doubleArrayOf(1.2, -0.1, -0.1, -0.05, 1.1, -0.05, 0.0, -0.2, 1.2)
    val b = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    val points = listOf(doubleArrayOf(0.7, 0.3) to a, doubleArrayOf(0.2, 0.9) to b)
    val atA = CalibrationMath.interpolate(points, 0.7, 0.3)
    for (i in 0 until 9) check(abs(atA[i] - a[i]) < 1e-9, "en el punto calibrado devuelve su matriz")
    val mid = CalibrationMath.interpolate(points, 0.45, 0.6)
    check(abs(mid[0] - 1.1) < 1e-9, "a mitad de camino mezcla por igual: ${mid[0]}")
    for (r in 0 until 3) check(abs(mid[r * 3] + mid[r * 3 + 1] + mid[r * 3 + 2] - 1) < 1e-9, "mezcla conserva el blanco")
    check(CalibrationMath.interpolate(emptyList(), 0.0, 0.0).contentEquals(ColorScience.identity()), "sin calibración = identidad")
}

private fun temporalStability() {
    val steady = List(20) { doubleArrayOf(0.18, 0.18, 0.18) }
    check(CalibrationMath.temporalStd(steady) < 1e-9, "sin variación temporal")
    val flicker = List(20) { i -> if (i % 2 == 0) doubleArrayOf(0.18, 0.18, 0.18) else doubleArrayOf(0.19, 0.18, 0.17) }
    check(CalibrationMath.temporalStd(flicker) > 0.5, "detecta variación de color entre fotogramas")
}

private fun captureProfiles() {
    val modes = listOf(VideoMode(3840, 2160, 30, 30, true), VideoMode(3840, 2160, 60, 60, true), VideoMode(1920, 1080, 24, 24, true))
    check(CaptureProfile.A_NATURAL.resolve(modes)?.fps == 30, "A usa 4K30")
    check(CaptureProfile.B_ACTION.resolve(modes)?.fps == 60, "B usa 4K60")
    val c = CaptureProfile.C_CINEMATIC.resolve(modes)
    check(c?.width == 3840 && c.fps == 30, "C cae a 4K30 si no hay 4K24: $c")
    check(CaptureProfile.E_DETAIL.resolve(modes) == null, "E no disponible sin 8K30")
    check(CaptureProfile.F_SLOW_MOTION.resolve(modes + VideoMode(1920, 1080, 120, 120, true)) == null,
        "F no se ofrece mientras no haya sesión de alta velocidad")
    val ids = CaptureProfile.selectable(modes).map { it.id }
    check(ids == listOf("A", "B", "C", "D"), "perfiles seleccionables: $ids")
    check(abs(1e9 / 24 * CaptureProfile.C_CINEMATIC.shutterFraction - 1e9 / 48) < 1, "C a 24p = 1/48")
}

private fun profilesBlackLevels() {
    for (p in ColorProfile.ALL) {
        val black = p.tone.apply(0f)
        if (p === ColorProfile.NEUTRAL_FLAT) continue
        check(black < 0.005f, "${p.id}: no levanta negros (${black})")
    }
    check(ColorProfile.NATURAL.satMid == 1f && ColorProfile.NATURAL.satShadow == 1f, "Natural: saturación 1.0")
    check(ColorProfile.ALL.size == 13, "13 perfiles Signature (incluye subtonos de piel 18 Pro)")
}
