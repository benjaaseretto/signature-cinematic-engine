package com.signature.cinematic.cinema

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.signature.cinematic.gl.CinematicSource

/**
 * Modo Cine: profundidad (Depth Anything V2 Small) + segmentación de personas (MediaPipe Selfie)
 * en un hilo propio. El hilo GL entrega fotogramas cuando el hilo está libre y recoge los mapas.
 */
class CinematicProcessor(private val context: Context) : CinematicSource {
    private val thread = HandlerThread("cinematic", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var depthModel: TfliteModel? = null
    @Volatile private var maskModel: TfliteModel? = null
    private val depthBox = DepthMath.Letterbox(DEPTH_SIZE, FRAME_W, FRAME_H)
    private val maskBox = DepthMath.Letterbox(MASK_SIZE, FRAME_W, FRAME_H)
    private val depthIn = FloatArray(DEPTH_SIZE * DEPTH_SIZE * 3)
    private val depthOut = FloatArray(DEPTH_SIZE * DEPTH_SIZE)
    private val maskIn = FloatArray(MASK_SIZE * MASK_SIZE * 3)
    private val maskOut = FloatArray(MASK_SIZE * MASK_SIZE)
    private val normalizer = DepthMath.Normalizer()
    private val rack = DepthMath.FocusRack()

    @Volatile var enabled = false
        private set
    @Volatile private var busy = false
    @Volatile private var pending: CinematicSource.Maps? = null
    @Volatile private var focusTarget = 0.5f
    @Volatile private var maskFocusTarget = 0f
    @Volatile private var focusPoint: FloatArray? = null
    @Volatile var fNumber = 2.8f
    /** Intervalo mínimo entre inferencias; se alarga con calor. */
    @Volatile var minIntervalMs = 0L
    @Volatile var status = "apagado"
        private set

    private var lastStart = 0L
    private var lastFocusNs = 0L
    private var maskFocusCurrent = 0f
    private var mapsCount = 0
    private var mapsWindowStart = 0L
    @Volatile private var mapsPerSecond = 0f

    /** Carga los modelos la primera vez (puede tardar varios segundos; después usa la caché de la GPU). */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            pending = null
            status = "apagado"
            return
        }
        handler.post {
            normalizer.reset()
            rack.reset()
            if (depthModel != null) return@post
            status = "preparando modelos…"
            try {
                depthModel = TfliteModel(context, DEPTH_ASSET, DEPTH_SIZE, DEPTH_SIZE, preferGpu = true, cpuThreads = 4)
                maskModel = TfliteModel(context, MASK_ASSET, MASK_SIZE, MASK_SIZE, preferGpu = false, cpuThreads = 2)
                status = "listo (${depthModel?.backend})"
            } catch (t: Throwable) {
                Log.e(TAG, "no se pudieron cargar los modelos", t)
                status = "error: ${t.javaClass.simpleName}"
                enabled = false
            }
        }
    }

    /** Punto de foco en coordenadas del cuadro (0..1, origen arriba a la izquierda); null = automático. */
    fun setFocusPoint(u: Float, v: Float) {
        focusPoint = floatArrayOf(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    fun setAutoFocus() {
        focusPoint = null
    }

    val isAutoFocus get() = focusPoint == null

    fun summary(): String {
        val d = depthModel ?: return "CINE $status"
        return "CINE ${d.backend} %.0f ms · %.1f mapas/s · ƒ%.1f%s".format(d.lastMs, mapsPerSecond, fNumber,
            if (focusPoint == null) " · foco auto" else " · foco fijo")
    }

    fun release() {
        handler.post {
            depthModel?.close()
            maskModel?.close()
            depthModel = null
            maskModel = null
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------ CinematicSource (hilo GL)

    override fun wantsFrame(): Boolean {
        if (!enabled || busy || depthModel == null) return false
        return System.currentTimeMillis() - lastStart >= minIntervalMs
    }

    override fun onFrame(rgba: ByteArray, width: Int, height: Int, timestampNs: Long) {
        busy = true
        lastStart = System.currentTimeMillis()
        handler.post {
            try {
                process(rgba, width, height)
            } catch (t: Throwable) {
                Log.w(TAG, "inferencia", t)
            } finally {
                busy = false
            }
        }
    }

    override fun pollMaps(): CinematicSource.Maps? {
        val m = pending
        pending = null
        return m
    }

    override fun focusDepth(timestampNs: Long): Float {
        val dt = if (lastFocusNs == 0L) 0f else (timestampNs - lastFocusNs) / 1e9f
        lastFocusNs = timestampNs
        maskFocusCurrent += (maskFocusTarget - maskFocusCurrent) * (dt * 6f).coerceIn(0f, 1f)
        return rack.step(focusTarget, dt)
    }

    override fun maskFocus(): Float = maskFocusCurrent

    override fun maxBlurFraction(): Float = DepthMath.maxBlurFraction(fNumber)

    // ------------------------------------------------------------ hilo de inferencia

    private fun process(rgba: ByteArray, w: Int, h: Int) {
        val dm = depthModel ?: return
        val mm = maskModel
        DepthMath.toModelInput(rgba, w, h, depthBox, depthIn)
        dm.run(depthIn, depthOut)
        val depth = normalizer.apply(DepthMath.crop(depthOut, depthBox))

        var mask: FloatArray? = null
        if (mm != null) {
            DepthMath.toModelInput(rgba, w, h, maskBox, maskIn)
            mm.run(maskIn, maskOut)
            mask = DepthMath.crop(maskOut, maskBox)
            for (i in mask.indices) mask[i] = mask[i].coerceIn(0f, 1f)
        }
        val target = DepthMath.chooseFocus(depth, depthBox.contentW, depthBox.contentH,
            mask, maskBox.contentW, maskBox.contentH, focusPoint)
        focusTarget = target.depth
        maskFocusTarget = if (target.onPerson) 1f else 0f

        pending = CinematicSource.Maps(
            DepthMath.toBytes(depth), depthBox.contentW, depthBox.contentH,
            mask?.let { DepthMath.toBytes(it) } ?: ByteArray(1), if (mask != null) maskBox.contentW else 1,
            if (mask != null) maskBox.contentH else 1,
        )
        val now = System.currentTimeMillis()
        if (mapsWindowStart == 0L) mapsWindowStart = now
        mapsCount++
        if (now - mapsWindowStart >= 1000) {
            mapsPerSecond = mapsCount * 1000f / (now - mapsWindowStart)
            mapsCount = 0
            mapsWindowStart = now
        }
    }

    companion object {
        private const val TAG = "Cinematic"
        const val DEPTH_ASSET = "models/depth_anything_v2.tflite"
        const val MASK_ASSET = "models/mediapipe_selfie.tflite"
        const val DEPTH_SIZE = 518
        const val MASK_SIZE = 256
        /** Tamaño de la lectura cruda del hilo GL (GlRenderer.RAW_W × RAW_H). */
        const val FRAME_W = 270
        const val FRAME_H = 480
    }
}
