# Signature Camera Engine

Motor de video para Motorola Signature (XT2603-2, Android 16): Camera2 → GPU (OpenGL ES 3) → MediaCodec.
Kotlin + Java, sin Android SDK. Las funciones se habilitan según lo que cada cámara declara; nada se fuerza.

> **Estado de verificación (v0.2.0):** todo compila, las pruebas de JVM pasan y los shaders validan con glslang.
> **Nada se probó todavía en el Motorola Signature**: `adb devices` no detecta un equipo autorizado.
> Las columnas "en dispositivo" quedan en *pendiente* hasta correr el diagnóstico y las tomas de prueba.

## Compilar, probar, diagnosticar

```bash
./build.sh                 # build/SignatureCinematic.apk (kotlinc + javac + d8 + apktool + firma de debug)
./test.sh                  # pruebas de JVM: exposición, perfiles, ΔE2000, ajuste de matriz, muestreo de carta
tools/device_report.sh     # informe ADB → reports/device_<modelo>_<fecha>/
adb install -r build/SignatureCinematic.apk
```

Herramientas: `~/rea-work/tools/{android-all.jar,r8.jar}`, `apktool`, `~/rea-work/uber-apk-signer.jar`, `kotlin` (brew).

## Fases (orden de la especificación)

| # | Fase | Código | JVM | En dispositivo |
|---|---|---|---|---|
| 1 | Capacidades reales | `diag/CameraCapabilityScanner.kt`, `diag/CapabilityProbe.java`, `tools/device_report.sh` | compila | pendiente |
| 2 | Perfiles de captura A–E | `engine/CaptureProfile.kt`, `control/ExposureController` (fracción de obturador, tope de ISO), `engine/CameraController` (NR/nitidez/curva por perfil) | probado | pendiente |
| 2 | Perfiles de color (8) | `color/ColorProfile.kt`, `color/ColorPipeline.kt`, `gl/Shaders` | probado (curvas, negros, gris) | pendiente |
| 3 | SDR estable | BT.709, HEVC Main, AAC 48 kHz | — | pendiente |
| 4 | Calibración con carta | `color/ColorCalibrationService.kt`, `CalibrationMath.kt`, `ColorScience.kt`, `ChartSampler.kt`, `CalibrationActivity.kt` | probado (ΔE2000, ajuste, muestreo) | pendiente: hace falta carta física |
| 5 | HDR (HLG/HDR10/Dolby Vision) | no implementado | — | el escáner informa si existe |
| 6 | NR temporal y estabilización propia | no implementado (se usa la del ISP) | — | — |
| 7 | Presets creativos y UI | presets sí; interfaz tipo iPhone sí (v0.4.0); controles manuales pro no | — | — |
| F | Cámara lenta | no implementado; el perfil F no aparece en la UI | — | el escáner informa si hay alta velocidad |
| + | Modo Cine (desenfoque de fondo) | `cinema/DepthMath.kt`, `cinema/TfliteModel.kt`, `cinema/CinematicProcessor.kt`, `gl/CinematicSource.java`, `gl/Shaders` (CINE_*), `gl/GlRenderer` | probado (letterbox, normalización, foco, rack, CoC); shaders validados | pendiente |
| + | Interfaz tipo iPhone + Foto, Retrato, Time-lapse | `MainActivity.java`, `ui/*`, `engine/CameraController` (JPEG/RAW), `record/PhotoSaver.java`, `color/PhotoLook.kt`, `gl/GlRenderer` (captura de cuadro) | probado (estilos sobre JPEG, recortes 4:3/16:9/1:1) | pendiente |

## Interfaz (v0.4.0)

Disposición de la app de cámara del iPhone, con solo lo que el Signature puede hacer de verdad.

