package com.signature.cinematic.gl;

final class Shaders {
    private Shaders() {}

    /**
     * Círculo de confusión 0..1 desde los mapas de profundidad (1 = cerca) y persona.
     * Los mapas tienen la fila 0 arriba: se invierte v. Igual que DepthMath.coc.
     */
    private static final String COC =
            "uniform sampler2D uDepth;\n"
            + "uniform sampler2D uMask;\n"
            + "uniform float uFocus;\n"
            + "uniform float uMaskFocus;\n"
            + "const float TOL = 0.04;\n"
            + "const float GAIN = 3.5;\n"
            + "float depthAt(vec2 uv) { return texture(uDepth, vec2(uv.x, 1.0 - uv.y)).r; }\n"
            + "float cocAt(vec2 uv) {\n"
            + "  float m = texture(uMask, vec2(uv.x, 1.0 - uv.y)).r;\n"
            + "  return clamp((abs(depthAt(uv) - uFocus) - TOL) * GAIN, 0.0, 1.0) * (1.0 - m * uMaskFocus);\n"
            + "}\n";

    /** Reduce el cuadro graduado a 1/4 y guarda el círculo de confusión en alfa. */
    static final String CINE_PREP =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D uGraded;\n"
            + "uniform vec2 uTexel;\n"
            + COC
            + "in vec2 vUv;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  vec3 c = 0.25 * (texture(uGraded, vUv + uTexel * vec2(-1.0, -1.0)).rgb\n"
            + "      + texture(uGraded, vUv + uTexel * vec2(1.0, -1.0)).rgb\n"
            + "      + texture(uGraded, vUv + uTexel * vec2(-1.0, 1.0)).rgb\n"
            + "      + texture(uGraded, vUv + uTexel * vec2(1.0, 1.0)).rgb);\n"
            + "  o = vec4(c, cocAt(vUv));\n"
            + "}\n";

    /**
     * Desenfoque de disco con muestreo en espiral (ángulo áureo). Las muestras más lejanas que el
     * centro no pueden extenderse sobre él más del doble de su propio radio: el fondo no invade al sujeto.
     */
    static final String CINE_BLUR =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D uQ;\n"
            + "uniform sampler2D uDepth;\n"
            + "uniform vec2 uTexel;\n"
            + "uniform float uMaxR;\n"
            + "in vec2 vUv;\n"
            + "out vec4 o;\n"
            + "float depthAt(vec2 uv) { return texture(uDepth, vec2(uv.x, 1.0 - uv.y)).r; }\n"
            + "void main() {\n"
            + "  vec4 c0 = texture(uQ, vUv);\n"
            + "  float cd = depthAt(vUv);\n"
            + "  float cs = c0.a * uMaxR;\n"
            + "  vec3 color = c0.rgb;\n"
            + "  float tot = 1.0;\n"
            + "  float radius = 1.0;\n"
            + "  float ang = 0.0;\n"
            + "  for (int i = 0; i < 160; i++) {\n"
            + "    if (radius >= uMaxR) break;\n"
            + "    vec2 tc = vUv + vec2(cos(ang), sin(ang)) * uTexel * radius;\n"
            + "    vec4 s = texture(uQ, tc);\n"
            + "    float ss = s.a * uMaxR;\n"
            + "    if (depthAt(tc) < cd) ss = min(ss, cs * 2.0);\n"
            + "    float m = smoothstep(radius - 0.5, radius + 0.5, ss);\n"
            + "    color += mix(color / tot, s.rgb, m);\n"
            + "    tot += 1.0;\n"
            + "    radius += 1.5 / radius;\n"
            + "    ang += 2.39996323;\n"
            + "  }\n"
            + "  o = vec4(color / tot, c0.a);\n"
            + "}\n";

