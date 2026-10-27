package com.signature.cinematic.cinema

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Preprocesado y posprocesado de los modelos y lógica de foco. Sin dependencias de Android. */
object DepthMath {
    /** Región del cuadro (vertical) dentro de la entrada cuadrada del modelo. */
    class Letterbox(val size: Int, srcW: Int, srcH: Int) {
        val contentW: Int
        val contentH: Int
        val x0: Int
        val y0: Int

        init {
            val scale = size / max(srcW, srcH).toFloat()
            contentW = (srcW * scale).roundToInt().coerceIn(1, size)
            contentH = (srcH * scale).roundToInt().coerceIn(1, size)
            x0 = (size - contentW) / 2
            y0 = (size - contentH) / 2
        }
    }

    /**
     * RGBA8 (fila 0 arriba) → entrada float [0,1] RGB de size×size, conservando la proporción
     * (bandas grises a los lados) con muestreo bilineal.
     */
    fun toModelInput(rgba: ByteArray, w: Int, h: Int, box: Letterbox, out: FloatArray) {
        val s = box.size
        out.fill(0.5f)
        for (y in 0 until box.contentH) {
            val sy = ((y + 0.5f) / box.contentH * h - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = min(y0 + 1, h - 1)
            val fy = sy - y0
            for (x in 0 until box.contentW) {
                val sx = ((x + 0.5f) / box.contentW * w - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = min(x0 + 1, w - 1)
                val fx = sx - x0
                val o = ((box.y0 + y) * s + box.x0 + x) * 3
                for (c in 0 until 3) {
                    val a = (rgba[(y0 * w + x0) * 4 + c].toInt() and 0xff) * (1 - fx) + (rgba[(y0 * w + x1) * 4 + c].toInt() and 0xff) * fx
                    val b = (rgba[(y1 * w + x0) * 4 + c].toInt() and 0xff) * (1 - fx) + (rgba[(y1 * w + x1) * 4 + c].toInt() and 0xff) * fx
                    out[o + c] = (a * (1 - fy) + b * fy) / 255f
                }
            }
        }
    }

    /** Recorta la región del cuadro de una salida size×size de un canal. */
    fun crop(src: FloatArray, box: Letterbox): FloatArray {
        val out = FloatArray(box.contentW * box.contentH)
        for (y in 0 until box.contentH) {
            System.arraycopy(src, (box.y0 + y) * box.size + box.x0, out, y * box.contentW, box.contentW)
        }
        return out
    }

    /** Percentiles aproximados por submuestreo. */
    fun percentiles(v: FloatArray, lo: Float, hi: Float): Pair<Float, Float> {
        val step = max(1, v.size / 4096)
        val sample = FloatArray((v.size + step - 1) / step) { v[it * step] }
        sample.sort()
        val i0 = ((sample.size - 1) * lo).roundToInt()
        val i1 = ((sample.size - 1) * hi).roundToInt()
        return sample[i0] to sample[i1]
    }

    fun median(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0.5f
        val a = values.copyOf(count)
        a.sort()
        return a[count / 2]
    }

    /**
     * Normaliza la disparidad relativa a 0..1 (1 = cerca) con límites suavizados en el tiempo
     * y mezcla con el mapa anterior para que no parpadee. Devuelve el mapa nuevo.
     */
    class Normalizer(private val rangeAlpha: Float = 0.25f, private val mapAlpha: Float = 0.6f) {
        private var lo = Float.NaN
        private var hi = Float.NaN
        private var prev: FloatArray? = null

        fun reset() {
            lo = Float.NaN
            hi = Float.NaN
            prev = null
        }

        fun apply(raw: FloatArray): FloatArray {
            val (p2, p98) = percentiles(raw, 0.02f, 0.98f)
            if (lo.isNaN()) {
                lo = p2
                hi = p98
            } else {
                lo += (p2 - lo) * rangeAlpha
                hi += (p98 - hi) * rangeAlpha
            }
            val span = max(hi - lo, 1e-6f)
            val out = FloatArray(raw.size) { ((raw[it] - lo) / span).coerceIn(0f, 1f) }
            val p = prev
            if (p != null && p.size == out.size) {
                var diff = 0f
                for (i in out.indices step 7) diff += abs(out[i] - p[i])
                diff /= (out.size / 7f)
                // Cambio de escena: no arrastrar el mapa anterior.
                if (diff < 0.2f) for (i in out.indices) out[i] = p[i] + (out[i] - p[i]) * mapAlpha
            }
            prev = out
            return out
        }
    }

    fun toBytes(v: FloatArray) = ByteArray(v.size) { (v[it].coerceIn(0f, 1f) * 255f).roundToInt().toByte() }

    /** Mediana de profundidad en una ventana alrededor de (u, v) (0..1, origen arriba a la izquierda). */
    fun depthAt(depth: FloatArray, w: Int, h: Int, u: Float, v: Float, radius: Int = 3): Float {
        val cx = (u * w).toInt().coerceIn(0, w - 1)
        val cy = (v * h).toInt().coerceIn(0, h - 1)
        val buf = FloatArray((2 * radius + 1) * (2 * radius + 1))
        var n = 0
        for (y in max(0, cy - radius)..min(h - 1, cy + radius)) for (x in max(0, cx - radius)..min(w - 1, cx + radius)) {
            buf[n++] = depth[y * w + x]
        }
        return median(buf, n)
    }

    class FocusTarget(val depth: Float, val onPerson: Boolean, val auto: Boolean)

    /**
     * Elige la profundidad de foco: punto tocado, persona más grande o centro del cuadro.
     * [mask] y [depth] cubren el mismo cuadro con distintas resoluciones.
     */
    fun chooseFocus(
        depth: FloatArray, dw: Int, dh: Int, mask: FloatArray?, mw: Int, mh: Int, point: FloatArray?,
    ): FocusTarget {
        fun maskAt(u: Float, v: Float): Float {
            if (mask == null) return 0f
            return mask[(v * mh).toInt().coerceIn(0, mh - 1) * mw + (u * mw).toInt().coerceIn(0, mw - 1)]
        }
        if (point != null) {
            return FocusTarget(depthAt(depth, dw, dh, point[0], point[1]), maskAt(point[0], point[1]) > 0.5f, false)
        }
        if (mask != null) {
            val values = FloatArray(4096)
            var n = 0
            var area = 0
            val step = max(1, (mw * mh) / 4096)
            var i = 0
            while (i < mw * mh) {
                if (mask[i] > 0.5f) {
                    area++
                    if (n < values.size) {
                        val u = (i % mw + 0.5f) / mw
                        val v = (i / mw + 0.5f) / mh
                        values[n++] = depth[((v * dh).toInt().coerceIn(0, dh - 1)) * dw + (u * dw).toInt().coerceIn(0, dw - 1)]
                    }
                }
                i += step
            }
            if (area * step > 0.02f * mw * mh) return FocusTarget(median(values, n), true, true)
        }
        return FocusTarget(depthAt(depth, dw, dh, 0.5f, 0.5f, radius = max(2, dw / 10)), false, true)
    }

    /** Transición de foco ("rack focus"): velocidad máxima en unidades de profundidad por segundo. */
    class FocusRack(private val maxSpeed: Float = 1.2f, private val tau: Float = 0.18f) {
        var current = Float.NaN
            private set

        fun step(target: Float, dtS: Float): Float {
            if (current.isNaN()) {
                current = target
                return current
            }
            val dt = dtS.coerceIn(0f, 0.1f)
            val wanted = (target - current) * (1f - kotlin.math.exp(-dt / tau))
            val limit = maxSpeed * dt
            current += wanted.coerceIn(-limit, limit)
            return current
        }

        fun reset() {
            current = Float.NaN
        }
    }

    /** Misma banda de foco y pendiente que TOL y GAIN en Shaders.COC. */
    const val COC_TOLERANCE = 0.04f
    const val COC_GAIN = 3.5f

    /** Círculo de confusión 0..1; misma fórmula que el shader. */
    fun coc(depth: Float, focus: Float, mask: Float, maskFocus: Float): Float =
        ((abs(depth - focus) - COC_TOLERANCE) * COC_GAIN).coerceIn(0f, 1f) * (1f - mask * maskFocus)

    /** Radio máximo de desenfoque como fracción de la altura del cuadro para un número f. */
    fun maxBlurFraction(fNumber: Float): Float = (0.022f * 2.0f / fNumber).coerceIn(0.002f, 0.035f)

    @JvmField
    val F_STOPS = floatArrayOf(1.48f, 1.8f, 2.8f, 4.0f, 5.6f, 8.0f, 16.0f)
}
