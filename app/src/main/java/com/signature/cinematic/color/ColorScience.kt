package com.signature.cinematic.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Conversión de color, Delta E 2000 y ajuste de matrices. Sin dependencias de Android. */
object ColorScience {
    private val D50 = doubleArrayOf(0.96422, 1.0, 0.82521)
    private val D65 = doubleArrayOf(0.95047, 1.0, 1.08883)

    /** Bradford D50 → D65. */
    private val BRADFORD_D50_TO_D65 = doubleArrayOf(
        0.9555766, -0.0230393, 0.0631636,
        -0.0282895, 1.0099416, 0.0210077,
        0.0122982, -0.0204830, 1.3299098,
    )
    private val BRADFORD_D65_TO_D50 = doubleArrayOf(
        1.0478112, 0.0228866, -0.0501270,
        0.0295424, 0.9904844, -0.0170491,
        -0.0092345, 0.0150436, 0.7521316,
    )
    private val XYZ_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252,
    )
    private val SRGB_TO_XYZ = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )

    /** L*, a*, b* (D50). */
    data class Lab(val l: Double, val a: Double, val b: Double)

    fun labToXyzD50(lab: Lab): DoubleArray {
        val fy = (lab.l + 16) / 116
        val fx = fy + lab.a / 500
        val fz = fy - lab.b / 200
        fun inv(t: Double) = if (t > 6.0 / 29) t * t * t else 3 * (6.0 / 29).pow(2) * (t - 4.0 / 29)
        return doubleArrayOf(D50[0] * inv(fx), D50[1] * inv(fy), D50[2] * inv(fz))
    }

    fun xyzD50ToLab(xyz: DoubleArray): Lab {
        fun f(t: Double) = if (t > (6.0 / 29).pow(3)) cbrt(t) else t / (3 * (6.0 / 29).pow(2)) + 4.0 / 29
        val fx = f(xyz[0] / D50[0])
        val fy = f(xyz[1] / D50[1])
        val fz = f(xyz[2] / D50[2])
        return Lab(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** Lab D50 → sRGB lineal (D65), sin recortar. */
    fun labToLinearSrgb(lab: Lab): DoubleArray =
        mul(XYZ_TO_SRGB, mul(BRADFORD_D50_TO_D65, labToXyzD50(lab)))

    fun linearSrgbToLab(rgb: DoubleArray): Lab =
        xyzD50ToLab(mul(BRADFORD_D65_TO_D50, mul(SRGB_TO_XYZ, rgb)))

    fun srgbDecode(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun luminance(rgb: DoubleArray): Double = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]

    /** CIEDE2000 (Sharma, Wu, Dalal 2005) con kL = kC = kH = 1. */
    fun deltaE2000(x: Lab, y: Lab): Double {
        val c1 = hypot(x.a, x.b)
        val c2 = hypot(y.a, y.b)
        val cMean = (c1 + c2) / 2
        val g = 0.5 * (1 - sqrt(cMean.pow(7) / (cMean.pow(7) + 25.0.pow(7))))
        val a1 = (1 + g) * x.a
        val a2 = (1 + g) * y.a
        val c1p = hypot(a1, x.b)
        val c2p = hypot(a2, y.b)
        fun hue(b: Double, a: Double): Double {
            if (a == 0.0 && b == 0.0) return 0.0
            val h = Math.toDegrees(atan2(b, a))
            return if (h < 0) h + 360 else h
        }
        val h1 = hue(x.b, a1)
        val h2 = hue(y.b, a2)
        val dL = y.l - x.l
        val dC = c2p - c1p
        val dh = when {
            c1p * c2p == 0.0 -> 0.0
            abs(h2 - h1) <= 180 -> h2 - h1
            h2 - h1 > 180 -> h2 - h1 - 360
            else -> h2 - h1 + 360
        }
        val dH = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dh / 2))
        val lMean = (x.l + y.l) / 2
        val cpMean = (c1p + c2p) / 2
        val hMean = when {
            c1p * c2p == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180 -> (h1 + h2) / 2
            h1 + h2 < 360 -> (h1 + h2 + 360) / 2
            else -> (h1 + h2 - 360) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hMean - 30)) + 0.24 * cos(Math.toRadians(2 * hMean)) +
            0.32 * cos(Math.toRadians(3 * hMean + 6)) - 0.20 * cos(Math.toRadians(4 * hMean - 63))
        val dTheta = 30 * exp(-((hMean - 275) / 25).pow(2))
        val rc = 2 * sqrt(cpMean.pow(7) / (cpMean.pow(7) + 25.0.pow(7)))
        val sl = 1 + 0.015 * (lMean - 50).pow(2) / sqrt(20 + (lMean - 50).pow(2))
        val sc = 1 + 0.045 * cpMean
        val sh = 1 + 0.015 * cpMean * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt((dL / sl).pow(2) + (dC / sc).pow(2) + (dH / sh).pow(2) + rt * (dC / sc) * (dH / sh))
    }

    /**
     * Matriz 3x3 (por filas) que minimiza el error cuadrático ponderado entre M·medido y objetivo,
     * normalizada para que el blanco neutro se conserve (M·[1,1,1] = [1,1,1]).
     */
    fun fitMatrix(measured: List<DoubleArray>, target: List<DoubleArray>, weights: DoubleArray): DoubleArray {
        require(measured.size == target.size && measured.size == weights.size && measured.size >= 3)
        val ata = DoubleArray(9)
        val atb = Array(3) { DoubleArray(3) }
        for (i in measured.indices) {
            val m = measured[i]
            val w = weights[i]
            for (r in 0 until 3) {
                for (c in 0 until 3) ata[r * 3 + c] += w * m[r] * m[c]
                for (o in 0 until 3) atb[o][r] += w * m[r] * target[i][o]
            }
        }
        val inv = invert3(ata) ?: return identity()
        val out = DoubleArray(9)
        for (o in 0 until 3) {
            val row = mul(inv, atb[o])
            for (c in 0 until 3) out[o * 3 + c] = row[c]
        }
        for (r in 0 until 3) {
            val sum = out[r * 3] + out[r * 3 + 1] + out[r * 3 + 2]
            if (abs(sum) > 1e-6) for (c in 0 until 3) out[r * 3 + c] /= sum
        }
        return out
    }

    fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun mul(m: DoubleArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    fun invert3(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-12) return null
        val d = 1 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d,
        )
    }
}
