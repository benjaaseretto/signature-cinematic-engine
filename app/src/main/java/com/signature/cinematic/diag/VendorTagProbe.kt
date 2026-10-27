package com.signature.cinematic.diag

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/**
 * Claves propias del HAL (com.lenovo.moto.*, org.codeaurora.*, org.quic.*) que esta app puede ver.
 * Cruza lo que cada cámara declara con los nombres que usa la app de cámara de Motorola
 * (assets/diag/moto_vendor_tags.txt). Que una clave aparezca o que la sesión se acepte con ella
 * no prueba que el HAL la aplique: el HAL puede ignorarla según el paquete que la pide.
 */
class VendorTagProbe(private val context: Context) {
    private val cameras: CameraManager = context.getSystemService(CameraManager::class.java)!!

    class Tag(
        val camera: String,
        val physicalOf: String?,
        val name: String,
        val usedByMoto: Boolean,
        val inCharacteristics: Boolean,
        val inRequest: Boolean,
        val inResult: Boolean,
        val inSession: Boolean,
        val staticValue: String?,
        val sessionCheck: String?,
    ) {
        val visible get() = inCharacteristics || inRequest || inResult || inSession
    }

    val motoTags: List<String> by lazy {
        runCatching { context.assets.open("diag/moto_vendor_tags.txt").bufferedReader().readLines() }
            .getOrDefault(emptyList()).map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun scan(): List<Tag> {
        val out = mutableListOf<Tag>()
        for (id in cameras.cameraIdList) {
            val cc = cameras.getCameraCharacteristics(id)
            scanCamera(id, null, cc, out)
            for (pid in cc.physicalCameraIds) scanCamera(pid, id, cameras.getCameraCharacteristics(pid), out)
        }
        return out
    }

    private fun scanCamera(id: String, physicalOf: String?, cc: CameraCharacteristics, out: MutableList<Tag>) {
        val request = cc.availableCaptureRequestKeys.filter { isVendor(it.name) }.associateBy { it.name }
        val result = cc.availableCaptureResultKeys.filter { isVendor(it.name) }.map { it.name }.toSet()
        val session = cc.availableSessionKeys.filter { isVendor(it.name) }.associateBy { it.name }
        val moto = motoTags.toSet()
        // getKeys() oculta las claves propias: se leen por nombre probando cada tipo nativo.
        val listed = cc.keys.filter { isVendor(it.name) }.map { it.name }
        val statics = (listed + moto).distinct().mapNotNull { n -> readStatic(cc, n)?.let { n to it } }.toMap()

        val setup = if (physicalOf == null && cameras.isCameraDeviceSetupSupported(id)) cameras.getCameraDeviceSetup(id) else null
        val baseline = setup?.let { s -> runCatching { sessionSupported(s, null, null) }.getOrNull() }

        val names = (statics.keys + request.keys + result + session.keys + moto).toSortedSet()
        for (name in names) {
            val sessionCheck = session[name]?.let { k ->
                if (UNSAFE.any { name.contains(it) }) "no probada (inyección de fallas)" else checkSessionKey(setup, baseline, k)
            }
            out += Tag(
                camera = id, physicalOf = physicalOf, name = name, usedByMoto = name in moto,
                inCharacteristics = name in statics, inRequest = name in request, inResult = name in result,
                inSession = name in session, staticValue = statics[name], sessionCheck = sessionCheck,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readStatic(cc: CameraCharacteristics, name: String): String? {
        for (type in STATIC_TYPES) {
            val v = try {
                cc.get(CameraCharacteristics.Key(name, type as Class<Any>))
            } catch (_: RuntimeException) {
                continue
            } ?: return null
            return show(v)
        }
        return null
    }

    private fun checkSessionKey(setup: CameraDevice.CameraDeviceSetup?, baseline: Boolean?, key: CaptureRequest.Key<*>): String {
        if (setup == null) return "sin CameraDeviceSetup"
        if (baseline != true) return "la sesión base ya es rechazada"
        for (value in CANDIDATES) {
            val ok = try {
                sessionSupported(setup, key, value)
            } catch (_: ClassCastException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: UnsupportedOperationException) {
                continue
            } catch (t: Throwable) {
                return "error: ${t.javaClass.simpleName}: ${t.message}"
            }
            return (if (ok) "aceptada" else "rechazada") + " con ${show(value)}"
        }
        return "tipo no admitido por la prueba"
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionSupported(setup: CameraDevice.CameraDeviceSetup, key: CaptureRequest.Key<*>?, value: Any?): Boolean {
        val oc = OutputConfiguration(PREVIEW, SurfaceTexture::class.java)
        val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oc))
        val b = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        if (key != null) b.set(key as CaptureRequest.Key<Any>, value)
        sc.setSessionParameters(b.build())
        return setup.isSessionConfigurationSupported(sc)
    }

    // ---------------------------------------------------------------- salidas

    fun toCsv(tags: List<Tag>): String = buildString {
        appendLine("camara,fisica_de,clave,usada_por_motorola,caracteristica,pedido,resultado,sesion,valor_estatico,prueba_sesion")
        for (t in tags) {
            appendLine(listOf(t.camera, t.physicalOf ?: "", t.name, t.usedByMoto, t.inCharacteristics, t.inRequest,
                t.inResult, t.inSession, t.staticValue ?: "", t.sessionCheck ?: "").joinToString(",") { csv(it.toString()) })
        }
    }

    fun toJson(tags: List<Tag>): JSONArray = JSONArray(tags.filter { it.visible || it.usedByMoto }.map { t ->
        JSONObject()
            .put("camera", t.camera).put("physical_of", t.physicalOf ?: JSONObject.NULL).put("name", t.name)
            .put("used_by_moto", t.usedByMoto).put("characteristics", t.inCharacteristics).put("request", t.inRequest)
            .put("result", t.inResult).put("session", t.inSession)
            .put("static_value", t.staticValue ?: JSONObject.NULL).put("session_check", t.sessionCheck ?: JSONObject.NULL)
    })

    fun markdown(tags: List<Tag>): String = buildString {
        appendLine("## Claves propias del HAL (Motorola / Qualcomm)")
        appendLine()
        val ids = cameras.cameraIdList.toList()
        appendLine("Cámaras visibles para esta app: " + ids.joinToString { id ->
            val phys = cameras.getCameraCharacteristics(id).physicalCameraIds
            if (phys.isEmpty()) id else "$id (físicas ${phys.joinToString("/")})"
        })
        appendLine()
        val byCamera = tags.groupBy { it.camera to it.physicalOf }
        for ((cam, list) in byCamera) {
            val label = cam.second?.let { "${cam.first} (física de $it)" } ?: cam.first
            val visibleMoto = list.count { it.usedByMoto && it.visible }
            appendLine("- Cámara $label: ${list.count { it.inCharacteristics }} características, " +
                "${list.count { it.inRequest }} de pedido, ${list.count { it.inResult }} de resultado, " +
                "${list.count { it.inSession }} de sesión · usadas por Motorola y visibles: $visibleMoto de ${motoTags.size}")
        }
        appendLine()

        val sessionTests = tags.filter { it.sessionCheck != null }
        if (sessionTests.isNotEmpty()) {
            appendLine("### Claves de sesión probadas (salida PRIVATE ${PREVIEW}, TEMPLATE_RECORD)")
            for (t in sessionTests) appendLine("- cámara ${t.camera} · ${t.name}: ${t.sessionCheck}")
            appendLine()
        }

        val main = byCamera.keys.firstOrNull { it.second == null }
        val mainTags = main?.let { byCamera[it] }.orEmpty()
        appendLine("### Usadas por Motorola y visibles (cámara ${main?.first})")
        for (t in mainTags.filter { it.usedByMoto && it.visible }) {
            val where = listOfNotNull("C".takeIf { t.inCharacteristics }, "P".takeIf { t.inRequest },
                "R".takeIf { t.inResult }, "S".takeIf { t.inSession }).joinToString("")
            appendLine("- ${t.name} [$where]" + (t.staticValue?.let { " = $it" } ?: ""))
        }
        appendLine()
        val hidden = mainTags.filter { it.usedByMoto && !it.visible }
        appendLine("### Usadas por Motorola y no declaradas a esta app (cámara ${main?.first}): ${hidden.size}")
        for (t in hidden) appendLine("- ${t.name}")
        appendLine()
        appendLine("C = característica, P = pedido, R = resultado, S = sesión.")
    }

    companion object {
        private val PREVIEW = Size(1920, 1080)
        private val CANDIDATES: List<Any> = listOf(1, 1.toByte(), 1L, 1f, 1.0, intArrayOf(1), byteArrayOf(1))
        private val STATIC_TYPES: List<Class<*>> = listOf(
            IntArray::class.java, ByteArray::class.java, FloatArray::class.java, LongArray::class.java, DoubleArray::class.java,
        )
        private val UNSAFE = listOf("Induce")
        private val VENDOR_PREFIXES = listOf("com.lenovo.", "com.motorola.", "org.codeaurora.", "org.quic.", "com.qti.", "vendor.")

        fun isVendor(name: String) = VENDOR_PREFIXES.any { name.startsWith(it) }

        private fun show(v: Any?): String = when (v) {
            null -> "null"
            is ByteArray -> if (v.size > 16) "byte[${v.size}]" else v.contentToString()
            is IntArray -> v.contentToString()
            is LongArray -> v.contentToString()
            is FloatArray -> v.contentToString()
            is DoubleArray -> v.contentToString()
            is BooleanArray -> v.contentToString()
            is Array<*> -> v.contentDeepToString()
            else -> v.toString()
        }.let { if (it.length > 240) it.take(240) + "…" else it }

        private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