| Modo | Qué hace | Cómo |
|---|---|---|
| FOTO | JPEG del ISP a resolución máxima 4:3; RAW (DNG) opcional | sesión Camera2 con JPEG + RAW_SENSOR, precaptura AE para flash, `TEMPLATE_STILL_CAPTURE`, NR/nitidez HQ |
| RETRATO | Foto con desenfoque de fondo | cuadro del Modo Cine (profundidad + máscara de persona) leído de la GPU a la resolución de vista previa (≤1920 px de lado largo) |
| VIDEO | 4K/HD/8K a 24/30/60 según la cámara | igual que antes; tocar `4K` o `30` en la barra superior cambia el formato |
| CINE | Video con desenfoque y foco elegible | Modo Cine; ƒ en la barra superior |
| TIME-LAPSE | 15× más rápido, sin audio | graba 1 de cada 15 cuadros a 30 fps y comprime las marcas de tiempo |

- **Barra superior:** flash (Auto/Sí/No en foto, linterna en video), `RAW`, formato de video, ƒ, chevron para abrir el panel.
- **Panel (chevron):** flash, temporizador 3/10 s, proporción 4:3/16:9/1:1, exposición ±2 EV, estilos (los 8 perfiles Signature), RAW, ƒ, estabilización (No/Estándar/Acción), ajustes (formato A–E, exposición de video, métricas, calibración, diagnóstico).
- **Zoom:** un botón por lente física más 2× (recorte del principal) y el doble del tele, hasta el máximo de la cámara. Tocar 1× otra vez alterna 24/28/35 mm. Pellizcar para zoom continuo.
- **Gestos:** tocar enfoca y mide; arrastrar vertical ajusta la exposición (sol); mantener presionado bloquea AE/AF; deslizar horizontal cambia de modo; doble toque en Cine vuelve al foco automático. Los botones de volumen disparan.
- **Guardado:** `Pictures/SignatureCamera` (JPEG + DNG) y `Movies/SignatureCamera`. La miniatura abre la última captura.

Limitaciones de la interfaz y los modos de foto:

- Sin probar en el Motorola Signature (no había dispositivo conectado al compilar).
- Los estilos en foto se aplican en CPU sobre la JPEG del ISP (no sobre RAW); con estilo Natural la JPEG queda intacta. La calibración por carta no se aplica a fotos.
- Retrato sale a la resolución de la vista previa, no a la del sensor, y con los mismos halos que el Modo Cine.
- RAW solo si la cámara lógica entrega RAW_SENSOR junto con JPEG en la misma sesión; si no, se avisa y se guarda solo JPEG. El DNG no lleva estilo ni recorte.
- Flash y linterna dependen de que la lente activa tenga flash; en Retrato no hay flash.
- No están: Cámara lenta, Panorámica, Live Photos, Modo Noche, Macro automático, Dolby Vision, ProRes/Log y video espacial. No se muestran en la interfaz hasta tener implementación real.

## Modo Cine

Desenfoque de fondo grabado en el video, con foco elegible y transición de foco.

- **Profundidad:** Depth Anything V2 Small (TFLite, ~99 MB, entrada 518×518) sobre LiteRT 1.4.2, con delegado GPU (fp16) y caída a CPU/XNNPACK. Da disparidad relativa, no metros.
- **Personas:** MediaPipe Selfie Segmentation (256×256). La máscara protege a la persona enfocada para que no se ablande el borde.
- **Foco:** por defecto la persona más grande (si ocupa >2% del cuadro); si no, el centro. Tocar fija el foco en ese punto; con doble toque vuelve al automático. El cambio de foco es una transición suave (máx. 1,2 unidades de profundidad/s).
- **Apertura:** el chip `ƒx.x` recorre ƒ1.4–ƒ16 y escala el radio máximo de desenfoque.
- **GPU:** cuadro graduado → preparación a 1/4 de resolución (CoC en alfa) → bokeh por ángulo áureo → composición por píxel según CoC. Se aplica igual a la vista previa y al encoder.
- **Uso:** modo `CINE` (video) o `RETRATO` (foto) en el carrusel. Los modelos se cargan al activarlo. La superposición muestra backend, ms por inferencia y si hay persona.

Limitaciones del Modo Cine:

