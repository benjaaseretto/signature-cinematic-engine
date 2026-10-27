package com.signature.cinematic

import com.signature.cinematic.color.ColorProfile
import com.signature.cinematic.color.PhotoLook
import kotlin.math.abs
import kotlin.system.exitProcess

private var fails = 0

private fun ok(cond: Boolean, what: String) {
    if (!cond) {
        fails++
        println("FALLO: $what")
    }
}

private fun rgb(r: Int, g: Int, b: Int) = (0xff shl 24) or (r shl 16) or (g shl 8) or b
private fun r(c: Int) = (c shr 16) and 0xff
private fun g(c: Int) = (c shr 8) and 0xff
private fun b(c: Int) = c and 0xff

private fun tone(p: ColorProfile) = p.tone.toLut8().let { lut -> FloatArray(256) { (lut[it].toInt() and 0xff) / 255f } }

fun main() {
    neutralStaysNeutral()
    monochrome()
    alphaAndThreads()
    crop()
    microTexture()
    if (fails > 0) {
        println("$fails fallos")
        exitProcess(1)
    }
    println("OK (foto)")
}

private fun neutralStaysNeutral() {
    for (p in listOf(ColorProfile.NATURAL, ColorProfile.VIBRANT, ColorProfile.NEUTRAL_FLAT)) {
        val t = tone(p)
        for (v in intArrayOf(20, 64, 128, 200, 245)) {
            val o = PhotoLook.pixel(rgb(v, v, v), p, t)
            val spread = maxOf(r(o), g(o), b(o)) - minOf(r(o), g(o), b(o))
            ok(spread <= 1, "${p.id}: gris $v se tiñe (${r(o)},${g(o)},${b(o)})")
        }
    }
    val t = tone(ColorProfile.NATURAL)
    val mid = PhotoLook.pixel(rgb(128, 128, 128), ColorProfile.NATURAL, t)
    ok(abs(g(mid) - 128) <= 12, "natural: gris medio se mueve demasiado (${g(mid)})")
    ok(g(PhotoLook.pixel(rgb(0, 0, 0), ColorProfile.NATURAL, t)) <= 3, "natural: negro levantado")
    ok(g(PhotoLook.pixel(rgb(255, 255, 255), ColorProfile.NATURAL, t)) >= 235, "natural: blanco apagado")
    var last = -1
    for (v in 0..255 step 5) {
        val o = g(PhotoLook.pixel(rgb(v, v, v), ColorProfile.NATURAL, t))
        ok(o >= last, "natural: curva no monótona en $v")
        last = o
    }
}

private fun monochrome() {
    val p = ColorProfile.MONOCHROME
    val t = tone(p)
    for (c in intArrayOf(rgb(220, 40, 30), rgb(30, 200, 60), rgb(40, 60, 230), rgb(210, 160, 120))) {
        val o = PhotoLook.pixel(c, p, t)
        ok(abs(r(o) - g(o)) <= 1 && abs(g(o) - b(o)) <= 1, "mono: color residual (${r(o)},${g(o)},${b(o)})")
    }
    val red = g(PhotoLook.pixel(rgb(255, 0, 0), p, t))
    val green = g(PhotoLook.pixel(rgb(0, 255, 0), p, t))
    ok(green > red, "mono: el verde debe ser más claro que el rojo (Rec.709)")
}

private fun alphaAndThreads() {
    val w = 37
    val h = 23
    val src = IntArray(w * h) { i -> (0x80 shl 24) or ((i * 7919) and 0xffffff) }
    val one = src.copyOf()
    val many = src.copyOf()
    PhotoLook.apply(one, w, h, ColorProfile.CINEMATIC_WARM, 1)
    PhotoLook.apply(many, w, h, ColorProfile.CINEMATIC_WARM, 6)
    ok(one.contentEquals(many), "apply: el resultado depende de la cantidad de hilos")
    ok(one.all { (it ushr 24) == 0x80 }, "apply: alfa alterado")
}

private fun crop() {
    fun eq(a: IntArray, vararg b: Int) = a.contentEquals(b)
    ok(eq(PhotoLook.cropRect(4000, 3000, 4f / 3f), 0, 0, 4000, 3000), "4:3 sobre 4:3 no recorta")
    ok(eq(PhotoLook.cropRect(4000, 3000, 16f / 9f), 0, 375, 4000, 2250), "16:9 apaisado")
    ok(eq(PhotoLook.cropRect(3000, 4000, 16f / 9f), 375, 0, 2250, 4000), "16:9 vertical")
    ok(eq(PhotoLook.cropRect(4000, 3000, 1f), 500, 0, 3000, 3000), "1:1 apaisado")
    ok(eq(PhotoLook.cropRect(3000, 4000, 1f), 0, 500, 3000, 3000), "1:1 vertical")
}

private fun microTexture() {
    val w = 32
    val h = 32
    val img1 = IntArray(w * h) { i -> (0xff shl 24) or ((i % w * 7 + i / w * 11) and 0xff) * 0x010101 }
    val img2 = img1.copyOf()
    PhotoLook.enhanceMicroTexture(img1, w, h, 0.28f, 1)
    PhotoLook.enhanceMicroTexture(img2, w, h, 0.28f, 4)
    ok(img1.contentEquals(img2), "microTexture: determinante entre hilos")
    ok(img1.all { (it ushr 24) == 0xff }, "microTexture: alfa intacto")
}
