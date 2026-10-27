package com.signature.cinematic

import com.signature.cinematic.cinema.DepthMath
import kotlin.math.abs
import kotlin.system.exitProcess

private var fails = 0

private fun ok(cond: Boolean, what: String) {
    if (!cond) {
        fails++
        println("FALLO: $what")
    }
}

fun main() {
    letterbox()
    modelInput()
    normalizer()
    focusChoice()
    rackFocus()
    cocAndAperture()
    if (fails > 0) {
        println("$fails fallos")
        exitProcess(1)
    }
    println("OK (cine)")
}

private fun letterbox() {
    val b = DepthMath.Letterbox(518, 270, 480)
    ok(b.contentH == 518 && b.contentW == 291, "cuadro vertical en 518: ${b.contentW}x${b.contentH}")
    ok(b.x0 == (518 - 291) / 2 && b.y0 == 0, "centrado: ${b.x0},${b.y0}")
    val m = DepthMath.Letterbox(256, 270, 480)
    ok(m.contentW == 144 && m.contentH == 256, "máscara 256: ${m.contentW}x${m.contentH}")
}

private fun modelInput() {
    val w = 270
    val h = 480
    val rgba = ByteArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val o = (y * w + x) * 4
        rgba[o] = (if (y < h / 2) 255 else 0).toByte()
        rgba[o + 1] = 0
        rgba[o + 2] = (if (y >= h / 2) 255 else 0).toByte()
    }
    val box = DepthMath.Letterbox(518, w, h)
    val input = FloatArray(518 * 518 * 3)
    DepthMath.toModelInput(rgba, w, h, box, input)
    fun px(x: Int, y: Int, c: Int) = input[(y * 518 + x) * 3 + c]
    ok(px(box.x0 + 100, 50, 0) > 0.99f && px(box.x0 + 100, 50, 2) < 0.01f, "arriba rojo (fila 0 arriba)")
    ok(px(box.x0 + 100, 470, 2) > 0.99f, "abajo azul")
    ok(abs(px(5, 200, 1) - 0.5f) < 1e-6, "bandas laterales grises")
    val out = FloatArray(518 * 518) { (it / 518).toFloat() }
    val crop = DepthMath.crop(out, box)
    ok(crop.size == 291 * 518 && crop[291 * 10] == 10f, "recorte conserva filas")
}

private fun normalizer() {
    val n = DepthMath.Normalizer()
    val raw = FloatArray(1000) { it.toFloat() }
    val a = n.apply(raw)
    ok(a.min() >= 0f && a.max() <= 1f, "normalizado a 0..1")
    ok(a[999] > a[0], "orden preservado (más disparidad = más cerca)")
    val noisy = FloatArray(1000) { it + if (it % 2 == 0) 30f else -30f }
    val b = n.apply(noisy)
    var jump = 0f
    for (i in b.indices) jump += abs(b[i] - a[i])
    ok(jump / b.size < 0.03f, "suavizado temporal limita el parpadeo: ${jump / b.size}")
    val other = FloatArray(1000) { (999 - it).toFloat() }
    val c = n.apply(other)
    ok(c[0] > 0.9f, "cambio de escena no arrastra el mapa anterior: ${c[0]}")
}

private fun focusChoice() {
    val dw = 30
    val dh = 50
    val depth = FloatArray(dw * dh) { 0.2f }
    val mw = 15
    val mh = 25
    val mask = FloatArray(mw * mh)
    for (y in 8 until 20) for (x in 4 until 11) mask[y * mw + x] = 1f
    for (y in 16 until 40) for (x in 8 until 22) depth[y * dw + x] = 0.8f
    val auto = DepthMath.chooseFocus(depth, dw, dh, mask, mw, mh, null)
    ok(auto.onPerson && abs(auto.depth - 0.8f) < 1e-6, "automático enfoca a la persona: ${auto.depth}")
    val tap = DepthMath.chooseFocus(depth, dw, dh, mask, mw, mh, floatArrayOf(0.05f, 0.05f))
    ok(!tap.onPerson && abs(tap.depth - 0.2f) < 1e-6, "toque en el fondo enfoca el fondo")
    val none = DepthMath.chooseFocus(depth, dw, dh, FloatArray(mw * mh), mw, mh, null)
    ok(!none.onPerson && abs(none.depth - 0.8f) < 1e-6, "sin persona enfoca el centro: ${none.depth}")
}

private fun rackFocus() {
    val r = DepthMath.FocusRack()
    r.step(0.2f, 0f)
    var t = 0f
    var v = 0.2f
    var prev = v
    var maxSpeed = 0f
    while (t < 2f) {
        v = r.step(0.9f, 1f / 30)
        maxSpeed = maxOf(maxSpeed, (v - prev) * 30)
        prev = v
        t += 1f / 30
    }
    ok(abs(v - 0.9f) < 0.01f, "llega al nuevo foco: $v")
    ok(maxSpeed <= 1.2f + 1e-4, "transición suave, sin saltos: $maxSpeed/s")
}

private fun cocAndAperture() {
    ok(DepthMath.coc(0.5f, 0.5f, 0f, 0f) == 0f, "en foco: nítido")
    ok(DepthMath.coc(0.52f, 0.5f, 0f, 0f) == 0f, "dentro de la banda de foco")
    ok(DepthMath.coc(0.0f, 0.5f, 0f, 0f) == 1f, "lejos: desenfoque máximo")
    ok(DepthMath.coc(0.0f, 0.5f, 1f, 1f) == 0f, "persona enfocada protegida por la máscara")
    ok(DepthMath.maxBlurFraction(1.4f) > DepthMath.maxBlurFraction(2.8f) &&
        DepthMath.maxBlurFraction(2.8f) > DepthMath.maxBlurFraction(16f), "más apertura = más desenfoque")
}
