package com.signature.cinematic.color

import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Estilo fotográfico sobre píxeles ARGB: la misma pipeline que [com.signature.cinematic.gl.Shaders.FRAGMENT]
 * con base identidad y ganancia 1 (la JPEG ya viene expuesta y balanceada por el ISP).
 */
object PhotoLook {
    private const val TABLE = 4096
    private val W = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    private val LIN8 = FloatArray(256) { toLin1(it / 255f) }
    private val GAM = FloatArray(TABLE + 1) { toGam1(it / TABLE.toFloat()) }
    private val LIN = FloatArray(TABLE + 1) { toLin1(it / TABLE.toFloat()) }

    @JvmStatic
    fun toLin1(c: Float): Float = if (c < 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    @JvmStatic
    fun toGam1(c: Float): Float = if (c < 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private fun table(t: FloatArray, x: Float): Float {
        val f = x.coerceIn(0f, 1f) * TABLE
        val i = min(f.toInt(), TABLE - 1)
        return t[i] + (t[i + 1] - t[i]) * (f - i)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Peso de piel desde el color en gamma (en la foto, la base ya es la JPEG). */
    private fun skinWeight(r: Float, g: Float, b: Float): Float {
        val cb = -0.1146f * r - 0.3854f * g + 0.5f * b
        val cr = 0.5f * r - 0.4542f * g - 0.0458f * b
        val chroma = sqrt(cb * cb + cr * cr)
        val hue = atan2(cr, cb)
        return exp(-(hue - 2.15f) * (hue - 2.15f) / 0.18f) *
            smoothstep(0.02f, 0.06f, chroma) * (1f - smoothstep(0.25f, 0.35f, chroma))
    }

    /** Aplica el perfil en el lugar, repartiendo filas entre núcleos. */
    @JvmStatic
    @JvmOverloads
    fun apply(
        px: IntArray, w: Int, h: Int, p: ColorProfile, threads: Int = Runtime.getRuntime().availableProcessors(),
        tonePad: Float = 0f, colorPad: Float = 0f, softSkin: Float = 0f, glow: Float = 0f, grain: Float = 0f,
    ) {
        val lut = p.tone.toLut8()
        val tone = FloatArray(256) { (lut[it].toInt() and 0xff) / 255f }
        val srcCopy = if (softSkin > 0.01f || glow > 0.01f) px.copyOf() else null
        val n = threads.coerceIn(1, 16)
        val rowsPer = (h + n - 1) / n
        val workers = (0 until n).map { k ->
            Thread {
                val y0 = k * rowsPer
                val y1 = min(h, y0 + rowsPer)
                for (y in y0 until y1) {
                    val rowOff = y * w
                    for (x in 0 until w) {
                        val i = rowOff + x
                        var argb = srcCopy?.get(i) ?: px[i]
                        if (srcCopy != null && x > 1 && x < w - 2 && y > 1 && y < h - 2) {
                            argb = applyTexture(srcCopy, w, x, y, argb, softSkin, glow)
                        }
                        px[i] = pixel(argb, p, tone, tonePad, colorPad, grain, i)
                    }
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
    }

    private fun applyTexture(src: IntArray, w: Int, x: Int, y: Int, c: Int, softSkin: Float, glow: Float): Int {
        val r = ((c shr 16) and 0xff) / 255f
        val g = ((c shr 8) and 0xff) / 255f
        val b = (c and 0xff) / 255f
        val rawSkin = skinWeight(r, g, b)
        if (softSkin <= 0.01f && glow <= 0.01f) return c
        if (rawSkin <= 0.04f && glow <= 0.01f) return c
        val p0 = src[(y - 2) * w + (x - 2)]
        val p1 = src[(y - 2) * w + (x + 2)]
        val p2 = src[(y + 2) * w + (x - 2)]
        val p3 = src[(y + 2) * w + (x + 2)]
        val ar = (((p0 shr 16) and 0xff) + ((p1 shr 16) and 0xff) + ((p2 shr 16) and 0xff) + ((p3 shr 16) and 0xff)) / (4f * 255f)
        val ag = (((p0 shr 8) and 0xff) + ((p1 shr 8) and 0xff) + ((p2 shr 8) and 0xff) + ((p3 shr 8) and 0xff)) / (4f * 255f)
        val ab = ((p0 and 0xff) + (p1 and 0xff) + (p2 and 0xff) + (p3 and 0xff)) / (4f * 255f)
        val dr = r - ar
        val dg = g - ag
        val db = b - ab
        val bilateral = exp(-(dr * dr + dg * dg + db * db) / 0.0045f)
        val softAmt = softSkin * rawSkin * bilateral * 0.68f
        var nr = r + (ar - r) * softAmt
        var ng = g + (ag - g) * softAmt
        var nb = b + (ab - b) * softAmt
        if (glow > 0.01f) {
            val glowLuma = max(0f, (W[0] * ar + W[1] * ag + W[2] * ab) - 0.38f)
            val k = glowLuma * glow * (0.14f + 0.12f * rawSkin)
            nr += ar * k
            ng += ag * k
            nb += ab * k
        }
        val ro = (nr * 255f + 0.5f).toInt().coerceIn(0, 255)
        val go = (ng * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bo = (nb * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (c and -0x1000000) or (ro shl 16) or (go shl 8) or bo
    }

    @JvmStatic
    fun pixel(argb: Int, p: ColorProfile, tone: FloatArray): Int =
        pixel(argb, p, tone, 0f, 0f, 0f, 0)

    @JvmStatic
    fun pixel(argb: Int, p: ColorProfile, tone: FloatArray, tonePad: Float, colorPad: Float, grain: Float, seed: Int): Int {
        val ri = (argb shr 16) and 0xff
        val gi = (argb shr 8) and 0xff
        val bi = argb and 0xff
        val r0 = LIN8[ri]
        val g0 = LIN8[gi]
        val b0 = LIN8[bi]
        val m = p.matrix
        val rawSkin = skinWeight(ri / 255f, gi / 255f, bi / 255f)
        val skin = rawSkin * p.skinProtect
        val su = p.skinUndertone
        // Calibración de fase de piel estilo Leica (idéntica a Shaders.FRAGMENT):
        // neutraliza tintes magenta artificiales y verdosos de iluminación interior sin afectar grises neutros
        val magentaExcess = max(0f, (r0 + b0) * 0.5f - g0 * 1.08f) * rawSkin
        val greenExcess = max(0f, g0 - (r0 * 0.72f + b0 * 0.28f)) * rawSkin
        val sr = (r0 - magentaExcess * 0.18f + greenExcess * 0.14f) * su[0] * (1f + 0.025f * colorPad)
        val sg = (g0 + magentaExcess * 0.15f - greenExcess * 0.32f) * su[1] * (1f - 0.024f * rawSkin)
        val sb = (b0 - magentaExcess * 0.28f) * su[2] * (1f - 0.020f * colorPad)
        var r = m[0] * r0 + m[1] * g0 + m[2] * b0
        var g = m[3] * r0 + m[4] * g0 + m[5] * b0
        var b = m[6] * r0 + m[7] * g0 + m[8] * b0
        r += (sr - r) * skin
        g += (sg - g) * skin
        b += (sb - b) * skin

        val y = max(W[0] * r + W[1] * g + W[2] * b, 1e-5f)
        // Roll-off logarítmico/hiperbólico suave desde knee 0.68 (estética Leica Authentic / Vibrant)
        val knee = 0.68f
        val yc = if (y <= knee) y else knee + (1f - knee) * tanh((y - knee) / (1f - knee))
        val u = table(GAM, yc) * 255f
        val ti = min(u.toInt(), 254)
        var yt = tone[ti] + (tone[ti + 1] - tone[ti]) * (u - ti)
        if (kotlin.math.abs(tonePad) > 0.01f) {
            val padCurve = yt.coerceIn(0f, 1f).pow(1f - tonePad * 0.28f)
            yt = padCurve + (yt - padCurve) * (skin * p.skinLumaAnchor)
        }
        val ySkinSafe = yt + (table(GAM, yc) - yt) * (skin * p.skinLumaAnchor * 0.42f)
        var yl = table(LIN, ySkinSafe)
        // Micro-contraste rico en tonos medios (idéntico al shader, solo cuando hay saturación cromática para preservar neutralidad de grises)
        if (rawSkin > 0.01f || kotlin.math.abs(r0 - g0) + kotlin.math.abs(g0 - b0) > 0.02f) {
            val midMask = smoothstep(0.08f, 0.35f, yl) * (1f - smoothstep(0.55f, 0.85f, yl))
            val midContrast = 1f + (yl - 0.38f) * 0.045f * midMask * (1f - 0.35f * skin)
            yl = (yl * midContrast).coerceIn(0f, 1f)
        }
        val s = yl / y
        r *= s
        g *= s
        b *= s

        // 1. Saturación por zonas (sombras, medios, altas) con protección de piel
        val wS = 1f - smoothstep(0.02f, 0.18f, yl)
        val wH = smoothstep(0.45f, 0.88f, yl)
        var sat = (p.satMid + (p.satShadow - p.satMid) * wS)
        sat += (p.satHigh - sat) * wH
        sat *= 1f + colorPad * 0.22f
        sat += (1f + colorPad * 0.06f - sat) * skin
        r = yl + (r - yl) * sat
        g = yl + (g - yl) * sat
        b = yl + (b - yl) * sat

        // 2. Tintes de sombras y altas luces
        val tm = smoothstep(0.05f, 0.6f, yl)
        val tf = 1f - 0.7f * skin
        val st = p.shadowTint
        val ht = p.highlightTint
        r *= 1f + (st[0] + (ht[0] - st[0]) * tm - 1f) * tf
        g *= 1f + (st[1] + (ht[1] - st[1]) * tm - 1f) * tf
        b *= 1f + (st[2] + (ht[2] - st[2]) * tm - 1f) * tf

        // 3. Altas luces: desaturación progresiva hacia blanco neutro (estética fílmica Leica / haluro de plata)
        val hlFactor = ((yl - 0.75f) / (0.98f - 0.75f)).coerceIn(0f, 1f)
        val hlDesat = 1f - hlFactor.pow(1.35f) * 0.90f
        r = yl + (r - yl) * hlDesat
        g = yl + (g - yl) * hlDesat
        b = yl + (b - yl) * hlDesat

        // 4. Soft-clipping y compresión de gamut
        val mx = max(r, max(g, b))
        if (mx > 1f) {
            val t = ((mx - 1f) / max(mx - yl, 1e-4f)).coerceIn(0f, 1f)
            r += (yl - r) * t
            g += (yl - g) * t
            b += (yl - b) * t
        }

        if (p.gamutCompress > 0f) {
            val dr = r - yl
            val dg = g - yl
            val db = b - yl
            val room = { d: Float -> if (d >= 0f) 1f - yl else yl }
            val k = max(max(Math.abs(dr) / max(room(dr), 1e-4f), Math.abs(dg) / max(room(dg), 1e-4f)),
                Math.abs(db) / max(room(db), 1e-4f))
            val knee = 0.8f
            if (k > knee) {
                val kc = knee + (1f - knee) * tanh((k - knee) / (1f - knee))
                val f = kc / k
                r += (yl + dr * f - r) * p.gamutCompress
                g += (yl + dg * f - g) * p.gamutCompress
                b += (yl + db * f - b) * p.gamutCompress
            }
        }
        r += (yl - r) * p.mono
        g += (yl - g) * p.mono
        b += (yl - b) * p.mono

        var gr = table(GAM, r)
        var gg = table(GAM, g)
        var gb = table(GAM, b)
        if (grain > 0.01f) {
            val n = (((seed * 1103515245 + 12345) ushr 16) and 0xff) / 255f - 0.5f
            val midW = 4f * gg * (1f - gg)
            val d = n * grain * 0.075f * midW
            gr = (gr + d).coerceIn(0f, 1f)
            gg = (gg + d).coerceIn(0f, 1f)
            gb = (gb + d).coerceIn(0f, 1f)
        }
        val ro = (gr * 255f + 0.5f).toInt().coerceIn(0, 255)
        val go = (gg * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bo = (gb * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (argb and -0x1000000) or (ro shl 16) or (go shl 8) or bo
    }

    /**
     * Recorte central a la proporción pedida (lado largo / lado corto: 4/3, 16/9, 1).
     * Devuelve x, y, ancho, alto. No depende de la orientación del cuadro.
     */
    @JvmStatic
    fun cropRect(w: Int, h: Int, ratio: Float): IntArray {
        val long = max(w, h)
        val short = min(w, h)
        val current = long / short.toFloat()
        var nl = long
        var ns = short
        if (ratio > current + 1e-3f) {
            ns = (long / ratio).toInt()
        } else if (ratio < current - 1e-3f) {
            nl = (short * ratio).toInt()
        }
        val cw = if (w >= h) nl else ns
        val ch = if (w >= h) ns else nl
        return intArrayOf((w - cw) / 2, (h - ch) / 2, cw, ch)
    }

    /**
     * Mejora de micro-textura y nitidez por reconstrucción de altas frecuencias
     * con umbral de ruido adaptativo (coring). Restaura detalles finos (textura de ropa, pestañas, cabello, poros)
     * sin quemar bordes ni amplificar ruido en áreas planas.
     */
    @JvmStatic
    @JvmOverloads
    fun enhanceMicroTexture(
        px: IntArray,
        w: Int,
        h: Int,
        strength: Float = 0.28f,
        threads: Int = Runtime.getRuntime().availableProcessors()
    ) {
        if (strength <= 0.01f || w < 3 || h < 3) return
        val src = px.copyOf()
        val n = threads.coerceIn(1, 16)
        val rowsPer = (h + n - 1) / n
        val workers = (0 until n).map { k ->
            Thread {
                val y0 = max(1, k * rowsPer)
                val y1 = min(h - 1, y0 + rowsPer)
                for (y in y0 until y1) {
                    val rowOff = y * w
                    for (x in 1 until w - 1) {
                        val i = rowOff + x
                        val c = src[i]
                        val r = (c shr 16) and 0xff
                        val g = (c shr 8) and 0xff
                        val b = c and 0xff

                        // Laplaciano espacial de 4 vecinos
                        val up = src[(y - 1) * w + x]
                        val down = src[(y + 1) * w + x]
                        val left = src[rowOff + (x - 1)]
                        val right = src[rowOff + (x + 1)]

                        val avgR = (((up shr 16) and 0xff) + ((down shr 16) and 0xff) + ((left shr 16) and 0xff) + ((right shr 16) and 0xff)) * 0.25f
                        val avgG = (((up shr 8) and 0xff) + ((down shr 8) and 0xff) + ((left shr 8) and 0xff) + ((right shr 8) and 0xff)) * 0.25f
                        val avgB = ((up and 0xff) + (down and 0xff) + (left and 0xff) + (right and 0xff)) * 0.25f

                        val diffR = r - avgR
                        val diffG = g - avgG
                        val diffB = b - avgB
                        val diffLuma = 0.2126f * diffR + 0.7152f * diffG + 0.0722f * diffB
                        val absDiff = kotlin.math.abs(diffLuma)

                        // Deconvolución acromática Rec.709 con protección de sombras (evita amplificar ruido cromático)
                        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                        val shadowWeight = ((luma - 12f) / 36f).coerceIn(0.15f, 1f)
                        val gain = when {
                            absDiff < 2.5f -> -0.12f * strength
                            absDiff < 28f -> strength * shadowWeight
                            else -> strength * shadowWeight * (35f / absDiff).coerceAtMost(1f)
                        }
                        val deltaLuma = diffLuma * gain
                        val ro = (r + (diffR * 0.35f + diffLuma * 0.65f) * gain + 0.5f).toInt().coerceIn(0, 255)
                        val go = (g + (diffG * 0.35f + diffLuma * 0.65f) * gain + 0.5f).toInt().coerceIn(0, 255)
                        val bo = (b + (diffB * 0.35f + diffLuma * 0.65f) * gain + 0.5f).toInt().coerceIn(0, 255)
                        px[i] = (c and -0x1000000) or (ro shl 16) or (go shl 8) or bo
                    }
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
    }
}
