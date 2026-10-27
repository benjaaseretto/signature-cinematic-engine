package com.signature.cinematic.cinema

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Modelo TFLite de una entrada y una salida float32 (NHWC). Intenta la GPU (fp16, con caché de
 * compilación) y cae a CPU con XNNPACK. Debe usarse siempre desde el mismo hilo.
 */
class TfliteModel(
    context: Context,
    asset: String,
    private val inputSize: Int,
    private val outputSize: Int,
    preferGpu: Boolean,
    cpuThreads: Int,
) : AutoCloseable {
    private var gpu: GpuDelegate? = null
    private val interpreter: Interpreter
    val backend: String
    private val input = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(outputSize * outputSize * 4).order(ByteOrder.nativeOrder())
    var lastMs = 0f
        private set

    init {
        val model = map(context, asset)
        var created: Interpreter? = null
        var label = "CPU"
        if (preferGpu) {
            try {
                val compat = CompatibilityList()
                if (compat.isDelegateSupportedOnThisDevice) {
                    val opts: GpuDelegateFactory.Options = compat.bestOptionsForThisDevice
                        .setPrecisionLossAllowed(true)
                        .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                        .setSerializationParams(File(context.cacheDir, "tflite").apply { mkdirs() }.path, asset.replace('/', '_'))
                    val d = GpuDelegate(opts)
                    created = Interpreter(model, Interpreter.Options().addDelegate(d))
                    gpu = d
                    label = "GPU"
                }
                compat.close()
            } catch (t: Throwable) {
                Log.w(TAG, "GPU no disponible para $asset", t)
                gpu?.close()
                gpu = null
                created = null
            }
        }
        interpreter = created ?: Interpreter(model, Interpreter.Options().setNumThreads(cpuThreads).setUseXNNPACK(true))
        backend = label
    }

    /** [rgb]: inputSize²×3 en [0,1]; [out]: outputSize² valores. */
    fun run(rgb: FloatArray, out: FloatArray) {
        input.rewind()
        input.asFloatBuffer().put(rgb)
        output.rewind()
        val t0 = System.nanoTime()
        interpreter.run(input, output)
        lastMs = (System.nanoTime() - t0) / 1e6f
        output.rewind()
        output.asFloatBuffer().get(out)
    }

    override fun close() {
        interpreter.close()
        gpu?.close()
    }

    companion object {
        private const val TAG = "TfliteModel"

        /** Mapea el modelo sin copiarlo si el asset va sin comprimir; si no, lo copia una vez a los archivos de la app. */
        private fun map(context: Context, asset: String): MappedByteBuffer {
            try {
                context.assets.openFd(asset).use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.use { ch ->
                        return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    }
                }
            } catch (e: java.io.IOException) {
                val f = File(context.filesDir, asset.replace('/', '_'))
                if (!f.isFile) context.assets.open(asset).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                FileInputStream(f).channel.use { ch -> return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
            }
        }
    }
}
