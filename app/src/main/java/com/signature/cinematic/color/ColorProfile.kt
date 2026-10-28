package com.signature.cinematic.color

/**
 * Perfil "Signature" + subtonos de piel de Photographic Styles 3 (iPhone 18 Pro / iOS 27).
 * Separa el look de la escena ([matrix], [tone]) del subtono dedicado de la piel ([skinUndertone])
 * y del anclaje de luminancia facial ([skinLumaAnchor]).
 */
class ColorProfile private constructor(
    @JvmField val id: String,
    @JvmField val name: String,
    @JvmField val tone: ToneCurve,
    /** Matriz creativa 3x3 en RGB lineal para la escena, por filas. */
    @JvmField val matrix: FloatArray,
    @JvmField val satShadow: Float,
    @JvmField val satMid: Float,
    @JvmField val satHigh: Float,
    /** 0..1: cuánto se protege la piel frente a la matriz/saturación de la escena. */
    @JvmField val skinProtect: Float,
    /** Multiplicador RGB lineal exclusivo para la máscara de piel (subtono Ámbar, Oro Rosado, Dorado, Rosa Frío). */
    @JvmField val skinUndertone: FloatArray,
    /** 0..1: cuánto se ancla la luminancia de la piel para evitar caras oscuras o lavadas al cambiar el contraste. */
    @JvmField val skinLumaAnchor: Float,
    @JvmField val shadowTint: FloatArray,
    @JvmField val highlightTint: FloatArray,
    @JvmField val mono: Float,
    /** 0..1: compresión suave de croma cerca del borde del gamut BT.709 en lugar de recortar. */
    @JvmField val gamutCompress: Float,
    @JvmField val description: String,
) {
    @JvmField val version = "signature-look-v3-ios27"

    companion object {
        private val NEUTRAL_TONE = ToneCurve(1.0f, 0.45f, 0.72f, 0.55f, 0.0f, 1.0f)
        private val ONE = floatArrayOf(1f, 1f, 1f)
        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        private fun profile(
            id: String,
            name: String,
            description: String,
            tone: ToneCurve = NEUTRAL_TONE,
            matrix: FloatArray = IDENTITY,
            sat: FloatArray = ONE,
            skin: Float = 0f,
            skinUndertone: FloatArray = ONE,
            skinAnchor: Float = 0.55f,
            shadowTint: FloatArray = ONE,
            highlightTint: FloatArray = ONE,
            mono: Float = 0f,
            gamut: Float = 0f,
        ) = ColorProfile(id, name, tone, matrix, sat[0], sat[1], sat[2], skin, skinUndertone, skinAnchor,
            shadowTint, highlightTint, mono, gamut, description)

        /** Leica Authentic / Signature Pro: colorimetría real D65 sin dominante cálida/magenta en blancos, piel fiel y roll-off natural. */
        @JvmField
        val SIGNATURE_PRO = profile(
            "pro", "Signature Leica Authentic",
            "Leica Authentic: blancos y grises neutros D65, tonos de piel fieles y roll-off suave en altas luces.",
            tone = ToneCurve(1.015f, 0.44f, 0.76f, 0.35f, 0.0f, 1.0f),
            matrix = IDENTITY,
            sat = floatArrayOf(0.99f, 1.02f, 0.97f), skin = 0.75f,
            skinUndertone = floatArrayOf(1.004f, 1.000f, 0.996f), skinAnchor = 0.60f,
            shadowTint = ONE, highlightTint = ONE,
            gamut = 0.35f,
        )

        /** Subtono de piel Ámbar (Photographic Styles 3). */
        @JvmField
        val SKIN_AMBER = profile(
            "amber", "Signature Ámbar",
            "Subtono de piel Ámbar: realza tonos dorados y cálidos en la piel manteniendo los blancos limpios.",
            tone = ToneCurve(1.04f, 0.43f, 0.67f, 0.84f, 0.0f, 0.99f),
            matrix = whiteShift(1.022f, 1.0f, 0.968f),
            sat = floatArrayOf(0.96f, 1.05f, 0.92f), skin = 0.90f,
            skinUndertone = floatArrayOf(1.045f, 0.996f, 0.945f), skinAnchor = 0.78f,
            highlightTint = floatArrayOf(1.012f, 1.0f, 0.985f), gamut = 0.65f,
        )

        /** Subtono de piel Oro Rosado (Photographic Styles 3). */
        @JvmField
        val SKIN_ROSE_GOLD = profile(
            "rose_gold", "Signature Oro Rosado",
            "Subtono Oro Rosado: luminosidad cálida-rosada en piel con altas luces cremosas.",
            tone = ToneCurve(1.01f, 0.44f, 0.65f, 0.86f, 0.0f, 0.995f),
            matrix = whiteShift(1.018f, 0.995f, 0.992f),
            sat = floatArrayOf(0.95f, 1.02f, 0.90f), skin = 0.92f,
            skinUndertone = floatArrayOf(1.042f, 0.984f, 0.988f), skinAnchor = 0.82f,
            highlightTint = floatArrayOf(1.015f, 0.996f, 0.995f), gamut = 0.65f,
        )

        /** Subtono de piel Dorado (Photographic Styles 3). */
        @JvmField
        val SKIN_GOLD = profile(
            "gold", "Signature Dorado",
            "Subtono Dorado: calidez radiante en medios tonos de piel con sombras suaves.",
            tone = ToneCurve(1.02f, 0.43f, 0.66f, 0.85f, 0.0f, 0.99f),
            matrix = whiteShift(1.026f, 1.002f, 0.962f),
            sat = floatArrayOf(0.95f, 1.04f, 0.91f), skin = 0.90f,
            skinUndertone = floatArrayOf(1.038f, 1.002f, 0.952f), skinAnchor = 0.78f,
            gamut = 0.65f,
        )

        /** Subtono de piel Rosa Frío (Photographic Styles 3). */
        @JvmField
        val SKIN_COOL_ROSE = profile(
            "cool_rose", "Signature Rosa Frío",
            "Subtono Rosa Frío: porcelana limpia que elimina dominantes amarillas en luz interior.",
            tone = ToneCurve(1.02f, 0.44f, 0.68f, 0.82f, 0.0f, 0.995f),
            matrix = whiteShift(0.992f, 0.998f, 1.018f),
            sat = floatArrayOf(0.94f, 1.01f, 0.92f), skin = 0.90f,
            skinUndertone = floatArrayOf(1.016f, 0.986f, 1.012f), skinAnchor = 0.76f,
            gamut = 0.60f,
        )

        @JvmField
        val NATURAL = profile(
            "natural", "Signature Natural",
            "Saturación 1.0, contraste 1.0, curva neutra con caída suave de altas luces.",
            skin = 0.5f, gamut = 0.3f,
        )

        @JvmField
        val NATURAL_WARM = profile(
            "natural_warm", "Signature Warm Natural",
            "Calidez creativa después del balance; piel protegida y altas luces sin dominante.",
            matrix = whiteShift(1.03f, 1.0f, 0.96f),
            skin = 0.6f, highlightTint = floatArrayOf(0.99f, 1.0f, 1.015f), gamut = 0.3f,
        )

        @JvmField
        val NATURAL_COOL = profile(
            "natural_cool", "Signature Cool Natural",
            "Más frío sin piel azulada ni blancos cian.",
            matrix = whiteShift(0.97f, 1.0f, 1.035f),
            skin = 0.7f, highlightTint = floatArrayOf(1.01f, 1.0f, 0.99f), gamut = 0.3f,
        )

        @JvmField
        val VIBRANT = profile(
            "vibrant", "Signature Leica Vibrant",
            "Leica Vibrant: cromatismo profundo con micro-contraste tridimensional, piel orgánica protegida y roll-off fílmico.",
            tone = ToneCurve(1.08f, 0.44f, 0.70f, 0.72f, 0.0f, 0.995f),
            sat = floatArrayOf(1.03f, 1.16f, 0.96f), skin = 0.84f,
            skinUndertone = floatArrayOf(1.020f, 0.994f, 0.982f), skinAnchor = 0.76f,
            gamut = 0.82f,
        )

        @JvmField
        val CINEMATIC_WARM = profile(
            "cine_warm", "Signature Cinematic Warm",
            "Contraste moderado, altas luces suaves, sombras algo frías, medios cálidos; negros sin levantar.",
            tone = ToneCurve(0.97f, 0.42f, 0.62f, 0.8f, 0.0f, 0.98f),
            matrix = whiteShift(1.02f, 1.0f, 0.97f),
            sat = floatArrayOf(0.9f, 0.95f, 0.88f), skin = 0.8f,
            shadowTint = floatArrayOf(0.98f, 1.0f, 1.025f), highlightTint = floatArrayOf(1.025f, 1.0f, 0.97f),
            gamut = 0.5f,
        )

        @JvmField
        val CINEMATIC_COOL = profile(
            "cine_cool", "Signature Cinematic Cool",
            "Sombras frías, piel natural, saturación controlada.",
            tone = ToneCurve(0.97f, 0.42f, 0.62f, 0.8f, 0.0f, 0.98f),
            matrix = whiteShift(0.98f, 1.0f, 1.025f),
            sat = floatArrayOf(0.88f, 0.94f, 0.88f), skin = 0.85f,
            shadowTint = floatArrayOf(0.97f, 1.0f, 1.035f), highlightTint = floatArrayOf(0.995f, 1.0f, 1.005f),
            gamut = 0.5f,
        )

        /** Bajo contraste estilo Apple Log 2 suave para graduar. */
        @JvmField
        val NEUTRAL_FLAT = profile(
            "flat", "Signature Log Flat",
            "Contraste bajo con rango extendido en sombras y altas luces, nitidez orgánica sin halos.",
            tone = ToneCurve(0.82f, 0.45f, 0.6f, 0.9f, 0.06f, 0.94f),
            sat = floatArrayOf(0.85f, 0.88f, 0.82f),
        )

        @JvmField
        val MONOCHROME = profile(
            "mono", "Signature Monochrome",
            "Luminancia Rec.709 con curva de contraste; sin canal de color dominante.",
            tone = ToneCurve(1.1f, 0.45f, 0.7f, 0.6f, 0.0f, 1.0f),
            mono = 1f,
        )

        @JvmField
        val ALL = arrayOf(
            SIGNATURE_PRO, SKIN_AMBER, SKIN_ROSE_GOLD, SKIN_GOLD, SKIN_COOL_ROSE,
            NATURAL, NATURAL_WARM, NATURAL_COOL, VIBRANT,
            CINEMATIC_WARM, CINEMATIC_COOL, NEUTRAL_FLAT, MONOCHROME,
        )

        @JvmStatic
        fun byId(id: String?): ColorProfile = ALL.firstOrNull { it.id == id } ?: SIGNATURE_PRO

        /** Diagonal normalizada para que el gris medio conserve su luminancia (Rec.709). */
        @JvmStatic
        fun whiteShift(r: Float, g: Float, b: Float): FloatArray {
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            return floatArrayOf(r / y, 0f, 0f, 0f, g / y, 0f, 0f, 0f, b / y)
        }
    }
}