- La profundidad llega con 1–3 cuadros de retraso respecto del video (inferencia a ~270×480). En movimiento rápido el borde del desenfoque puede quedar atrás.
- Pueden aparecer halos en bordes finos (pelo, ramas, vidrio): es profundidad monocular, sin sensor ToF.
- El desenfoque se hace en espacio gamma, no lineal: las luces puntuales quedan menos "bokeh" que en óptica real.
- Solo hasta 4K. En 8K se desactiva solo.
- Con temperatura alta la inferencia baja a 4 por segundo.
- El rendimiento real (ms por cuadro, GPU vs CPU) no está medido en el Motorola Signature.

## 1. Escáner de capacidades

La tabla cruza cámara (lógicas y físicas) × resolución (8K, 4K, 1080p, 720p) × fps (24/30/60/120/240) ×
rango dinámico × formato (PRIVATE, YUV_420_888, P010) × estabilización × codificador × orientación.

- **Cámara**: `CameraDeviceSetup.isSessionConfigurationSupported` valida la sesión sin abrir la cámara.
  Se prueba primero con el stream use case VIDEO_RECORD y, si falla, sin él. Las físicas usan `setPhysicalCameraId`.
  Las sesiones de 120/240 fps se prueban con `SESSION_HIGH_SPEED`.
- **Codificador**: `MediaCodecInfo.areSizeAndRateSupported` para HEVC 8 bits, HEVC Main10, AVC, AV1 y Dolby Vision (solo por hardware).
- **Estados**: CONFIRMADO, SOLO CÁMARA, SOLO CODIFICADOR, NO VERIFICABLE (el HAL no responde) y NO.
- **Salida** (DIAG): `compatibilidad_<fecha>.csv` y el informe `.json`/`.md`, con qué perfiles A–F se pueden usar, en Descargas/SignatureCinematic.

## 2. Perfiles de captura

| Perfil | Modos (en orden) | Obturador | ISO máx. | NR / nitidez ISP | Estab. | Exposición | Look | Curva ISP |
|---|---|---|---|---|---|---|---|---|
| A Natural | 4K30, 1080p30 | 1/60 | 3200 | HQ / FAST | estándar | CINE | Natural | ISP |
| B Acción | 4K60, 1080p60 | 1/120 | 6400 | FAST / FAST | máx. | AUTO | Natural | ISP |
| C Cinematográfico | 4K24, 4K30, 1080p24, 1080p30 | 1/48 o 1/60 | 3200 | HQ / OFF | estándar | CINE | Neutral Flat | sRGB |
| D Nocturno | 4K30, 1080p30 | hasta 1/30 | 6400 | HQ / FAST | estándar | CINE | Natural | ISP |
| E Detalle | 8K30 | 1/60 | 1600 | MINIMAL / FAST | no | CINE | Natural | sRGB |
| F Cámara lenta | 4K120, 1080p120, 1080p240 | — | — | — | — | — | — | no implementado |

- Si se alcanza el tope de ISO, el obturador se alarga hasta la duración de cuadro antes de subexponer.
- En interiores (ISO > 400) se usan múltiplos de 10 ms contra el parpadeo de 50 Hz.
- El ISP solo recibe los niveles de NR y nitidez que declara; si no, FAST. La alta calidad se desactiva con calor o por encima de 30 fps.
- El chip de perfil solo ofrece los perfiles cuyo modo admite la cámara actual.

## Color

El orden por fotograma está en `ColorPipeline`:

1. **ISP**: demosaico, balance de sensor, CCM, NR, nitidez y curva (por defecto, o sRGB en C y E).
2. **GPU, base**: contra-ganancia temporal del AWB × matriz de calibración de la lente física activa (interpolada por iluminante).
3. **GPU, look**: matriz creativa, atenuada en tonos de piel.
4. Curva de luminancia sin viraje de tono.
5. Desaturación hacia blanco en altas luces.
6. Saturación por zona, con la piel protegida.
7. Tintes de sombras y altas luces.
8. Compresión de croma en el borde del gamut BT.709.
9. Codificación BT.709 SDR.

Los 8 perfiles Signature son Natural, Warm Natural, Cool Natural, Vibrant, Cinematic Warm, Cinematic Cool, Neutral Flat y Monochrome.