    /** Mezcla nítido y desenfocado por píxel con el círculo de confusión a resolución completa. */
    static final String CINE_COMPOSITE =
            "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D uGraded;\n"
            + "uniform sampler2D uBlur;\n"
            + COC
            + "in vec2 vUv;\n"
            + "out vec4 o;\n"
            + "void main() {\n"
            + "  float c = cocAt(vUv);\n"
            + "  vec3 sharp = texture(uGraded, vUv).rgb;\n"
            + "  vec3 soft = texture(uBlur, vUv).rgb;\n"
            + "  o = vec4(mix(sharp, soft, smoothstep(0.02, 0.3, c)), 1.0);\n"
            + "}\n";

    /**
     * Cuadro congelado durante el cambio de cámara o salto de lente físico con zoom-lerp
     * y disolvencia cruzada suave (cross-fade a 60 fps) contra el nuevo sensor.
     */
    static final String FREEZE_BLUR =
            "#version 300 es\n"
            + "precision mediump float;\n"
            + "uniform sampler2D uFreeze;\n"
            + "uniform float uAlpha;\n"
            + "uniform vec2 uTexel;\n"
            + "uniform float uZoomScale;\n"
            + "in vec2 vUv;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "  vec2 uv = clamp((vUv - vec2(0.5)) / max(0.25, uZoomScale) + vec2(0.5), 0.0, 1.0);\n"
            + "  vec3 c = 0.44 * texture(uFreeze, uv).rgb\n"
            + "         + 0.14 * (texture(uFreeze, clamp(uv + vec2(uTexel.x * 1.5, 0.0), 0.0, 1.0)).rgb\n"
            + "                 + texture(uFreeze, clamp(uv - vec2(uTexel.x * 1.5, 0.0), 0.0, 1.0)).rgb\n"
            + "                 + texture(uFreeze, clamp(uv + vec2(0.0, uTexel.y * 1.5), 0.0, 1.0)).rgb\n"
            + "                 + texture(uFreeze, clamp(uv - vec2(0.0, uTexel.y * 1.5), 0.0, 1.0)).rgb);\n"
            + "  fragColor = vec4(c, uAlpha);\n"
            + "}\n";

    static final String VERTEX =
            "#version 300 es\n"
            + "layout(location = 0) in vec4 aPos;\n"
            + "layout(location = 1) in vec4 aTex;\n"
            + "uniform mat4 uTexMatrix;\n"
            + "uniform mat4 uMvp;\n"
            + "out vec2 vUv;\n"
            + "void main() {\n"
            + "  gl_Position = uMvp * aPos;\n"
            + "  vUv = (uTexMatrix * aTex).xy;\n"
            + "}\n";

