package com.signature.cinematic.color

import com.signature.cinematic.color.ColorScience.Lab

/**
 * Referencia de la carta ColorChecker Classic de 24 parches (valores publicados "ColorChecker 2005",
 * L*a*b* D50). Si se mide la carta propia con espectrofotómetro, reemplazar con [fromList].
 * Orden: fila superior izquierda → derecha, de arriba abajo, como se ve la carta de frente.
 */
class ColorChecker(val name: String, val patches: List<Lab>) {
    init {
        require(patches.size == PATCH_COUNT)
    }

    companion object {
        const val PATCH_COUNT = 24
        const val COLUMNS = 6
        const val ROWS = 4
        val SKIN = intArrayOf(0, 1)
        val NEUTRALS = intArrayOf(18, 19, 20, 21, 22, 23)
        /** Gris neutro 8 (fila inferior, segundo): referencia de exposición para comparar. */
        const val EXPOSURE_PATCH = 19

        val NAMES = listOf(
            "piel oscura", "piel clara", "cielo", "follaje", "flor azul", "verde azulado",
            "naranja", "azul púrpura", "rojo moderado", "púrpura", "verde amarillo", "amarillo naranja",
            "azul", "verde", "rojo", "amarillo", "magenta", "cian",
            "blanco", "neutro 8", "neutro 6.5", "neutro 5", "neutro 3.5", "negro",
        )

        @JvmField
        val CLASSIC_2005_D50 = ColorChecker(
            "ColorChecker 2005 (D50)",
            listOf(
                Lab(37.986, 13.555, 14.059), Lab(65.711, 18.130, 17.810), Lab(49.927, -4.880, -21.925),
                Lab(43.139, -13.095, 21.905), Lab(55.112, 8.844, -25.399), Lab(70.719, -33.397, -0.199),
                Lab(62.661, 36.067, 57.096), Lab(40.020, 10.410, -45.964), Lab(51.124, 48.239, 16.248),
                Lab(30.325, 22.976, -21.587), Lab(72.532, -23.709, 57.255), Lab(71.941, 19.363, 67.857),
                Lab(28.778, 14.179, -50.297), Lab(55.261, -38.342, 31.370), Lab(42.101, 53.378, 28.190),
                Lab(81.733, 4.039, 79.819), Lab(51.935, 49.986, -14.574), Lab(51.038, -28.631, -28.638),
                Lab(96.539, -0.425, 1.186), Lab(81.257, -0.638, -0.335), Lab(66.766, -0.734, -0.504),
                Lab(50.867, -0.153, -0.270), Lab(35.656, -0.421, -1.231), Lab(20.461, -0.079, -0.973),
            ),
        )

        fun fromList(name: String, lab: List<DoubleArray>) =
            ColorChecker(name, lab.map { Lab(it[0], it[1], it[2]) })
    }
}