- Ninguno levanta negros salvo Neutral Flat.
- Neutral Flat es bajo contraste y no se presenta como Log.
- Son looks: no corrigen el sensor. Eso lo hace la calibración.

**HDR**: solo SDR BT.709 está implementado (`OutputTransfer`). No se ofrece HLG, HDR10 ni Dolby Vision. Cuando el escáner confirme el perfil de 10 bits en Camera2 y Main10 en el codificador, se agrega como fase 5.

## 4. Calibración con ColorChecker

**Pantalla CAL.** La carta de 24 parches se encuadra en la guía, con el parche marrón arriba a la izquierda. Se elige el iluminante (luz día, cálida o LED) y si la toma es de **AJUSTE** o de **VALIDACIÓN**.

**Medición.** Se promedian 30 fotogramas con AE y AWB bloqueados:

- se lee la salida del ISP antes de la pipeline de color;
- de cada celda se mide solo el 40 % central;
- los parches recortados se excluyen;
- se avisa si el blanco está subexpuesto o el negro tiene velo.

**Ajuste.** Matriz 3×3 por mínimos cuadrados ponderados (piel ×3, neutros ×2), normalizada para que el blanco quede neutro. La exposición se normaliza con el neutro 8: el brillo no cuenta como error de color.

**Métricas.** El ΔE2000 se mide antes y después del ajuste:

- media, p95 y máximo;
- error en piel y en grises;
- error por parche;
- estabilidad temporal del neutro 5;
- diferencia entre lentes bajo el mismo iluminante.

**Validación.** Se hace con otra toma que no se usó para ajustar: otra posición, otro momento u otra exposición.

**Versionado.** Cada GUARDAR crea `files/calibration/v<N>.json`, sin sobrescribir nada. REVERTIR vuelve a la versión anterior y la versión 0 equivale a identidad. INFORME exporta un markdown a Descargas.

**Referencia.** Por defecto, ColorChecker 2005 (L\*a\*b\* D50). Si la carta se midió con espectrofotómetro, va en `files/calibration/reference_lab.json` como `{"lab": [[L, a, b] × 24]}`.

**Curva del ISP.** La calibración solo se aplica con la misma curva con la que se midió (sRGB o la del ISP). Con la curva del ISP la salida ya trae tono propio: la matriz absorbe parte, pero es menos exacta. Por eso C y E piden sRGB al ISP (`TONEMAP_PRESET_CURVE_SRGB`, si el HAL lo admite).

**Condiciones a documentar en cada sesión:**

- carta (modelo y lote) y medición de la luz (CCT o lux, si hay medidor);
- distancia y ángulo (unos 45° respecto de la luz, para evitar reflejos);
- perfil de captura y lente física (la registra la app);
- hora.

## Limitaciones conocidas

- Solo se usa Camera2 público: ni buffers internos del ISP ni parámetros privados de Motorola.
- Pasar a la frontal requiere otra sesión y corta la grabación. El cambio 0.5×/1×/3× es sin corte solo en la cámara lógica trasera.
- La normalización de color entre lentes depende de calibrar cada lente física. Sin calibración, la matriz es la identidad y la pantalla lo indica ("sin calibrar").
- La medición de la carta asume video 16:9: la lectura cruda es de 270×480.
- No se afirma equivalencia con iPhone ni con la app de Motorola sin la comparativa siguiente.

## Validación contra la app de Motorola y un iPhone

Se graba la misma escena, con la misma luz, encuadre, resolución y fps. Escenas:

1. Interior con ventana.
2. Piel en luz mixta.
3. Paneo lento con una lámpara que entra en cuadro.
4. Cambio 0.5→1→3× grabando.
5. Caminata (estabilización).
6. Interior con poca luz (ruido).
7. 10 minutos en 4K60 (temperatura).
8. ColorChecker con las tres luces, medido con la misma herramienta en los tres equipos (ΔE2000).

Hay que guardar los originales, el CSV de métricas de cada toma y el informe de calibración.
