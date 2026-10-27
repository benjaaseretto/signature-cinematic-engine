package com.signature.cinematic.color

import com.signature.cinematic.color.ColorScience.Lab
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Calibración de una lente física bajo un iluminante. Inmutable: cada cambio crea una versión nueva del conjunto. */
class LensColorProfile(
    val physicalId: String,
    val illuminant: Illuminant,
    val tonemap: IspTonemap,
    val matrix: DoubleArray,
    val logRG: Double,
    val logBG: Double,
    val before: ErrorStats,
    val after: ErrorStats,
    val validation: ErrorStats?,
    val temporalStd: Double,
    val correctedLab: List<Lab>,
    val createdAt: Long,
    val conditions: String,
    val reference: String,
) {
    fun withValidation(v: ErrorStats) = LensColorProfile(physicalId, illuminant, tonemap, matrix, logRG, logBG,
        before, after, v, temporalStd, correctedLab, createdAt, conditions, reference)
}

/**
 * Calibración con carta ColorChecker por lente e iluminante, versionada y reversible.
 *
 * Archivos en [dir]: `v<N>.json` (conjunto completo, nunca se sobrescribe) y `active` (número de versión
 * activa; 0 = sin calibración → identidad).
 */
class ColorCalibrationService(private val dir: File, var reference: ColorChecker = ColorChecker.CLASSIC_2005_D50) {
    @Volatile
    private var profiles: List<LensColorProfile> = emptyList()

    @Volatile
    var activeVersion = 0
        private set

    init {
        dir.mkdirs()
        File(dir, "reference_lab.json").takeIf { it.isFile }?.let { f ->
            runCatching {
                val arr = JSONObject(f.readText()).getJSONArray("lab")
                reference = ColorChecker.fromList("medida propia (${f.name})",
                    List(arr.length()) { i -> arr.getJSONArray(i).let { doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } })
            }
        }
        activeVersion = File(dir, "active").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0
        profiles = load(activeVersion)
    }

    fun profiles(): List<LensColorProfile> = profiles

    fun versions(): List<Int> = dir.listFiles().orEmpty()
        .mapNotNull { Regex("v(\\d+)\\.json").matchEntire(it.name)?.groupValues?.get(1)?.toInt() }.sorted()

    fun isCalibrated(physicalId: String?, tonemap: IspTonemap) =
        profiles.any { it.physicalId == physicalId && it.tonemap == tonemap }

    /** Matriz 3x3 (por filas) para la lente y el AWB actual. Identidad si no hay calibración compatible. */
    fun matrixFor(physicalId: String?, tonemap: IspTonemap, gainR: Float, gainG: Float, gainB: Float): DoubleArray {
        val candidates = profiles.filter { it.physicalId == physicalId && it.tonemap == tonemap }
        if (candidates.isEmpty() || gainG <= 0f) return ColorScience.identity()
        return CalibrationMath.interpolate(
            candidates.map { doubleArrayOf(it.logRG, it.logBG) to it.matrix },
            kotlin.math.ln((gainR / gainG).toDouble()), kotlin.math.ln((gainB / gainG).toDouble()),
        )
    }

    /** Ajusta una matriz con la toma de entrenamiento. No guarda: ver [save]. */
    fun fit(capture: ChartCapture): LensColorProfile {
        val r = CalibrationMath.fit(capture, reference)
        return LensColorProfile(
            physicalId = capture.physicalId,
            illuminant = capture.illuminant,
            tonemap = capture.tonemap,
            matrix = r.matrix,
            logRG = capture.logRG,
            logBG = capture.logBG,
            before = r.before,
            after = r.after,
            validation = null,
            temporalStd = CalibrationMath.temporalStd(capture.neutralPerFrame),
            correctedLab = r.correctedLab,
            createdAt = System.currentTimeMillis(),
            conditions = capture.conditions,
            reference = reference.name,
        )
    }

    /** Error con una toma que no se usó para ajustar (otra posición, otra exposición u otro momento). */
    fun validate(profile: LensColorProfile, capture: ChartCapture): ErrorStats =
        CalibrationMath.evaluate(profile.matrix, capture.patches, reference)

    /** Guarda como nueva versión reemplazando la entrada de la misma lente, iluminante y curva. */
    @Synchronized
    fun save(profile: LensColorProfile, notes: String): Int {
        val next = (versions().maxOrNull() ?: 0) + 1
        val set = profiles.filterNot {
            it.physicalId == profile.physicalId && it.illuminant == profile.illuminant && it.tonemap == profile.tonemap
        } + profile
        val root = JSONObject()
            .put("version", next)
            .put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            .put("notes", notes)
            .put("previous", activeVersion)
            .put("profiles", JSONArray(set.map { toJson(it) }))
        File(dir, "v$next.json").writeText(root.toString(2))
        activate(next)
        return next
    }