    /**
     * Pipeline de color en un solo pase. Con uBypass=1 devuelve la imagen del ISP sin tocar
     * (se usa para medir la escena).
     */
    static final String FRAGMENT =
            "#version 300 es\n"
            + "#extension GL_OES_EGL_image_external_essl3 : require\n"
            + "precision highp float;\n"
            + "uniform samplerExternalOES uTex;\n"
            + "uniform sampler2D uTone;\n"
            + "uniform mat3 uBase;\n"
            + "uniform mat3 uLook;\n"
            + "uniform float uGain;\n"
            + "uniform vec3 uSat;\n"
            + "uniform float uSkin;\n"
            + "uniform vec3 uSkinUndertone;\n"
            + "uniform float uSkinAnchor;\n"
            + "uniform float uTonePad;\n"
            + "uniform float uColorPad;\n"
            + "uniform float uSoftSkin;\n"
            + "uniform float uGlow;\n"
            + "uniform float uGrain;\n"
            + "uniform float uLensMorph;\n"
            + "uniform vec2 uInvSize;\n"
            + "uniform float uSeed;\n"
            + "uniform vec3 uShadowTint;\n"
            + "uniform vec3 uHighTint;\n"
            + "uniform float uMono;\n"
            + "uniform float uGamut;\n"
            + "uniform float uDceStrength;\n"
            + "uniform float uBypass;\n"
            + "in vec2 vUv;\n"
            + "out vec4 fragColor;\n"
            + "const vec3 W = vec3(0.2126, 0.7152, 0.0722);\n"
            + "vec3 toLin(vec3 c) {\n"
            + "  return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));\n"
            + "}\n"
            + "vec3 toGam(vec3 c) {\n"
            + "  c = max(c, 0.0);\n"
            + "  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));\n"
            + "}\n"
            + "float toLin1(float c) { return c < 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }\n"
            + "float toGam1(float c) { return c < 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }\n"
            // Peso de tono de piel a partir del color ya calibrado (antes del look).
            + "float skinWeight(vec3 lin) {\n"
            + "  vec3 g = toGam(lin);\n"
            + "  float cb = -0.1146 * g.r - 0.3854 * g.g + 0.5 * g.b;\n"
            + "  float cr = 0.5 * g.r - 0.4542 * g.g - 0.0458 * g.b;\n"
            + "  float chroma = length(vec2(cb, cr));\n"
            + "  float hue = atan(cr, cb);\n"
            + "  return exp(-(hue - 2.15) * (hue - 2.15) / 0.18)\n"
            + "      * smoothstep(0.02, 0.06, chroma) * (1.0 - smoothstep(0.25, 0.35, chroma));\n"
            + "}\n"
            // Comprime el croma que se sale de [0,1] por canal en vez de recortarlo (evita colores neón y virajes).
            + "vec3 gamutCompress(vec3 c, float yl, float amount) {\n"
            + "  vec3 d = c - vec3(yl);\n"
            + "  vec3 room = mix(vec3(yl), vec3(1.0 - yl), step(vec3(0.0), d));\n"
            + "  float r = max(max(abs(d.r) / max(room.r, 1e-4), abs(d.g) / max(room.g, 1e-4)), abs(d.b) / max(room.b, 1e-4));\n"
            + "  const float knee = 0.8;\n"
            + "  if (r <= knee) return c;\n"
            + "  float rc = knee + (1.0 - knee) * tanh((r - knee) / (1.0 - knee));\n"
            + "  return mix(c, vec3(yl) + d * (rc / r), amount);\n"
            + "}\n"
            // Zero-DCE++: Curvas cuadráticas de iluminación adaptativas a la escena
            // LE_n = LE_{n-1} + A * LE_{n-1} * (1 - LE_{n-1})
            + "vec3 applyZeroDce(vec3 rgb, float strength) {\n"
            + "  if (strength <= 0.01) return rgb;\n"
            + "  float luma = clamp(dot(rgb, W), 0.0, 1.0);\n"
            + "  float alpha = strength * clamp(1.0 - luma * 1.25, 0.0, 1.0);\n"
            + "  vec3 cur = rgb;\n"
            + "  for (int i = 0; i < 3; i++) {\n"
            + "    cur = cur + alpha * cur * (vec3(1.0) - cur);\n"
            + "  }\n"
            + "  return clamp(cur, 0.0, 1.0);\n"
            + "}\n"
            + "void main() {\n"
            + "  vec3 src = texture(uTex, vUv).rgb;\n"
            + "  if (uBypass > 0.5) { fragColor = vec4(src, 1.0); return; }\n"
            // Micro-fusión óptica radial al conmutar entre lentes físicas (.5x <-> 1x <-> 3x):
            // rastro radial Gaussiano de 6 muestras hacia el eje óptico que suaviza el salto de paralaje en video.
            + "  if (uLensMorph > 0.01) {\n"
            + "    vec2 radial = (vUv - vec2(0.5)) * (uLensMorph * 0.032);\n"
            + "    vec3 c0 = src * 0.28;\n"
            + "    c0 += texture(uTex, clamp(vUv - radial * 0.20, 0.0, 1.0)).rgb * 0.22;\n"
            + "    c0 += texture(uTex, clamp(vUv - radial * 0.45, 0.0, 1.0)).rgb * 0.18;\n"
            + "    c0 += texture(uTex, clamp(vUv - radial * 0.70, 0.0, 1.0)).rgb * 0.14;\n"
            + "    c0 += texture(uTex, clamp(vUv + radial * 0.25, 0.0, 1.0)).rgb * 0.10;\n"
            + "    c0 += texture(uTex, clamp(vUv + radial * 0.50, 0.0, 1.0)).rgb * 0.08;\n"
            + "    src = mix(src, c0, min(0.90, uLensMorph * 1.15));\n"
            + "  }\n"
            + "  float rawSkin = skinWeight(toLin(src));\n"
            // Photographic Styles 3 (iPhone 18 Pro): Soft Skin (suavizado bilateral selectivo en poros/manchas
            // dentro de la máscara de piel, preservando bordes de ojos, pestañas, labios y pelo) + Glow.
            + "  if ((uSoftSkin > 0.01 && rawSkin > 0.04) || uGlow > 0.01) {\n"
            + "    vec2 stepUv = uInvSize * 1.45;\n"
            + "    vec3 s0 = texture(uTex, vUv + vec2(stepUv.x, stepUv.y)).rgb;\n"
            + "    vec3 s1 = texture(uTex, vUv + vec2(-stepUv.x, stepUv.y)).rgb;\n"
            + "    vec3 s2 = texture(uTex, vUv + vec2(stepUv.x, -stepUv.y)).rgb;\n"
            + "    vec3 s3 = texture(uTex, vUv + vec2(-stepUv.x, -stepUv.y)).rgb;\n"
            + "    vec3 avg = 0.25 * (s0 + s1 + s2 + s3);\n"
            + "    vec3 d = src - avg;\n"
            + "    float edge = dot(d, d);\n"
            + "    float bilateral = exp(-edge / 0.0045);\n"
            + "    float softAmt = uSoftSkin * rawSkin * bilateral * 0.68;\n"
            + "    src = mix(src, avg, softAmt);\n"
            + "    if (uGlow > 0.01) {\n"
            + "      float glowLuma = max(0.0, dot(avg, W) - 0.38);\n"
            + "      src += avg * (glowLuma * uGlow * (0.14 + 0.12 * rawSkin));\n"
            + "    }\n"
            + "  }\n"
            + "  vec3 base = uBase * toLin(src) * uGain;\n"
            + "  float baseLuma = max(dot(base, W), 1e-5);\n"
            + "  float skin = skinWeight(base) * uSkin;\n"
            // Ciencia de Color Leica con preservación estricta de luminancia Rec.709:
            // suprime el viraje al magenta artificial y el amarillo verdoso sin oscurecer el canal verde (G).
            + "  float magentaExcess = max(0.0, (base.r + base.b) * 0.5 - base.g * 1.06);\n"
            + "  float greenExcess = max(0.0, base.g - (base.r * 0.62 + base.b * 0.38));\n"
            + "  vec3 leicaSkinBase = vec3(\n"
            + "      base.r + greenExcess * 0.18 - magentaExcess * 0.10,\n"
            + "      base.g - greenExcess * 0.16 + magentaExcess * 0.14,\n"
            + "      base.b - magentaExcess * 0.22 + greenExcess * 0.05);\n"
            + "  vec3 skinTone = leicaSkinBase * uSkinUndertone * vec3(1.0 + 0.025 * uColorPad, 1.0, 1.0 - 0.020 * uColorPad);\n"
            + "  skinTone *= (baseLuma * (1.0 + 0.035 * rawSkin)) / max(dot(skinTone, W), 1e-5);\n"
            + "  vec3 c = mix(uLook * base, skinTone, skin);\n"
            + "  float y = max(dot(c, W), 1e-5);\n"
            // Leica Highlight Roll-Off con knee alto (0.88) para preservar blancos puros y luminosos sin quemar rostros
            + "  const float knee = 0.88;\n"
            + "  float yc = y <= knee ? y : knee + (1.0 - knee) * tanh((y - knee) / (1.0 - knee));\n"
            + "  float yt = texture(uTone, vec2(toGam1(yc) * (255.0 / 256.0) + 0.5 / 256.0, 0.5)).r;\n"
            // Claridad luminosa en medios tonos: abre sombras y medios tonos sin hundir el punto gris
            + "  float midBell = 4.0 * yt * (1.0 - yt);\n"
            + "  float midLift = midBell * 0.038 + (yt - 0.36) * midBell * 0.028 * (1.0 - 0.60 * skin);\n"
            + "  yt = clamp(yt + midLift, 0.0, 1.0);\n"
            // Control Pad 2D (Tono): modifica sombras y medios de la escena anclando la luminancia de la piel.
            + "  if (abs(uTonePad) > 0.01) {\n"
            + "    float padCurve = pow(clamp(yt, 0.0, 1.0), 1.0 - uTonePad * 0.28);\n"
            + "    yt = mix(padCurve, yt, skin * uSkinAnchor);\n"
            + "  }\n"
            // Anclaje de luminancia de piel del perfil (evita caras oscuras cuando la escena tiene alto contraste).
            + "  float ySkinSafe = mix(yt, max(yt, toGam1(yc)), skin * uSkinAnchor * 0.50);\n"
            + "  float yl = toLin1(ySkinSafe);\n"
            + "  c *= yl / y;\n"
            // 1. Saturación por zonas (sombras, medios, altas) con protección de piel
            + "  float wS = 1.0 - smoothstep(0.02, 0.18, yl);\n"
            + "  float wH = smoothstep(0.45, 0.88, yl);\n"
            + "  float sat = mix(mix(uSat.y, uSat.x, wS), uSat.z, wH) * (1.0 + uColorPad * 0.22);\n"
            + "  sat = mix(sat, 1.0 + uColorPad * 0.06, skin);\n"
            + "  c = vec3(yl) + (c - vec3(yl)) * sat;\n"
            // 2. Tintes de sombras y altas luces
            + "  c *= mix(vec3(1.0), mix(uShadowTint, uHighTint, smoothstep(0.05, 0.6, yl)), 1.0 - 0.7 * skin);\n"
            // 3. Altas luces: desaturación progresiva hacia blanco neutro (estética fílmica Leica / haluro de plata)
            + "  float hlFactor = clamp((yl - 0.78) / (0.99 - 0.78), 0.0, 1.0);\n"
            + "  float hlDesat = 1.0 - pow(hlFactor, 1.30) * 0.85;\n"
            + "  c = mix(vec3(yl), c, hlDesat);\n"
            // 4. Viñeta óptica ultra-sutil (máximo 4% en bordes extremos para no oscurecer la escena)
            + "  vec2 vigUv = vUv - vec2(0.5);\n"
            + "  float vigR2 = dot(vigUv, vigUv);\n"
            + "  float vignette = 1.0 - vigR2 * 0.05 * (1.0 - 0.70 * hlFactor) * (1.0 - uMono * 0.15);\n"
            + "  c *= clamp(vignette, 0.95, 1.0);\n"
            // 4. Soft-clipping y compresión de gamut (evita clipping duro por canal en 1.0)
            + "  float m = max(c.r, max(c.g, c.b));\n"
            + "  if (m > 1.0) { c = mix(c, vec3(yl), clamp((m - 1.0) / max(m - yl, 1e-4), 0.0, 1.0)); }\n"
            + "  if (uGamut > 0.0) { c = gamutCompress(c, yl, uGamut); }\n"
            + "  c = mix(c, vec3(yl), uMono);\n"
            + "  vec3 outGam = clamp(toGam(c), 0.0, 1.0);\n"
            + "  if (uDceStrength > 0.01) {\n"
            + "    outGam = applyZeroDce(outGam, uDceStrength);\n"
            + "  }\n"
            + "  if (uGrain > 0.01) {\n"
            + "    float n = fract(sin(dot(vUv + vec2(uSeed), vec2(12.9898, 78.233))) * 43758.5453) - 0.5;\n"
            + "    float midWeight = 4.0 * outGam.g * (1.0 - outGam.g);\n"
            + "    outGam = clamp(outGam + vec3(n * uGrain * 0.075 * midWeight), 0.0, 1.0);\n"
            + "  }\n"
            + "  fragColor = vec4(outGam, 1.0);\n"
            + "}\n";
}
