package com.signature.cinematic

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.signature.cinematic.color.ChartCapture
import com.signature.cinematic.color.ChartSampler
import com.signature.cinematic.color.ColorChecker
import com.signature.cinematic.color.Illuminant
import com.signature.cinematic.color.LensColorProfile
import com.signature.cinematic.diag.ReportWriter
import com.signature.cinematic.engine.CameraCaps
import com.signature.cinematic.engine.CaptureProfile
import com.signature.cinematic.engine.VideoEngine
import com.signature.cinematic.engine.VideoMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calibración con carta ColorChecker: mide la salida del ISP (antes de la pipeline de color) con
 * exposición y balance bloqueados, ajusta una matriz por lente e iluminante, valida con otra toma
 * y guarda una versión nueva (reversible).
 */
class CalibrationActivity : Activity(), VideoEngine.Ui {
    private var engine: VideoEngine? = null
    private var caps: CameraCaps? = null
    private lateinit var preview: SurfaceView
    private lateinit var guide: GuideView
    private lateinit var result: TextView
    private lateinit var illuminantChip: TextView
    private lateinit var purposeChip: TextView
    private lateinit var lensRow: LinearLayout
    private var illuminant = Illuminant.DAYLIGHT
    private var validation = false
    private var pending: LensColorProfile? = null
    private var measuring = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = SurfaceView(this)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
                engine?.setPreviewSurface(h.surface, w, hgt)
            }
            override fun surfaceDestroyed(h: SurfaceHolder) {
                engine?.clearPreviewSurface()
            }
        })
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        guide = GuideView(this)
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(8))
        }
        top.addView(label(12, Color.WHITE).apply {
            text = "Carta de frente, llenando la guía, parche marrón arriba a la izquierda. " +
                "Luz pareja sin reflejos; que ningún parche quede quemado."
        })
        val chips = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        illuminantChip = chip("") { cycleIlluminant() }
        purposeChip = chip("") { validation = !validation; refresh() }
        chips.addView(illuminantChip)
        chips.addView(purposeChip)
        top.addView(chips)
        lensRow = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        top.addView(lensRow)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val actions = LinearLayout(this)
        actions.addView(chip("MEDIR") { measure() })
        actions.addView(chip("GUARDAR") { save() })
        actions.addView(chip("REVERTIR") { revert() })
        actions.addView(chip("INFORME") { export() })
        bottom.addView(actions)
        result = label(11, Color.WHITE).apply { typeface = Typeface.MONOSPACE }
        val scroll = ScrollView(this).apply { addView(result) }
        bottom.addView(scroll, LinearLayout.LayoutParams(-1, dp(220)))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        try {
            val e = engine ?: VideoEngine(this, this).also { engine = it }
            val c = e.back ?: run { onError("No hay cámara trasera con modos validados"); return }
            caps = c
            val prefs = getSharedPreferences("engine", MODE_PRIVATE)
            val profile = CaptureProfile.byId(prefs.getString("capture", "A"))
            val mode: VideoMode = profile.resolve(c.modes) ?: c.modes[0]
            e.open(c, mode, profile)
            layoutPreview(mode)
            buildLensRow(c)
            showStatus("Perfil ${profile.name} · $mode\n${statusText()}")
        } catch (t: Exception) {
            onError("No se pudo abrir la cámara: $t")
        }
    }

    override fun onPause() {
        engine?.close()
        super.onPause()
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    private fun layoutPreview(m: VideoMode) {
        val w = resources.displayMetrics.widthPixels
        val h = Math.round(w * m.width.toFloat() / m.height)
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP).apply { topMargin = dp(80) }
        preview.layoutParams = lp
        guide.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP).apply { topMargin = dp(80) }
    }

    private fun buildLensRow(c: CameraCaps) {
        lensRow.removeAllViews()
        for (z in c.lensZooms) {
            lensRow.addView(chip(if (z < 1f) ".%d×".format(Math.round(z * 10)) else "%.0f×".format(z)) {
                engine?.setZoom(z)
            })
        }
    }

    private fun cycleIlluminant() {
        val all = Illuminant.values()
        illuminant = all[(illuminant.ordinal + 1) % all.size]
        refresh()
    }

    private fun refresh() {
        illuminantChip.text = illuminant.label
        purposeChip.text = if (validation) "VALIDACIÓN" else "AJUSTE"
    }

    private fun measure() {
        val e = engine ?: return
        if (measuring) return
        measuring = true
        val g = guide.normalizedGuide()
        val sampler = ChartSampler(g.left, g.top, g.right, g.bottom, FRAMES)
        showStatus("Midiendo $FRAMES fotogramas con exposición y balance bloqueados…")
        val conditions = "${illuminant.label} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())} · " +
            "${android.os.Build.MODEL}"
        e.captureChart(sampler, illuminant, conditions) { capture -> onCaptured(capture) }
    }

    private fun onCaptured(capture: ChartCapture?) {
        measuring = false
        val e = engine ?: return
        if (capture == null) {
            showStatus("Sin metadatos de la cámara: no se puede calibrar en este modo.")
            return
        }
        val warnings = qualityWarnings(capture)
        val service = e.calibration()
        if (!validation) {
            val p = service.fit(capture)
            pending = p
            showStatus(warnings + service.report(p) + "\nNo guardado todavía: GUARDAR crea una versión nueva.")
            return
        }
        val base = pending?.takeIf { it.physicalId == capture.physicalId && it.illuminant == capture.illuminant && it.tonemap == capture.tonemap }
            ?: service.profiles().firstOrNull { it.physicalId == capture.physicalId && it.illuminant == capture.illuminant && it.tonemap == capture.tonemap }
        if (base == null) {
            showStatus(warnings + "No hay ajuste para la lente ${capture.physicalId} con ${capture.illuminant.label}. Hacé primero un AJUSTE.")
            return
        }
        val v = service.validate(base, capture)
        pending = base.withValidation(v)
        showStatus(warnings + service.report(pending!!) + "\nGUARDAR registra la validación en una versión nueva.")
    }

    private fun qualityWarnings(c: ChartCapture): String = buildString {
        val clipped = c.clipped.indices.filter { c.clipped[it] }
        if (clipped.isNotEmpty()) {
            appendLine("AVISO: parches recortados (excluidos del ajuste): " + clipped.joinToString { ColorChecker.NAMES[it] })
        }
        val white = c.patches[18].max()
        if (white < 0.25) appendLine("AVISO: la carta está subexpuesta (blanco lineal %.2f); acercá luz.".format(white))
        val black = c.patches[23].max()
        if (black > 0.08) appendLine("AVISO: negro demasiado claro (%.3f): posible reflejo o velo.".format(black))
        if (isNotEmpty()) appendLine()
    }

    private fun save() {
        val e = engine ?: return
        val p = pending ?: run { showStatus("Nada para guardar: primero MEDIR."); return }
        val v = e.calibration().save(p, if (p.validation != null) "ajuste + validación" else "ajuste")
        pending = null
        showStatus("Guardada la versión $v.\n${statusText()}")
    }

    private fun revert() {
        val e = engine ?: return
        val v = e.calibration().revert()
        showStatus("Activa la versión $v${if (v == 0) " (sin calibración: identidad)" else ""}.\n${statusText()}")
    }

    private fun export() {
        val e = engine ?: return
        val s = e.calibration()
        val md = buildString {
            appendLine("# Calibración de color — versión ${s.activeVersion}")
            appendLine("Dispositivo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Referencia: ${s.reference.name}")
            appendLine()
            if (s.profiles().isEmpty()) appendLine("Sin perfiles calibrados.")
            for (p in s.profiles()) {
                appendLine("```")
                append(s.report(p))
                appendLine("```")
            }
            val inter = s.interLensReport()
            if (inter.isNotEmpty()) {
                appendLine("## Consistencia entre lentes")
                inter.forEach { appendLine("- $it") }
            }
        }
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val uri: Uri = ReportWriter.saveToDownloads(this, "calibracion_$stamp.md", "text/markdown", md)
            showStatus("Informe guardado en Descargas/SignatureCinematic ($uri)\n\n$md")
        } catch (t: Exception) {
            onError("No se pudo guardar el informe: $t")
        }
    }

    private fun statusText(): String {
        val s = engine?.calibration() ?: return ""
        val list = s.profiles().joinToString("\n") {
            "  lente ${it.physicalId} · ${it.illuminant.label} · ${it.tonemap} · ΔE00 %.2f (val %s)".format(
                it.after.mean, it.validation?.let { v -> "%.2f".format(v.mean) } ?: "—")
        }
        return "Versión activa ${s.activeVersion} (versiones: ${s.versions().ifEmpty { listOf(0) }.joinToString()})\n" +
            list.ifEmpty { "  sin perfiles" }
    }

    private fun showStatus(text: String) {
        result.text = text
    }

    override fun onMetrics(text: String, audioDb: Float) {}

    override fun onRecordingSaved(video: Uri?, ok: Boolean) {}

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun chip(text: String, onClick: () -> Unit) = label(12, Color.WHITE).apply {
        this.text = text
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { setColor(Color.argb(140, 40, 40, 40)); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun label(sp: Int, color: Int) = TextView(this).apply {
        textSize = sp.toFloat()
        setTextColor(color)
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    /** Guía 6×4 con la proporción de la zona de parches; marca la región central que se mide. */
    private class GuideView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.rgb(255, 204, 0)
            strokeWidth = 3f
        }
        private val cell = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(160, 255, 255, 255)
            strokeWidth = 1.5f
        }

        fun guideRect(): RectF {
            val w = width.toFloat()
            val h = height.toFloat()
            val gw = w * 0.88f
            val gh = gw / CHART_ASPECT
            return RectF((w - gw) / 2, (h - gh) / 2, (w + gw) / 2, (h + gh) / 2)
        }

        fun normalizedGuide(): RectF {
            val r = guideRect()
            return RectF(r.left / width, r.top / height, r.right / width, r.bottom / height)
        }

        override fun onDraw(canvas: Canvas) {
            val r = guideRect()
            canvas.drawRect(r, line)
            val cw = r.width() / ColorChecker.COLUMNS
            val ch = r.height() / ColorChecker.ROWS
            for (row in 0 until ColorChecker.ROWS) for (col in 0 until ColorChecker.COLUMNS) {
                val cx = r.left + (col + 0.5f) * cw
                val cy = r.top + (row + 0.5f) * ch
                canvas.drawRect(cx - cw * 0.2f, cy - ch * 0.2f, cx + cw * 0.2f, cy + ch * 0.2f, cell)
            }
        }
    }

    companion object {
        private const val FRAMES = 30
        /** Zona de parches de la ColorChecker Classic (6 × 4 parches con separación). */
        private const val CHART_ASPECT = 1.5f
    }
}