    /** Vuelve a la versión activa anterior (la registrada en "previous"). */
    @Synchronized
    fun revert(): Int {
        val prev = File(dir, "v$activeVersion.json").takeIf { it.isFile }
            ?.let { JSONObject(it.readText()).optInt("previous", 0) } ?: 0
        activate(prev)
        return prev
    }

    @Synchronized
    fun activate(version: Int) {
        profiles = load(version)
        activeVersion = version
        File(dir, "active").writeText(version.toString())
    }

    /** Diferencia entre lentes ya calibradas bajo el mismo iluminante (misma carta corregida por cada una). */
    fun interLensReport(): List<String> {
        val out = mutableListOf<String>()
        for (group in profiles.groupBy { it.illuminant to it.tonemap }.values) {
            for (i in group.indices) for (j in i + 1 until group.size) {
                val de = CalibrationMath.interLensDeltaE(group[i].correctedLab, group[j].correctedLab)
                out += "lente ${group[i].physicalId} vs ${group[j].physicalId} (${group[i].illuminant.label}): ΔE00 medio %.2f".format(de)
            }
        }
        return out
    }

    fun report(p: LensColorProfile): String = buildString {
        appendLine("Lente física ${p.physicalId} · ${p.illuminant.label} · curva ISP ${p.tonemap}")
        appendLine("Referencia: ${p.reference}")
        appendLine("Antes (sin matriz): ${p.before.summary()}")
        appendLine("Después (ajuste):   ${p.after.summary()}")
        appendLine(p.validation?.let { "Validación:        ${it.summary()}" } ?: "Validación: pendiente (capturar otra toma sin usarla para ajustar)")
        appendLine("Estabilidad temporal del neutro 5: σ ΔE00 %.2f".format(p.temporalStd))
        appendLine("Condiciones: ${p.conditions}")
        appendLine("Por parche (ΔE00 después):")
        p.after.perPatch.forEachIndexed { i, d -> appendLine("  %2d %-16s %.2f".format(i + 1, ColorChecker.NAMES[i], d)) }
    }

    private fun load(version: Int): List<LensColorProfile> {
        if (version <= 0) return emptyList()
        val f = File(dir, "v$version.json")
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("profiles")
            List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun statsJson(s: ErrorStats) = JSONObject()
        .put("mean", s.mean).put("p95", s.p95).put("max", s.max).put("skin", s.skin).put("gray", s.gray)
        .put("perPatch", JSONArray(s.perPatch.toList()))

    private fun statsFrom(o: JSONObject): ErrorStats {
        val pp = o.getJSONArray("perPatch")
        return ErrorStats(o.getDouble("mean"), o.getDouble("p95"), o.getDouble("max"), o.getDouble("skin"),
            o.getDouble("gray"), DoubleArray(pp.length()) { pp.getDouble(it) })
    }

    private fun toJson(p: LensColorProfile) = JSONObject()
        .put("physicalId", p.physicalId)
        .put("illuminant", p.illuminant.name)
        .put("tonemap", p.tonemap.name)
        .put("matrix", JSONArray(p.matrix.toList()))
        .put("logRG", p.logRG).put("logBG", p.logBG)
        .put("before", statsJson(p.before))
        .put("after", statsJson(p.after))
        .put("validation", p.validation?.let { statsJson(it) } ?: JSONObject.NULL)
        .put("temporalStd", p.temporalStd)
        .put("correctedLab", JSONArray(p.correctedLab.map { JSONArray(listOf(it.l, it.a, it.b)) }))
        .put("createdAt", p.createdAt)
        .put("conditions", p.conditions)
        .put("reference", p.reference)

    private fun fromJson(o: JSONObject): LensColorProfile {
        val m = o.getJSONArray("matrix")
        val labs = o.getJSONArray("correctedLab")
        return LensColorProfile(
            physicalId = o.getString("physicalId"),
            illuminant = Illuminant.valueOf(o.getString("illuminant")),
            tonemap = IspTonemap.valueOf(o.getString("tonemap")),
            matrix = DoubleArray(9) { m.getDouble(it) },
            logRG = o.getDouble("logRG"),
            logBG = o.getDouble("logBG"),
            before = statsFrom(o.getJSONObject("before")),
            after = statsFrom(o.getJSONObject("after")),
            validation = o.optJSONObject("validation")?.let { statsFrom(it) },
            temporalStd = o.optDouble("temporalStd", 0.0),
            correctedLab = List(labs.length()) { i -> labs.getJSONArray(i).let { Lab(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } },
            createdAt = o.getLong("createdAt"),
            conditions = o.optString("conditions"),
            reference = o.optString("reference"),
        )
    }
}
