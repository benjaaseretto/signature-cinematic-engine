package com.signature.cinematic.color

/**
 * Acumula los 24 parches de la carta a partir de fotogramas RGBA8 sin procesar (fila 0 arriba).
 * La carta debe ocupar la guía [left, top, right, bottom] (0..1 del cuadro) con el parche marrón
 * (piel oscura) arriba a la izquierda.
 */
class ChartSampler(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    val framesWanted: Int,
    /** Fracción central de cada celda que se mide (evita bordes negros y desalineación). */
    private val inner: Float = 0.4f,
) {
    private val sums = Array(ColorChecker.PATCH_COUNT) { DoubleArray(3) }
    private val clipCount = IntArray(ColorChecker.PATCH_COUNT)
    private val pixelCount = IntArray(ColorChecker.PATCH_COUNT)
    val neutralPerFrame = mutableListOf<DoubleArray>()
    var frames = 0
        private set

    val done get() = frames >= framesWanted

    fun addFrame(rgba: ByteArray, width: Int, height: Int) {
        if (done) return
        val cw = (right - left) / ColorChecker.COLUMNS
        val ch = (bottom - top) / ColorChecker.ROWS
        for (row in 0 until ColorChecker.ROWS) for (col in 0 until ColorChecker.COLUMNS) {
            val idx = row * ColorChecker.COLUMNS + col
            val cx = left + (col + 0.5f) * cw
            val cy = top + (row + 0.5f) * ch
            val x0 = ((cx - cw * inner / 2) * width).toInt().coerceIn(0, width - 1)
            val x1 = ((cx + cw * inner / 2) * width).toInt().coerceIn(x0 + 1, width)
            val y0 = ((cy - ch * inner / 2) * height).toInt().coerceIn(0, height - 1)
            val y1 = ((cy + ch * inner / 2) * height).toInt().coerceIn(y0 + 1, height)
            val frameSum = DoubleArray(3)
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val o = (y * width + x) * 4
                var clipped = false
                for (c in 0 until 3) {
                    val v = rgba[o + c].toInt() and 0xff
                    if (v >= 250) clipped = true
                    frameSum[c] += LINEAR[v]
                }
                if (clipped) clipCount[idx]++
                n++
            }
            for (c in 0 until 3) sums[idx][c] += frameSum[c]
            pixelCount[idx] += n
            if (idx == NEUTRAL_5) neutralPerFrame += DoubleArray(3) { frameSum[it] / n }
        }
        frames++
    }

    fun patches(): List<DoubleArray> = List(ColorChecker.PATCH_COUNT) { i ->
        DoubleArray(3) { c -> if (pixelCount[i] > 0) sums[i][c] / pixelCount[i] else 0.0 }
    }

    fun clipped() = BooleanArray(ColorChecker.PATCH_COUNT) { pixelCount[it] > 0 && clipCount[it] > pixelCount[it] * 0.05 }

    companion object {
        const val NEUTRAL_5 = 21
        private val LINEAR = DoubleArray(256) { ColorScience.srgbDecode(it / 255.0) }
    }
}
