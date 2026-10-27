package com.signature.cinematic.gl;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.signature.cinematic.color.ColorProfile;
import com.signature.cinematic.color.ToneCurve;
import com.signature.cinematic.control.FrameStats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * Hilo GL: recibe los fotogramas de la cámara como textura externa, aplica la pipeline de color
 * y dibuja en la vista previa y en la superficie del codificador. Todo el estado GL vive en este hilo.
 */
public final class GlRenderer {
    private static final String TAG = "GlRenderer";
    private static final int STATS_W = 72;
    private static final int STATS_H = 128;

    /** Lo que el motor aporta por fotograma. Se llama en el hilo GL. */
    public interface FrameHook {
        /**
         * Rellena la matriz base (balance + calibración) y la creativa (3x3 por filas)
         * y devuelve la ganancia lineal del fotograma.
         */
        float prepareFrame(long timestampNs, float[] baseOut, float[] lookOut);

        void onStats(FrameStats stats, long timestampNs);

        void onFrameRendered(long timestampNs, long renderNs, boolean recorded);

        /** Timestamp de presentación para el codificador. */
        long encoderTimeNs(long timestampNs);
    }

    /** Recibe fotogramas sin procesar (salida del ISP) en orientación de la vista; hilo GL. */
    public interface RawFrameSink {
        /** @return false para dejar de recibir fotogramas */
        boolean onRawFrame(byte[] rgba, int width, int height, long timestampNs);
    }

    /** Cuadro graduado (y con desenfoque si Cine está activo) en vertical, fila 0 arriba. Hilo GL. */
    public interface SnapshotSink {
        void onSnapshot(Bitmap upright);
    }

    public static final int RAW_W = 270;
    public static final int RAW_H = 480;

    private final HandlerThread thread = new HandlerThread("gl", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
    private final Handler handler;
    private final FrameHook hook;

    private EglCore egl;
    private EGLSurface pbuffer;
    private EGLSurface previewSurface;
    private int previewW;
    private int previewH;
    private EGLSurface encoderSurface;
    private int encoderW;
    private int encoderH;
    private boolean encoderRotate;

    private int program;
    private int oesTex;
    private int toneTex;
    private int statsFbo;
    private int statsTex;
    private int rawFbo;
    private int rawTex;
    private volatile RawFrameSink rawSink;

    /** Lado largo máximo del cuadro graduado del modo Cine (8K se graba sin Cine). */
    private static final int CINE_MAX_LONG = 3840;
    /** Radio máximo del disco en píxeles de 1/4 de resolución (cota del bucle del shader). */
    private static final float CINE_MAX_BLUR_Q = 21f;
    private volatile CinematicSource cine;
    private boolean cineMapsReady;
    private int cinePrepProg;
    private int cineBlurProg;
    private int cineCompProg;
    private int depthTex;
    private int maskTex;
    private int gradedFbo;
    private int gradedTex;
    private int gradedW;
    private int gradedH;
    private int qFbo;
    private int qTex;
    private int qW;
    private int qH;
    private int blurFbo;
    private int blurTex;
    private final float[] identity = new float[16];
    private final ByteBuffer rawBuffer = ByteBuffer.allocateDirect(RAW_W * RAW_H * 4).order(ByteOrder.nativeOrder());
    private SurfaceTexture surfaceTexture;
    private Surface cameraSurface;

    private int locTexMatrix;
    private int locMvp;
    private int locBase;
    private int locLook;
    private int locGain;
    private int locSat;
    private int locSkin;
    private int locSkinUndertone;
    private int locSkinAnchor;
    private int locTonePad;
    private int locColorPad;
    private int locSoftSkin;
    private int locGlow;
    private int locGrain;
    private int locLensMorph;
    private int locInvSize;
    private int locSeed;
    private int locShadowTint;
    private int locHighTint;
    private int locMono;
    private int locGamut;
    private int locDceStrength;
    private int locBypass;
    private int locTex;
    private int locTone;

    private int freezeProg;
    private int locFreezeTex;
    private int locFreezeAlpha;
    private int locFreezeTexel;
    private int locFreezeZoomScale;
    private int freezeFbo;
    private int freezeOwnTex;
    private int freezeOwnW;
    private int freezeOwnH;
    private int freezeTex;
    private int freezeW;
    private int freezeH;
    private final int[] historyFbo = new int[2];
    private final int[] historyTex = new int[2];
    private int historyW;
    private int historyH;
    private int historyWriteIdx = 0;
    private boolean historyPrimed = false;
    private volatile boolean isSwitchingCamera;
    private volatile boolean isLensCrossfading;
    private volatile boolean sensorJustSwitched;
    private boolean waitingForNewCameraFrame;
    private int newCameraSettleFrames;
    private float switchAlpha;
    private long switchStartTimeNs;
    private static final long SWITCH_DURATION_NS = 340_000_000L;
    private static final long LENS_CROSSFADE_DURATION_NS = 320_000_000L;
    private float stableSceneLuma = 0f;
    private float targetBridgeLuma = 0f;
    private float lumaBridgeGain = 1.0f;
    private int lumaBridgeFrames = 0;

    private final float[] texMatrix = new float[16];
    private final float[] mvp = new float[16];
    private final float[] baseMatrix = new float[9];
    private final float[] lookMatrix = new float[9];
    private final ByteBuffer statsBuffer = ByteBuffer.allocateDirect(STATS_W * STATS_H * 4).order(ByteOrder.nativeOrder());
    private final byte[] statsBytes = new byte[STATS_W * STATS_H * 4];
    private final int[] statsPbo = new int[2];
    private int statsPboIndex = 0;
    private boolean statsPboPrimed = false;
    private FloatBuffer quad;

    private volatile ColorProfile profile = ColorProfile.NATURAL;
    private ColorProfile uploadedProfile;
    private volatile float tonePad;
    private volatile float colorPad;
    private volatile float softSkin;
    private volatile float glow;
    private volatile float grain;
    private volatile float lensMorph;
    private volatile float dceStrength = 0.0f;
    private volatile float focusX = 0.5f;
    private volatile float focusY = 0.5f;
    private volatile int statsInterval = 2;
    private volatile int encoderEvery = 1;
    private volatile int targetFps = 30;
    private long lastDeliveredTs = 0;
    private long lastEncodedWallNs = 0;
    private float lastGain = 1.0f;
    private int lastExtraRotation = 0;
    private volatile SnapshotSink snapshotSink;
    private final float[] flipY = new float[16];
    private final float[] flipX = new float[16];
    private final float[] mvpTmp = new float[16];
    private int sensorOrientation;
    private boolean frontFacing;
    private int bufferW = 16;
    private int bufferH = 9;
    private long frameCount;

    public GlRenderer(FrameHook hook) {
        this.hook = hook;
        Matrix.setIdentityM(identity, 0);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Crea el contexto y la superficie de entrada de la cámara con el tamaño del sensor. Bloquea. */
    public Surface start(int width, int height, int sensorOrientation, boolean front) {
        Surface[] out = new Surface[1];
        runSync(() -> {
            this.sensorOrientation = sensorOrientation;
            this.frontFacing = front;
            this.bufferW = width;
            this.bufferH = height;
            if (isSwitchingCamera) {
                waitingForNewCameraFrame = true;
                newCameraSettleFrames = 4;
                switchStartTimeNs = 0;
                if (stableSceneLuma > 0.01f) {
                    targetBridgeLuma = stableSceneLuma;
                    lumaBridgeFrames = 24;
                }
            }
            if (egl == null) {
                initGl();
            }
            if (surfaceTexture == null) {
                surfaceTexture = new SurfaceTexture(oesTex);
                surfaceTexture.setDefaultBufferSize(width, height);
                surfaceTexture.setOnFrameAvailableListener(st -> drawFrame(), handler);
                cameraSurface = new Surface(surfaceTexture);
            } else {
                surfaceTexture.setDefaultBufferSize(width, height);
            }
            out[0] = cameraSurface;
        });
        return out[0];
    }

    private void initGl() {
        Matrix.setIdentityM(identity, 0);
        egl = new EglCore();
        pbuffer = egl.createPbuffer(1, 1);
        egl.makeCurrent(pbuffer);
        program = GlUtil.program(Shaders.VERTEX, Shaders.FRAGMENT);
        locTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix");
        locMvp = GLES30.glGetUniformLocation(program, "uMvp");
        locBase = GLES30.glGetUniformLocation(program, "uBase");
        locLook = GLES30.glGetUniformLocation(program, "uLook");
        locGain = GLES30.glGetUniformLocation(program, "uGain");
        locSat = GLES30.glGetUniformLocation(program, "uSat");
        locSkin = GLES30.glGetUniformLocation(program, "uSkin");
        locSkinUndertone = GLES30.glGetUniformLocation(program, "uSkinUndertone");
        locSkinAnchor = GLES30.glGetUniformLocation(program, "uSkinAnchor");
        locTonePad = GLES30.glGetUniformLocation(program, "uTonePad");
        locColorPad = GLES30.glGetUniformLocation(program, "uColorPad");
        locSoftSkin = GLES30.glGetUniformLocation(program, "uSoftSkin");
        locGlow = GLES30.glGetUniformLocation(program, "uGlow");
        locGrain = GLES30.glGetUniformLocation(program, "uGrain");
        locLensMorph = GLES30.glGetUniformLocation(program, "uLensMorph");
        locInvSize = GLES30.glGetUniformLocation(program, "uInvSize");
        locSeed = GLES30.glGetUniformLocation(program, "uSeed");
        locShadowTint = GLES30.glGetUniformLocation(program, "uShadowTint");
        locHighTint = GLES30.glGetUniformLocation(program, "uHighTint");
        locMono = GLES30.glGetUniformLocation(program, "uMono");
        locGamut = GLES30.glGetUniformLocation(program, "uGamut");
        locDceStrength = GLES30.glGetUniformLocation(program, "uDceStrength");
        locBypass = GLES30.glGetUniformLocation(program, "uBypass");
        locTex = GLES30.glGetUniformLocation(program, "uTex");
        locTone = GLES30.glGetUniformLocation(program, "uTone");

        freezeProg = GlUtil.program(Shaders.VERTEX, Shaders.FREEZE_BLUR);
        locFreezeTex = GLES30.glGetUniformLocation(freezeProg, "uFreeze");
        locFreezeAlpha = GLES30.glGetUniformLocation(freezeProg, "uAlpha");
        locFreezeTexel = GLES30.glGetUniformLocation(freezeProg, "uTexel");
        locFreezeZoomScale = GLES30.glGetUniformLocation(freezeProg, "uZoomScale");

        oesTex = GlUtil.texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        toneTex = GlUtil.texture(GLES30.GL_TEXTURE_2D);
        statsTex = GlUtil.texture(GLES30.GL_TEXTURE_2D);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, STATS_W, STATS_H, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        int[] fbo = new int[1];
        GLES30.glGenFramebuffers(1, fbo, 0);
        statsFbo = fbo[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, statsFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, statsTex, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        GLES30.glGenBuffers(2, statsPbo, 0);
        int statsByteCount = STATS_W * STATS_H * 4;
        for (int i = 0; i < 2; i++) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, statsPbo[i]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, statsByteCount, null, GLES30.GL_DYNAMIC_READ);
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        statsPboIndex = 0;
        statsPboPrimed = false;

        rawTex = GlUtil.texture(GLES30.GL_TEXTURE_2D);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, RAW_W, RAW_H, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glGenFramebuffers(1, fbo, 0);
        rawFbo = fbo[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rawFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, rawTex, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        float[] verts = {
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        };
        quad = ByteBuffer.allocateDirect(verts.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(verts).position(0);
    }

    public void setPreviewSurface(Surface surface, int width, int height) {
        handler.post(() -> {
            releasePreview();
            if (surface != null && egl != null) {
                try {
                    previewSurface = egl.createWindowSurface(surface);
                    previewW = width;
                    previewH = height;
                } catch (RuntimeException e) {
                    Log.w(TAG, "preview surface", e);
                }
            }
        });
    }

    public void clearPreviewSync() {
        runSync(this::releasePreview);
    }

    private void releasePreview() {
        if (previewSurface != null) {
            egl.makeCurrent(pbuffer);
            egl.releaseSurface(previewSurface);
            previewSurface = null;
        }
    }

    /**
     * @param rotateToLandscape el codificador no acepta cuadro vertical: se dibuja girado 90°
     */
    public void setEncoderSurface(Surface surface, int width, int height, boolean rotateToLandscape) {
        runSync(() -> {
            releaseEncoder();
            if (surface != null) {
                encoderSurface = egl.createWindowSurface(surface);
                encoderW = width;
                encoderH = height;
                encoderRotate = rotateToLandscape;
            }
        });
    }

    public void clearEncoderSync() {
        runSync(this::releaseEncoder);
    }

    private void releaseEncoder() {
        if (encoderSurface != null) {
            egl.makeCurrent(pbuffer);
            egl.releaseSurface(encoderSurface);
            encoderSurface = null;
        }
    }

    public void setProfile(ColorProfile p) {
        profile = p;
    }

    public void setStyleParams(float tonePad, float colorPad, float softSkin, float glow, float grain) {
        this.tonePad = tonePad;
        this.colorPad = colorPad;
        this.softSkin = softSkin;
        this.glow = glow;
        this.grain = grain;
    }

    /** Intensidad del realce de curvas de iluminación adaptativa Zero-DCE (0 = desactivado, 0.35 = estándar cinemático). */
    public void setDceStrength(float s) {
        this.dceStrength = Math.max(0f, Math.min(1f, s));
    }

    public float getDceStrength() {
        return dceStrength;
    }

    /**
     * Transición óptica multi-cámara (.5x <-> 1x <-> 3x):
     * delegada al hardware SAT (Sensor Array Transit) del ISP sin congelar cuadros ni alterar ganancia.
     */
    public void triggerLensTransition(float strength) {
        // No-op: el ISP ejecuta la transición SAT nativa sin saltos ni cuadros congelados.
    }

    /** Notifica que el HAL conmutó de sensor físico; invalida el PBO previo para no mezclar estadísticas. */
    public void notifySensorSwitch() {
        handler.post(() -> {
            statsPboPrimed = false;
            lumaBridgeFrames = 0;
            lumaBridgeGain = 1.0f;
        });
    }

    public void setTargetFps(int fps) {
        this.targetFps = Math.max(15, Math.min(120, fps));
    }

    /** Punto de medición en coordenadas de la vista (0..1, origen arriba a la izquierda). */
    public void setFocusPoint(float x, float y) {
        focusX = x;
        focusY = y;
    }

    /** Entrega fotogramas del ISP sin la pipeline de color (para medir la carta) hasta que el sink devuelva false. */
    public void setRawFrameSink(RawFrameSink sink) {
        rawSink = sink;
    }

    public void setStatsInterval(int frames) {
        statsInterval = Math.max(1, frames);
    }

    /** Solo uno de cada n cuadros llega al codificador (time-lapse). */
    public void setEncoderDecimation(int n) {
        encoderEvery = Math.max(1, n);
    }

    /** Entrega el próximo cuadro a resolución completa (hasta 3840 en Cine). */
    public void requestSnapshot(SnapshotSink sink) {
        snapshotSink = sink;
    }

    private void ensureHistoryTargets() {
        int shortSide = Math.min(bufferW, bufferH);
        int longSide = Math.max(bufferW, bufferH);
        int hw = Math.min(540, Math.max(1, shortSide));
        int hh = Math.max(1, Math.round(hw * (longSide / (float) Math.max(1, shortSide))));
        if (historyW != hw || historyH != hh || historyFbo[0] == 0) {
            if (historyFbo[0] != 0) {
                GLES30.glDeleteFramebuffers(2, historyFbo, 0);
                GLES30.glDeleteTextures(2, historyTex, 0);
            }
            for (int i = 0; i < 2; i++) {
                int[] t = colorTarget(hw, hh);
                historyFbo[i] = t[0];
                historyTex[i] = t[1];
            }
            historyW = hw;
            historyH = hh;
            historyPrimed = false;
        }
    }

    private void recordHistoryFrame(int extraRotation, float gain) {
        ensureHistoryTargets();
        int idx = historyWriteIdx;
        historyWriteIdx = 1 - historyWriteIdx;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, historyFbo[idx]);
        GLES30.glViewport(0, 0, historyW, historyH);
        buildMvp(historyW, historyH, extraRotation, false, false);
        draw(historyW, historyH, gain, false);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        historyPrimed = true;
    }

    private void lockHistoryAsFreezeBuffer() {
        if (historyPrimed && historyTex[1 - historyWriteIdx] != 0) {
            freezeTex = historyTex[1 - historyWriteIdx];
            freezeW = historyW;
            freezeH = historyH;
        } else {
            captureFreezeBuffer();
        }
    }

    private void captureFreezeBuffer() {
        int shortSide = Math.min(bufferW, bufferH);
        int longSide = Math.max(bufferW, bufferH);
        int fw = Math.min(720, Math.max(1, shortSide));
        int fh = Math.max(1, Math.round(fw * (longSide / (float) Math.max(1, shortSide))));
        if (freezeFbo == 0 || freezeOwnW != fw || freezeOwnH != fh) {
            releaseFreezeTarget();
            int[] f = colorTarget(fw, fh);
            freezeFbo = f[0];
            freezeOwnTex = f[1];
            freezeOwnW = fw;
            freezeOwnH = fh;
        }
        freezeTex = freezeOwnTex;
        freezeW = freezeOwnW;
        freezeH = freezeOwnH;
        egl.makeCurrent(pbuffer);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, freezeFbo);
        GLES30.glViewport(0, 0, freezeW, freezeH);
        boolean producerRotated = Math.abs(texMatrix[0]) < 0.5f;
        int extraRotation = (!producerRotated && sensorOrientation % 180 != 0) ? -sensorOrientation : 0;
        buildMvp(freezeW, freezeH, extraRotation, false, false);
        draw(freezeW, freezeH, lastGain, false);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Congela el último cuadro renderizado en la GPU para evitar pantallas negras
     * y mantener el flujo de cuadros continuo hacia MediaCodec durante la reconexión de cámara.
     */
    public void freezeLastFrame() {
        handler.post(() -> {
            if (egl == null || previewSurface == null || previewW <= 0 || previewH <= 0) {
                return;
            }
            captureFreezeBuffer();
            isLensCrossfading = false;
            isSwitchingCamera = true;
            waitingForNewCameraFrame = true;
            newCameraSettleFrames = 4;
            switchAlpha = 1.0f;
            switchStartTimeNs = 0;
            if (stableSceneLuma > 0.01f) {
                targetBridgeLuma = stableSceneLuma;
                lumaBridgeFrames = 24;
            }
            handler.removeCallbacks(framePacer);
            handler.post(framePacer);
        });
    }

    /**
     * Sintetizador de cadencia cero-pérdidas (Zero-Drop Frame Pacer):
     * si el HAL de cámara demora más de 1 intervalo de cuadro durante la conmutación entre sensores
     * (.5x <-> 1x <-> 3x o Frontal <-> Trasera), mantiene alimentados el codificador de video y el visor
     * exactamente a la cadencia objetivo (30/60 fps) sin perder ni un solo fotograma.
     */
    private final Runnable framePacer = new Runnable() {
        @Override
        public void run() {
            if (egl == null || !isSwitchingCamera) {
                return;
            }
            long t0 = System.nanoTime();
            long frameIntervalNs = 1_000_000_000L / Math.max(15, targetFps);
            frameCount++;

            boolean recorded = false;
            if (encoderSurface != null && frameCount % encoderEvery == 0) {
                long ptsNs = hook.encoderTimeNs(-1L);
                if (ptsNs >= 0) {
                    egl.makeCurrent(encoderSurface);
                    if (freezeTex != 0) {
                        GLES30.glViewport(0, 0, encoderW, encoderH);
                        GLES30.glClearColor(0f, 0f, 0f, 1f);
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                        renderFreezeBlit(encoderW, encoderH, encoderRotate ? 90 : 0, false, 1.0f, 1.0f);
                    } else {
                        buildMvp(encoderW, encoderH, lastExtraRotation + (encoderRotate ? 90 : 0), false, false);
                        draw(encoderW, encoderH, lastGain, false);
                    }
                    egl.setPresentationTime(encoderSurface, ptsNs);
                    recorded = egl.swap(encoderSurface);
                }
            }

            if (previewSurface != null && freezeTex != 0) {
                egl.makeCurrent(previewSurface);
                GLES30.glViewport(0, 0, previewW, previewH);
                GLES30.glClearColor(0f, 0f, 0f, 1f);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                renderFreezeBlit(previewW, previewH, 0, true, 1.0f, 1.0f);
                egl.swap(previewSurface);
            }

            hook.onFrameRendered(t0, System.nanoTime() - t0, recorded);
            if (isSwitchingCamera) {
                handler.postDelayed(this, Math.max(8L, frameIntervalNs / 1_000_000L));
            }
        }
    };

    private void renderFreezeBlit(int w, int h, int rotationDeg, boolean fit, float alpha, float zoomScale) {
        if (freezeProg == 0 || freezeTex == 0) {
            return;
        }
        Matrix.setIdentityM(mvp, 0);
        if (fit) {
            float content = rotationDeg % 180 != 0 ? freezeH / (float) freezeW : freezeW / (float) freezeH;
            float surface = w / (float) h;
            if (content > surface) {
                Matrix.scaleM(mvp, 0, 1f, surface / content, 1f);
            } else {
                Matrix.scaleM(mvp, 0, content / surface, 1f, 1f);
            }
        }
        if (rotationDeg != 0) {
            Matrix.rotateM(mvp, 0, rotationDeg, 0f, 0f, 1f);
        }
        GLES30.glUseProgram(freezeProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, freezeTex);
        GLES30.glUniform1i(locFreezeTex, 0);
        GLES30.glUniform1f(locFreezeAlpha, alpha);
        GLES30.glUniform2f(locFreezeTexel, 1.0f / Math.max(1, freezeW), 1.0f / Math.max(1, freezeH));
        if (locFreezeZoomScale >= 0) {
            GLES30.glUniform1f(locFreezeZoomScale, zoomScale);
        }

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);

        drawQuad(freezeProg, mvp);

        GLES30.glDisable(GLES30.GL_BLEND);
    }

    public void notifyNewCameraActive() {
        handler.post(() -> {
            waitingForNewCameraFrame = false;
        });
    }

    private void releaseFreezeTarget() {
        if (freezeFbo != 0) {
            GLES30.glDeleteFramebuffers(1, new int[] {freezeFbo}, 0);
            GLES30.glDeleteTextures(1, new int[] {freezeOwnTex}, 0);
            freezeFbo = 0;
            freezeOwnTex = 0;
        }
        freezeOwnW = 0;
        freezeOwnH = 0;
    }

    private void drawFrame() {
        if (surfaceTexture == null) {
            return;
        }
        handler.removeCallbacks(framePacer);
        long t0 = System.nanoTime();
        egl.makeCurrent(pbuffer);
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(texMatrix);
        long ts = surfaceTexture.getTimestamp();
        long frameIntervalNs = 1_000_000_000L / Math.max(15, targetFps);
        frameCount++;
        if (lensMorph > 0.005f) {
            lensMorph *= 0.72f;
            if (lensMorph < 0.01f) {
                lensMorph = 0f;
            }
        }

        if (uploadedProfile != profile) {
            uploadProfile(profile);
        }
        boolean producerRotated = Math.abs(texMatrix[0]) < 0.5f;
        int extraRotation = (!producerRotated && sensorOrientation % 180 != 0) ? -sensorOrientation : 0;
        boolean mirror = false;

        float gain = hook.prepareFrame(ts, baseMatrix, lookMatrix);
        lastGain = gain;
        lastExtraRotation = extraRotation;

        CinematicSource cs = cine;
        if (cs != null) {
            ensureCineGl();
            CinematicSource.Maps maps = cs.pollMaps();
            if (maps != null) {
                uploadMaps(maps);
            }
        }
        boolean cineActive = cs != null && cineMapsReady;
        float focus = 0f;
        float maskFocus = 0f;
        if (cineActive) {
            focus = cs.focusDepth(ts);
            maskFocus = cs.maskFocus();
            renderCine(gain, extraRotation, mirror, focus, maskFocus, cs.maxBlurFraction());
        }

        float crossfadeAlpha = 0f;
        float crossfadeZoomScale = 1.0f;
        if (isSwitchingCamera || isLensCrossfading) {
            if (waitingForNewCameraFrame) {
                crossfadeAlpha = 1.0f;
            } else if (newCameraSettleFrames > 0) {
                newCameraSettleFrames--;
                crossfadeAlpha = 1.0f;
            } else {
                if (switchStartTimeNs == 0) {
                    switchStartTimeNs = t0;
                }
                long elapsed = t0 - switchStartTimeNs;
                long dur = isLensCrossfading ? LENS_CROSSFADE_DURATION_NS : SWITCH_DURATION_NS;
                float progress = Math.max(0f, Math.min(1f, (float) elapsed / dur));
                if (progress >= 1.0f) {
                    isSwitchingCamera = false;
                    isLensCrossfading = false;
                    switchAlpha = 0.0f;
                    switchStartTimeNs = 0;
                } else {
                    // Curva S cúbica Hermite (smoothstep) para disolvencia óptica continua sin escalones de luz
                    float smooth = progress * progress * (3.0f - 2.0f * progress);
                    switchAlpha = 1.0f - smooth;
                    crossfadeAlpha = switchAlpha;
                    crossfadeZoomScale = isLensCrossfading ? (1.0f + smooth * 0.015f) : 1.0f;
                }
            }
        }

        boolean recorded = false;
        if (encoderSurface != null && frameCount % encoderEvery == 0) {
            long ptsNs = hook.encoderTimeNs(ts);
            if (ptsNs >= 0) {
                egl.makeCurrent(encoderSurface);
                if (cineActive) {
                    composite(encoderW, encoderH, encoderRotate ? 90 : 0, false, mirror, false, focus, maskFocus);
                } else {
                    buildMvp(encoderW, encoderH, extraRotation + (encoderRotate ? 90 : 0), mirror, false);
                    draw(encoderW, encoderH, gain, false);
                }
                if (crossfadeAlpha > 0.001f) {
                    renderFreezeBlit(encoderW, encoderH, encoderRotate ? 90 : 0, false, crossfadeAlpha, crossfadeZoomScale);
                }
                egl.setPresentationTime(encoderSurface, ptsNs);
                recorded = egl.swap(encoderSurface);
            }
        }
        if (previewSurface != null) {
            egl.makeCurrent(previewSurface);
            if (cineActive) {
                composite(previewW, previewH, 0, true, mirror, false, focus, maskFocus);
            } else {
                buildMvp(previewW, previewH, extraRotation, mirror, true);
                draw(previewW, previewH, gain, false);
            }
            if (crossfadeAlpha > 0.001f) {
                renderFreezeBlit(previewW, previewH, 0, true, crossfadeAlpha, crossfadeZoomScale);
            }
            if (!egl.swap(previewSurface)) {
                releasePreview();
            }
        }
        SnapshotSink snap = snapshotSink;
        if (snap != null) {
            snapshotSink = null;
            egl.makeCurrent(pbuffer);
            snap.onSnapshot(snapshot(gain, extraRotation, mirror, cineActive, focus, maskFocus));
        }
        if (cs != null && cs.wantsFrame()) {
            egl.makeCurrent(pbuffer);
            cs.onFrame(readRawFrame(extraRotation, mirror), RAW_W, RAW_H, ts);
        }
        if (frameCount % statsInterval == 0 && !waitingForNewCameraFrame) {
            egl.makeCurrent(pbuffer);
            measure(extraRotation, mirror, ts);
        }
        RawFrameSink sink = rawSink;
        if (sink != null) {
            egl.makeCurrent(pbuffer);
            readRaw(sink, extraRotation, mirror, ts);
        }
        hook.onFrameRendered(ts, System.nanoTime() - t0, recorded);
        if (isSwitchingCamera) {
            long pacerDelayMs = Math.max(8L, (frameIntervalNs * 12 / 10) / 1_000_000L);
            handler.postDelayed(framePacer, pacerDelayMs);
        }
    }

    private void readRaw(RawFrameSink sink, int extraRotation, boolean mirror, long ts) {
        if (!sink.onRawFrame(readRawFrame(extraRotation, mirror), RAW_W, RAW_H, ts)) {
            rawSink = null;
        }
    }

    // ------------------------------------------------------------ modo Cine

    /** Activa el modo Cine con esta fuente de mapas; null lo apaga. */
    public void setCinematic(CinematicSource source) {
        cine = source;
        if (source == null) {
            handler.post(() -> cineMapsReady = false);
        }
    }

    private void ensureCineGl() {
        if (cinePrepProg == 0) {
            cinePrepProg = GlUtil.program(Shaders.VERTEX, Shaders.CINE_PREP);
            cineBlurProg = GlUtil.program(Shaders.VERTEX, Shaders.CINE_BLUR);
            cineCompProg = GlUtil.program(Shaders.VERTEX, Shaders.CINE_COMPOSITE);
            depthTex = GlUtil.texture(GLES30.GL_TEXTURE_2D);
            maskTex = GlUtil.texture(GLES30.GL_TEXTURE_2D);
            Matrix.setIdentityM(identity, 0);
        }
        int longSide = Math.min(CINE_MAX_LONG, Math.max(bufferW, bufferH));
        int shortSide = Math.round(longSide * Math.min(bufferW, bufferH) / (float) Math.max(bufferW, bufferH));
        if (gradedW != shortSide || gradedH != longSide) {
            releaseCineTargets();
            gradedW = shortSide;
            gradedH = longSide;
            qW = Math.max(1, gradedW / 4);
            qH = Math.max(1, gradedH / 4);
            int[] g = colorTarget(gradedW, gradedH);
            gradedFbo = g[0];
            gradedTex = g[1];
            int[] q = colorTarget(qW, qH);
            qFbo = q[0];
            qTex = q[1];
            int[] b = colorTarget(qW, qH);
            blurFbo = b[0];
            blurTex = b[1];
        }
    }

    private static int[] colorTarget(int w, int h) {
        int tex = GlUtil.texture(GLES30.GL_TEXTURE_2D);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        int[] fbo = new int[1];
        GLES30.glGenFramebuffers(1, fbo, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        return new int[] {fbo[0], tex};
    }

    private void releaseCineTargets() {
        if (gradedFbo != 0) {
            GLES30.glDeleteFramebuffers(3, new int[] {gradedFbo, qFbo, blurFbo}, 0);
            GLES30.glDeleteTextures(3, new int[] {gradedTex, qTex, blurTex}, 0);
            gradedFbo = 0;
        }
        gradedW = 0;
        gradedH = 0;
    }

    private void uploadMaps(CinematicSource.Maps m) {
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, m.depthW, m.depthH, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(m.depth));
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, m.maskW, m.maskH, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(m.mask));
        cineMapsReady = true;
    }

    /** Graduado a resolución de trabajo, reducción a 1/4 con círculo de confusión y desenfoque. */
    private void renderCine(float gain, int extraRotation, boolean mirror, float focus, float maskFocus, float blurFraction) {
        egl.makeCurrent(pbuffer);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, gradedFbo);
        buildMvp(gradedW, gradedH, extraRotation, false, false);
        draw(gradedW, gradedH, gain, false);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, qFbo);
        GLES30.glViewport(0, 0, qW, qH);
        GLES30.glUseProgram(cinePrepProg);
        bindTex(cinePrepProg, "uGraded", 0, gradedTex);
        bindMaps(cinePrepProg, focus, maskFocus);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(cinePrepProg, "uTexel"), 1f / gradedW, 1f / gradedH);
        drawQuad(cinePrepProg, identity);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFbo);
        GLES30.glViewport(0, 0, qW, qH);
        GLES30.glUseProgram(cineBlurProg);
        bindTex(cineBlurProg, "uQ", 0, qTex);
        bindTex(cineBlurProg, "uDepth", 1, depthTex);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(cineBlurProg, "uTexel"), 1f / qW, 1f / qH);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(cineBlurProg, "uMaxR"),
                Math.min(CINE_MAX_BLUR_Q, blurFraction * qH));
        drawQuad(cineBlurProg, identity);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /** Lee el cuadro completo en vertical; glReadPixels empieza abajo, así que se dibuja invertido en Y. */
    private Bitmap snapshot(float gain, int extraRotation, boolean mirror, boolean cineActive, float focus,
            float maskFocus) {
        int w = cineActive ? gradedW : Math.min(bufferW, bufferH);
        int h = cineActive ? gradedH : Math.max(bufferW, bufferH);
        int[] target = colorTarget(w, h);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target[0]);
        if (cineActive) {
            composite(w, h, 0, false, mirror, true, focus, maskFocus);
        } else {
            buildMvp(w, h, extraRotation, mirror, false);
            flipMvp();
            draw(w, h, gain, false);
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glDeleteFramebuffers(1, new int[] {target[0]}, 0);
        GLES30.glDeleteTextures(1, new int[] {target[1]}, 0);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        buf.position(0);
        bmp.copyPixelsFromBuffer(buf);
        return bmp;
    }

    /** Invierte Y en el espacio de recorte (después de rotar), no en el del modelo. */
    private void flipMvp() {
        Matrix.setIdentityM(flipY, 0);
        Matrix.scaleM(flipY, 0, 1f, -1f, 1f);
        Matrix.multiplyMM(mvpTmp, 0, flipY, 0, mvp, 0);
        System.arraycopy(mvpTmp, 0, mvp, 0, 16);
    }

    /** Invierte X en el espacio de recorte (después de rotar), garantizando reflejo horizontal selfie correcto en cualquier ángulo. */
    private void flipMvpX() {
        Matrix.setIdentityM(flipX, 0);
        Matrix.scaleM(flipX, 0, -1f, 1f, 1f);
        Matrix.multiplyMM(mvpTmp, 0, flipX, 0, mvp, 0);
        System.arraycopy(mvpTmp, 0, mvp, 0, 16);
    }

    private void composite(int w, int h, int rotationDeg, boolean fit, boolean mirror, boolean flipY, float focus, float maskFocus) {
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        Matrix.setIdentityM(mvp, 0);
        if (fit) {
            float content = rotationDeg % 180 != 0 ? gradedH / (float) gradedW : gradedW / (float) gradedH;
            float surface = w / (float) h;
            if (content > surface) {
                Matrix.scaleM(mvp, 0, 1f, surface / content, 1f);
            } else {
                Matrix.scaleM(mvp, 0, content / surface, 1f, 1f);
            }
        }
        if (rotationDeg != 0) {
            Matrix.rotateM(mvp, 0, rotationDeg, 0f, 0f, 1f);
        }
        if (mirror) {
            flipMvpX();
        }
        if (flipY) {
            flipMvp();
        }
        GLES30.glUseProgram(cineCompProg);
        bindTex(cineCompProg, "uGraded", 0, gradedTex);
        bindTex(cineCompProg, "uBlur", 3, blurTex);
        bindMaps(cineCompProg, focus, maskFocus);
        drawQuad(cineCompProg, mvp);
    }

    private void bindMaps(int prog, float focus, float maskFocus) {
        bindTex(prog, "uDepth", 1, depthTex);
        bindTex(prog, "uMask", 2, maskTex);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uFocus"), focus);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, "uMaskFocus"), maskFocus);
    }

    private static void bindTex(int prog, String name, int unit, int tex) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, name), unit);
    }

    private void drawQuad(int prog, float[] mvpMatrix) {
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uTexMatrix"), 1, false, identity, 0);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(prog, "uMvp"), 1, false, mvpMatrix, 0);
        quad.position(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad);
        GLES30.glEnableVertexAttribArray(0);
        quad.position(2);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private byte[] readRawFrame(int extraRotation, boolean mirror) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rawFbo);
        buildMvp(RAW_W, RAW_H, extraRotation, mirror, false);
        draw(RAW_W, RAW_H, 1f, true);
        rawBuffer.position(0);
        GLES30.glReadPixels(0, 0, RAW_W, RAW_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rawBuffer);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        byte[] out = new byte[RAW_W * RAW_H * 4];
        // glReadPixels empieza abajo: se entrega con la fila 0 arriba.
        int row = RAW_W * 4;
        for (int y = 0; y < RAW_H; y++) {
            rawBuffer.position((RAW_H - 1 - y) * row);
            rawBuffer.get(out, y * row, row);
        }
        return out;
    }

    private float measureImmediateLuma(int extraRotation, boolean mirror) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, statsFbo);
        buildMvp(STATS_W, STATS_H, extraRotation, mirror, false);
        draw(STATS_W, STATS_H, 1f, true);
        statsBuffer.position(0);
        GLES30.glReadPixels(0, 0, STATS_W, STATS_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, statsBuffer);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        statsBuffer.position(0);
        statsBuffer.get(statsBytes);
        FrameStats stats = FrameStats.compute(statsBytes, STATS_W, STATS_H, focusX, 1f - focusY);
        return (float) Math.exp(stats.logMeanLuma);
    }

    private void measure(int extraRotation, boolean mirror, long ts) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, statsFbo);
        buildMvp(STATS_W, STATS_H, extraRotation, mirror, false);
        draw(STATS_W, STATS_H, 1f, true);
        int writeIdx = statsPboIndex;
        int readIdx = 1 - statsPboIndex;
        statsPboIndex = readIdx;
        int byteCount = STATS_W * STATS_H * 4;

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, statsPbo[writeIdx]);
        GLES30.glReadPixels(0, 0, STATS_W, STATS_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0);

        if (statsPboPrimed) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, statsPbo[readIdx]);
            java.nio.Buffer mapped = GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER, 0, byteCount, GLES30.GL_MAP_READ_BIT);
            if (mapped instanceof ByteBuffer) {
                ByteBuffer bb = (ByteBuffer) mapped;
                bb.position(0);
                bb.get(statsBytes);
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                FrameStats stats = FrameStats.compute(statsBytes, STATS_W, STATS_H, focusX, 1f - focusY);
                float curLuma = (float) Math.exp(stats.logMeanLuma);
                if (lumaBridgeFrames > 0) {
                    lumaBridgeFrames--;
                    if (targetBridgeLuma > 0.008f && curLuma > 0.008f) {
                        // El objetivo fotométrico converge suavemente hacia la luminancia del nuevo sensor (~650 ms),
                        // mientras lumaBridgeGain amortigua en lazo cerrado cualquier escalón brusco del AEC hardware.
                        targetBridgeLuma += (curLuma - targetBridgeLuma) * 0.055f;
                        float ratio = Math.max(0.58f, Math.min(1.72f, targetBridgeLuma / curLuma));
                        float weight = Math.min(1f, lumaBridgeFrames / 14f);
                        float targetGain = 1.0f + (ratio - 1.0f) * weight;
                        lumaBridgeGain = lumaBridgeGain * 0.35f + targetGain * 0.65f;
                        stableSceneLuma = targetBridgeLuma;
                    }
                } else if (!isSwitchingCamera && !isLensCrossfading && curLuma > 0.005f) {
                    stableSceneLuma = stableSceneLuma <= 0.001f ? curLuma : (stableSceneLuma * 0.82f + curLuma * 0.18f);
                }
                hook.onStats(stats, ts);
                return;
            }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        } else {
            statsPboPrimed = true;
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /** Escala para ver el cuadro completo (letterbox) en una salida de w x h. */
    private void buildMvp(int w, int h, int rotationDeg, boolean mirror, boolean fit) {
        Matrix.setIdentityM(mvp, 0);
        if (fit) {
            float contentAspect = contentAspect(rotationDeg);
            float surfaceAspect = w / (float) h;
            float sx = 1f;
            float sy = 1f;
            if (contentAspect > surfaceAspect) {
                sy = surfaceAspect / contentAspect;
            } else {
                sx = contentAspect / surfaceAspect;
            }
            Matrix.scaleM(mvp, 0, sx, sy, 1f);
        }
        if (rotationDeg != 0) {
            Matrix.rotateM(mvp, 0, rotationDeg, 0f, 0f, 1f);
        }
        if (mirror) {
            flipMvpX();
        }
    }

    private float contentAspect(int rotationDeg) {
        boolean producerRotated = Math.abs(texMatrix[0]) < 0.5f;
        float bw = Math.max(bufferW, bufferH);
        float bh = Math.min(bufferW, bufferH);
        boolean portrait = producerRotated ^ (rotationDeg % 180 != 0);
        return portrait ? bh / bw : bw / bh;
    }

    private void draw(int w, int h, float gain, boolean bypass) {
        GLES30.glViewport(0, 0, w, h);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES30.glUniform1i(locTex, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex);
        GLES30.glUniform1i(locTone, 1);
        GLES30.glUniformMatrix4fv(locTexMatrix, 1, false, texMatrix, 0);
        GLES30.glUniformMatrix4fv(locMvp, 1, false, mvp, 0);
        GLES30.glUniformMatrix3fv(locBase, 1, true, baseMatrix, 0);
        GLES30.glUniformMatrix3fv(locLook, 1, true, lookMatrix, 0);
        GLES30.glUniform1f(locGain, gain);
        GLES30.glUniform1f(locBypass, bypass ? 1f : 0f);
        GLES30.glUniform1f(locTonePad, bypass ? 0f : tonePad);
        GLES30.glUniform1f(locColorPad, bypass ? 0f : colorPad);
        GLES30.glUniform1f(locSoftSkin, bypass ? 0f : softSkin);
        GLES30.glUniform1f(locGlow, bypass ? 0f : glow);
        GLES30.glUniform1f(locGrain, bypass ? 0f : grain);
        GLES30.glUniform1f(locLensMorph, bypass ? 0f : lensMorph);
        GLES30.glUniform1f(locDceStrength, bypass ? 0f : dceStrength);
        GLES30.glUniform2f(locInvSize, 1f / Math.max(1, bufferW), 1f / Math.max(1, bufferH));
        GLES30.glUniform1f(locSeed, (frameCount % 997) * 0.0013f);

        quad.position(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad);
        GLES30.glEnableVertexAttribArray(0);
        quad.position(2);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void uploadProfile(ColorProfile p) {
        GLES30.glUseProgram(program);
        GLES30.glUniform3f(locSat, p.satShadow, p.satMid, p.satHigh);
        GLES30.glUniform1f(locSkin, p.skinProtect);
        GLES30.glUniform3f(locSkinUndertone, p.skinUndertone[0], p.skinUndertone[1], p.skinUndertone[2]);
        GLES30.glUniform1f(locSkinAnchor, p.skinLumaAnchor);
        GLES30.glUniform3f(locShadowTint, p.shadowTint[0], p.shadowTint[1], p.shadowTint[2]);
        GLES30.glUniform3f(locHighTint, p.highlightTint[0], p.highlightTint[1], p.highlightTint[2]);
        GLES30.glUniform1f(locMono, p.mono);
        GLES30.glUniform1f(locGamut, p.gamutCompress);
        byte[] lut = p.tone.toLut8();
        ByteBuffer buf = ByteBuffer.allocateDirect(ToneCurve.LUT_SIZE).order(ByteOrder.nativeOrder());
        buf.put(lut).position(0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, toneTex);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, ToneCurve.LUT_SIZE, 1, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf);
        uploadedProfile = p;
    }

    public void release() {
        runSync(() -> {
            releasePreview();
            releaseEncoder();
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            if (cameraSurface != null) {
                cameraSurface.release();
                cameraSurface = null;
            }
            if (egl != null) {
                egl.makeCurrent(pbuffer);
                releaseCineTargets();
                if (cinePrepProg != 0) {
                    GLES30.glDeleteProgram(cinePrepProg);
                    GLES30.glDeleteProgram(cineBlurProg);
                    GLES30.glDeleteProgram(cineCompProg);
                    GLES30.glDeleteTextures(2, new int[] {depthTex, maskTex}, 0);
                }
                GLES30.glDeleteProgram(program);
                GLES30.glDeleteTextures(4, new int[] {oesTex, toneTex, statsTex, rawTex}, 0);
                GLES30.glDeleteFramebuffers(2, new int[] {statsFbo, rawFbo}, 0);
                if (statsPbo[0] != 0) {
                    GLES30.glDeleteBuffers(2, statsPbo, 0);
                    statsPbo[0] = 0;
                    statsPbo[1] = 0;
                }
                releaseFreezeTarget();
                if (freezeProg != 0) {
                    GLES30.glDeleteProgram(freezeProg);
                    freezeProg = 0;
                }
                egl.releaseSurface(pbuffer);
                egl.release();
                egl = null;
            }
        });
        thread.quitSafely();
    }

    private void runSync(Runnable r) {
        if (Thread.currentThread() == thread) {
            r.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        RuntimeException[] error = new RuntimeException[1];
        handler.post(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                error[0] = e;
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (error[0] != null) {
            throw error[0];
        }
    }
}
