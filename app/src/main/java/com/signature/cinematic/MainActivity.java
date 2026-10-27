package com.signature.cinematic;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import com.signature.cinematic.control.SmoothZoomController;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.signature.cinematic.cinema.DepthMath;
import com.signature.cinematic.color.ColorProfile;
import com.signature.cinematic.control.ExposureController;
import com.signature.cinematic.engine.CameraCaps;
import com.signature.cinematic.engine.CameraController;
import com.signature.cinematic.engine.CaptureProfile;
import com.signature.cinematic.engine.VideoEngine;
import com.signature.cinematic.engine.VideoMode;
import com.signature.cinematic.record.PhotoSaver;
import com.signature.cinematic.ui.FocusView;
import com.signature.cinematic.ui.IconView;
import com.signature.cinematic.ui.ModeStrip;
import com.signature.cinematic.ui.PortraitLightView;
import com.signature.cinematic.ui.ProHudView;
import com.signature.cinematic.ui.ShutterButton;
import com.signature.cinematic.ui.StylePadView;
import com.signature.cinematic.ui.ZoomDialView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cámara con diseño Liquid Glass y control de subtono y textura de piel en tiempo real,
 * optimizada para los sensores del Motorola Signature (13mm S5KJNS · 24mm IMX09A · 70mm IMX882).
 */
public final class MainActivity extends Activity implements VideoEngine.Ui {
    private enum Mode {
        TIMELAPSE("CÁMARA RÁPIDA", false), SLOWMO("CÁMARA LENTA", false), CINE("CINE", false),
        VIDEO("VIDEO", false), PHOTO("FOTO", true), PORTRAIT("RETRATO", true), PRO("PRO", true);

        final String label;
        final boolean photo;

        Mode(String label, boolean photo) {
            this.label = label;
            this.photo = photo;
        }
    }

    private static final Mode[] MODES = Mode.values();
    private static final String[] PERMISSIONS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private static final int YELLOW = IconView.YELLOW;
    private static final int RED = Color.rgb(255, 59, 48);
    private static final int TIMELAPSE_FACTOR = 15;
    private static final float[] RATIOS = {4f / 3f, 16f / 9f, 1f};
    private static final String[] RATIO_LABELS = {"4:3", "16:9", "1:1"};
    private static final int[] TIMERS = {0, 3, 10};
    private static final float[] ONE_X_STEPS = {1f, 1.17f, 1.46f};
    private static final long[] SHUTTER_SPEEDS_NS = {
        0, 1_000_000_000L / 24, 1_000_000_000L / 50, 1_000_000_000L / 120,
        1_000_000_000L / 250, 1_000_000_000L / 500, 1_000_000_000L / 1000,
        1_000_000_000L / 2000, 1_000_000_000L / 4000, 1_000_000_000L / 8000
    };
    private static final String[] SHUTTER_SPEEDS_LABELS = {
        "AUTO", "1/24", "1/50", "1/120", "1/250", "1/500", "1/1000", "1/2000", "1/4000", "1/8000"
    };
    private static final int[] ISO_STEPS = {0, 50, 100, 200, 400, 800, 1600, 3200, 6400};
    private static final String[] ISO_LABELS = {"AUTO", "50", "100", "200", "400", "800", "1600", "3200", "6400"};
    private static final int[] WB_MODES = {1, 2, 3, 5, 6, 8};
    private static final String[] WB_LABELS = {"AUTO", "3200K Inc.", "4000K Flu.", "5500K Sol", "6500K Nub.", "7500K Som."};
    /** Chrome negro superior: 48 dp bajo la cámara frontal + 44 dp de íconos. */
    private static final int TOP_CHROME_DP = 92;
    /** Chrome negro inferior: 64 dp de fila de modos + 48 dp sobre la barra de gestos. */
    private static final int BOTTOM_CHROME_DP = 112;
    private static final int SHUTTER_ROW_DP = 100;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private VideoEngine engine;
    private CameraCaps caps;
    private Mode mode = Mode.VIDEO;
    private CameraController.Flash flash = CameraController.Flash.AUTO;
    private boolean torch;
    private int timerSec;
    private int ratioIndex;
    private boolean raw;
    private boolean showMetrics;
    private boolean showProHud;
    private boolean nightBoost;
    private int portraitPreset;
    private boolean showGrid = true;
    private boolean editingWidgets;
    private boolean topShowFlash = true;
    private boolean topShowStyles = true;
    private boolean topShowFormat = true;
    private float zoom = 1f;
    private float ev = 0.0f;
    /** Controles de Estilo y Piel: Tono, Color, Piel Suave, Resplandor y Grano. */
    private float tonePad;
    private float colorPad;
    private float softSkin = 0.35f;
    private float glow = 0.15f;
    private float grain;
    private int textureTab; // 0 = Piel Suave, 1 = Resplandor, 2 = Grano

    private int deviceOrientation;
    private int iconRotation;
    private boolean suppressNextPulse;
    private long recordStart;
    private boolean countingDown;
    private Uri lastUri;
    private String lastMime = "image/jpeg";
    private CaptureProfile captureProfile = CaptureProfile.A_NATURAL;
    private ExposureController.Mode videoAe = ExposureController.Mode.CINEMATIC;
    private CameraController.Stabilization stabilization = CameraController.Stabilization.STANDARD;
    private final MediaActionSound sound = new MediaActionSound();

    private FrameLayout root;
    private SurfaceView preview;
    private FocusView focusView;
    private View blink;
    private TextView countdown;
    private IconView flashBtn;
    private IconView topStyleBtn;
    private IconView topGridBtn;
    private IconView sixDotsBtn;
    private IconView nightBtn;
    private IconView fStopBtn;
    private TextView topRatioPill;
    private TextView topTimerPill;
    private TextView topProPill;
    private TextView topHdrPill;
    private boolean hdrEnabled = true;
    private long manualShutterNs = 0;
    private int manualIso = 0;
    private int manualWb = 1;
    private PortraitLightView portraitLight;
    private FrameLayout topChrome;
    private FrameLayout bottomChrome;
    private FrameLayout shutterRow;
    private LinearLayout overlayStack;
    private LinearLayout topStack;
    private LinearLayout formatPill;
    private TextView resText;
    private TextView fpsText;
    private TextView rawPill;
    private TextView timerPill;
    private TextView aperturePill;
    private TextView lockPill;
    private TextView statusBanner;
    private ProHudView proHud;
    private LinearLayout popoverSheet;
    private LinearLayout stylesSheet;
    private HorizontalScrollView stylesScroll;
    private LinearLayout stylesRow;
    private StylePadView stylePad;
    private TextView padReadout;
    private SeekBar textureBar;
    private TextView textureValue;
    private LinearLayout textureTabsRow;
    private LinearLayout evRow;
    private SeekBar evBar;
    private TextView evValue;
    private TextView focalHint;
    private ZoomDialView zoomDial;
    private ModeStrip modeStrip;
    private ImageView thumb;
    private ShutterButton shutter;
    private IconView flip;
    private View liveSnapBtn;
    private TextView metricsLabel;
    private OrientationEventListener orientationListener;
    private ValueAnimator zoomAnimator;
    private SmoothZoomController smoothZoom;
    private final Runnable hideBanner = () -> {
        if (statusBanner != null) {
            statusBanner.animate().alpha(0f).translationY(-dp(8)).setDuration(220)
                    .withEndAction(() -> statusBanner.setVisibility(View.GONE)).start();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        requestHighRefreshRate120Hz();
        prefs = getSharedPreferences("engine", MODE_PRIVATE);
        try {
            mode = Mode.valueOf(prefs.getString("ui_mode", Mode.PHOTO.name()));
        } catch (IllegalArgumentException e) {
            mode = Mode.PHOTO;
        }
        nightBoost = prefs.getBoolean("night", false);
        portraitPreset = prefs.getInt("portrait_light", 0);
        flash = CameraController.Flash.valueOf(prefs.getString("flash", CameraController.Flash.AUTO.name()));
        timerSec = prefs.getInt("timer", 0);
        ratioIndex = prefs.getInt("ratio", 0);
        raw = prefs.getBoolean("raw", false);
        showMetrics = prefs.getBoolean("metrics", false);
        showProHud = prefs.getBoolean("pro_hud_v2", false);
        showGrid = prefs.getBoolean("grid", true);
        topShowFlash = prefs.getBoolean("top_flash", true);
        topShowStyles = prefs.getBoolean("top_styles", true);
        topShowFormat = prefs.getBoolean("top_format", true);
        tonePad = prefs.getFloat("tone_pad", 0f);
        colorPad = prefs.getFloat("color_pad", 0f);
        softSkin = prefs.getFloat("soft_skin_v2", 0.14f);
        glow = prefs.getFloat("glow_v2", 0.0f);
        grain = prefs.getFloat("grain", 0f);
        buildUi();
        sound.load(MediaActionSound.SHUTTER_CLICK);
        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) {
                    return;
                }
                int snapped = ((o + 45) / 90 * 90) % 360;
                float roll = o - snapped;
                if (roll > 180f) {
                    roll -= 360f;
                } else if (roll < -180f) {
                    roll += 360f;
                }
                if (focusView != null) {
                    focusView.setRollDegrees(roll);
                }
                if (snapped != deviceOrientation) {
                    deviceOrientation = snapped;
                    rotateIcons();
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        orientationListener.enable();
        if (hasPermissions()) {
            startEngine();
        } else {
            requestPermissions(PERMISSIONS, 1);
        }
    }

    @Override
    protected void onPause() {
        orientationListener.disable();
        countingDown = false;
        countdown.setVisibility(View.GONE);
        if (engine != null) {
            if (engine.isRecording()) {
                toggleRecording();
            }
            engine.close();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.release();
            engine = null;
        }
        sound.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (hasPermissions()) {
            startEngine();
        } else {
            Toast.makeText(this, "Hace falta permiso de cámara y micrófono", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getRepeatCount() == 0) {
                onShutter();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean hasPermissions() {
        for (String p : PERMISSIONS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startEngine() {
        try {
            if (engine != null && engine.back == null && engine.front == null) {
                engine.release();
                engine = null;
            }
            if (engine == null) {
                engine = new VideoEngine(this, this);
                engine.setProfile(ColorProfile.byId(prefs.getString("profile", ColorProfile.SIGNATURE_PRO.id)));
                engine.setStyleParams(tonePad, colorPad, softSkin, glow, grain);
                videoAe = ExposureController.Mode.valueOf(prefs.getString("ae", ExposureController.Mode.CINEMATIC.name()));
                stabilization = CameraController.Stabilization.valueOf(
                        prefs.getString("stab", CameraController.Stabilization.STANDARD.name()));
                captureProfile = CaptureProfile.byId(prefs.getString("capture", CaptureProfile.A_NATURAL.getId()));
                caps = prefs.getBoolean("front", false) && engine.front != null ? engine.front : engine.back;
            }
            if (caps == null) {
                caps = engine.back != null ? engine.back : engine.front;
            }
            if (caps == null) {
                onError("Esperando al servicio de cámara…");
                main.postDelayed(this::startEngine, 600);
                return;
            }
            applyMode();
        } catch (Exception e) {
            onError("No se pudo iniciar la cámara: " + e);
        }
    }

    private void syncStyleParams() {
        if (engine != null) {
            engine.setStyleParams(tonePad, colorPad, softSkin, glow, grain);
        }
        prefs.edit()
                .putFloat("tone_pad", tonePad)
                .putFloat("color_pad", colorPad)
                .putFloat("soft_skin_v2", softSkin)
                .putFloat("glow_v2", glow)
                .putFloat("grain", grain)
                .apply();
    }

    // ------------------------------------------------------------ modos

    private void applyMode() {
        if (engine == null || caps == null) {
            return;
        }
        if (suppressNextPulse) {
            suppressNextPulse = false;
        } else {
            pulseTransition();
        }
        zoom = 1f;
        torch = false;
        showProHud = (mode == Mode.PRO);
        if (mode != Mode.PRO) {
            manualShutterNs = 0;
            manualIso = 0;
            manualWb = 1;
            ev = 0f;
            if (evBar != null) {
                evBar.setProgress(20);
            }
            if (evValue != null) {
                evValue.setText("+0.0 EV");
            }
            if (focusView != null) {
                focusView.setEv(0f);
            }
        }
        engine.setCinematic(false);
        engine.setTorch(false);
        engine.setLocked(false);
        lockPill.setVisibility(View.GONE);
        if (mode.photo) {
            boolean allowRaw = raw && (mode == Mode.PHOTO || mode == Mode.PRO);
            engine.setExposureMode(ExposureController.Mode.AUTO);
            if (!engine.openPhoto(caps, mode == Mode.PORTRAIT, allowRaw)) {
                onError("Esta cámara no ofrece un formato 4:3 para fotos");
                return;
            }
            engine.setFlash(mode != Mode.PORTRAIT ? flash : CameraController.Flash.OFF);
            engine.setNightBoost(nightBoost && (mode == Mode.PHOTO || mode == Mode.PRO));
            layoutPreview(4f / 3f, true);
        } else {
            engine.setExposureMode(videoAe);
            engine.setNightBoost(false);
            VideoMode m = videoMode();
            engine.open(caps, m, captureProfile);
            engine.setStabilization(stabilization);
            layoutPreview(m.width / (float) m.height, false);
            if (mode == Mode.SLOWMO && m.fps < 120) {
                showBanner("Cámara lenta a " + m.fps + " fps: el sensor no ofrece 120 fps en esta sesión");
            }
        }
        if (mode == Mode.CINE || mode == Mode.PORTRAIT) {
            engine.setFNumber(prefs.getFloat("fnumber", 1.8f));
            if (!engine.setCinematic(true)) {
                showBanner("Modo Cine activo hasta 4K");
            }
        }
        initSmoothZoom();
        if (caps != null) {
            smoothZoom.setLimits(caps.zoomRange.getLower(), caps.zoomRange.getUpper());
            smoothZoom.setImmediateZoom(zoom);
        } else {
            engine.setZoom(zoom);
        }
        engine.setEvBias(ev);
        engine.setManualExposure(manualShutterNs, manualIso);
        engine.setAwbMode(manualWb);
        if (mode == Mode.PORTRAIT) {
            applyPortraitStyle();
        } else {
            engine.setProfile(ColorProfile.byId(prefs.getString("profile", ColorProfile.SIGNATURE_PRO.id)));
            engine.setStyleParams(tonePad, colorPad, softSkin, glow, grain);
        }
        focusView.setAspect(mode.photo ? RATIOS[ratioIndex] : 0f);
        focusView.setGridEnabled(showGrid);
        prefs.edit().putString("ui_mode", mode.name()).apply();
        refreshUi();
    }

    private void pulseTransition() {
        if (blink == null) {
            return;
        }
        blink.setAlpha(0.28f);
        blink.animate().alpha(0f).setDuration(170).start();
    }

    private VideoMode videoMode() {
        List<VideoMode> modes = caps.modes;
        int saved = Math.min(prefs.getInt("mode_" + caps.id, defaultIndex(modes, 3840, 30)), modes.size() - 1);
        VideoMode m = modes.get(saved);
        if (mode == Mode.TIMELAPSE) {
            return modes.get(defaultIndex(modes, 3840, 30));
        }
        if (mode == Mode.SLOWMO) {
            return slowMotionMode(modes);
        }
        if (mode == Mode.CINE && !VideoEngine.cinematicAllowed(m)) {
            return modes.get(defaultIndex(modes, 3840, 30));
        }
        return m;
    }

    /** Perfil F si el HAL lo resuelve; si no, el modo estándar con más fps (a igualdad, el de más resolución). */
    private static VideoMode slowMotionMode(List<VideoMode> modes) {
        VideoMode resolved = CaptureProfile.F_SLOW_MOTION.resolve(modes);
        if (resolved != null) {
            return resolved;
        }
        VideoMode best = modes.get(0);
        for (VideoMode m : modes) {
            if (m.fps > best.fps || (m.fps == best.fps && m.width > best.width && m.width <= 3840)) {
                best = m;
            }
        }
        return best;
    }

    private void applyPortraitStyle() {
        if (engine == null) {
            return;
        }
        PortraitLightView.Preset[] all = PortraitLightView.Preset.values();
        PortraitLightView.Preset p = all[Math.max(0, Math.min(all.length - 1, portraitPreset))];
        engine.setProfile(ColorProfile.byId(p.profileId));
        engine.setStyleParams(p.tone, p.color, p.softSkin, p.glow, grain);
    }

    private void toggleNight() {
        nightBoost = !nightBoost;
        prefs.edit().putBoolean("night", nightBoost).apply();
        if (engine != null) {
            engine.setNightBoost(nightBoost && mode == Mode.PHOTO);
        }
        showBanner(nightBoost ? "Modo Noche automático" : "Modo Noche desactivado");
        refreshUi();
    }

    private static int defaultIndex(List<VideoMode> modes, int width, int fps) {
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).width == width && modes.get(i).fps == fps) {
                return i;
            }
        }
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).fps == fps) {
                return i;
            }
        }
        return 0;
    }

    private void selectMode(int index) {
        if (engine != null && engine.isRecording()) {
            return;
        }
        mode = MODES[index];
        closeAllPopovers();
        if (mode != Mode.PRO) {
            showProHud = false;
            manualShutterNs = 0;
            manualIso = 0;
            manualWb = 1;
        } else {
            showProHud = true;
            if (popoverSheet != null) {
                popoverSheet.setVisibility(View.VISIBLE);
                popoverSheet.setAlpha(0f);
                popoverSheet.setTranslationY(dp(12));
                popoverSheet.animate().alpha(1f).translationY(0f).setDuration(200)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        prefs.edit().putBoolean("pro_hud_v2", showProHud).apply();
        applyMode();
    }

    // ------------------------------------------------------------ interfaz Liquid Glass

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ViewOutlineProvider roundedClip = new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(26));
            }
        };

        preview = new SurfaceView(this);
        preview.setClipToOutline(true);
        preview.setOutlineProvider(roundedClip);
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
                if (engine != null) {
                    engine.setPreviewSurface(h.getSurface(), w, hgt);
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                if (engine != null) {
                    engine.clearPreviewSurface();
                }
            }
        });
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        focusView = new FocusView(this);
        focusView.setClipToOutline(true);
        focusView.setOutlineProvider(roundedClip);
        root.addView(focusView, new FrameLayout.LayoutParams(-1, -1));

        blink = new View(this);
        blink.setBackgroundColor(Color.BLACK);
        blink.setAlpha(0f);
        blink.setClipToOutline(true);
        blink.setOutlineProvider(roundedClip);
        root.addView(blink, new FrameLayout.LayoutParams(-1, -1));
        installGestures();

        countdown = label(92, Color.WHITE);
        countdown.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        countdown.setVisibility(View.GONE);
        root.addView(countdown, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        buildTopBar();
        buildBottom();
        setContentView(root);
    }

    private void buildTopBar() {
        topChrome = new FrameLayout(this);
        topChrome.setBackgroundColor(Color.BLACK);

        LinearLayout topPillBar = new LinearLayout(this);
        topPillBar.setOrientation(LinearLayout.HORIZONTAL);
        topPillBar.setGravity(Gravity.CENTER_VERTICAL);
        topPillBar.setPadding(dp(16), dp(2), dp(16), dp(2));

        // 1. Flash y Modo Noche (lado izquierdo)
        flashBtn = new IconView(this, IconView.Kind.FLASH_AUTO);
        flashBtn.setDisc(true);
        flashBtn.setContentDescription("Flash");
        flashBtn.setOnClickListener(v -> {
            closeAllPopovers();
            toggleFlash();
        });
        topPillBar.addView(flashBtn, new LinearLayout.LayoutParams(dp(34), dp(34)));

        nightBtn = new IconView(this, IconView.Kind.NIGHT);
        nightBtn.setDisc(true);
        nightBtn.setContentDescription("Modo Noche");
        nightBtn.setOnClickListener(v -> {
            closeAllPopovers();
            toggleNight();
        });
        LinearLayout.LayoutParams nbp = new LinearLayout.LayoutParams(dp(34), dp(34));
        nbp.leftMargin = dp(8);
        topPillBar.addView(nightBtn, nbp);

        // Espaciador izquierdo flexible
        View leftSpacer = new View(this);
        topPillBar.addView(leftSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

        // 2. Píldoras centrales flotantes: Timer, Proporción 4:3 y Formato 4K/60
        topTimerPill = label(11, Color.WHITE);
        topTimerPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topTimerPill.setGravity(Gravity.CENTER);
        topTimerPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        topTimerPill.setBackground(liquidGlassCapsule(false, dp(13)));
        topTimerPill.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            cycleTimer();
            refreshUi();
        });
        LinearLayout.LayoutParams tpParams = new LinearLayout.LayoutParams(-2, -2);
        topPillBar.addView(topTimerPill, tpParams);

        topRatioPill = label(11, Color.WHITE);
        topRatioPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topRatioPill.setGravity(Gravity.CENTER);
        topRatioPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        topRatioPill.setBackground(liquidGlassCapsule(false, dp(13)));
        topRatioPill.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            cycleRatio();
            refreshUi();
        });
        LinearLayout.LayoutParams rpParams = new LinearLayout.LayoutParams(-2, -2);
        rpParams.leftMargin = dp(8);
        topPillBar.addView(topRatioPill, rpParams);

        formatPill = new LinearLayout(this);
        formatPill.setOrientation(LinearLayout.HORIZONTAL);
        formatPill.setGravity(Gravity.CENTER);
        formatPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        formatPill.setBackground(liquidGlassCapsule(false, dp(13)));
        resText = label(11, Color.WHITE);
        resText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        resText.setFontFeatureSettings("tnum");
        resText.setGravity(Gravity.CENTER);
        resText.setIncludeFontPadding(false);
        resText.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            cycleVideoMode(true);
        });
        TextView dotSep = label(11, Color.argb(165, 255, 255, 255));
        dotSep.setText(" · ");
        dotSep.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        dotSep.setIncludeFontPadding(false);
        fpsText = label(11, Color.WHITE);
        fpsText.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        fpsText.setFontFeatureSettings("tnum");
        fpsText.setGravity(Gravity.CENTER);
        fpsText.setIncludeFontPadding(false);
        fpsText.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            cycleVideoMode(false);
        });
        formatPill.addView(resText);
        formatPill.addView(dotSep);
        formatPill.addView(fpsText);
        LinearLayout.LayoutParams fpp = new LinearLayout.LayoutParams(-2, -2);
        fpp.leftMargin = dp(8);
        topPillBar.addView(formatPill, fpp);

        topHdrPill = label(10, YELLOW);
        topHdrPill.setText("HDR · STEREO");
        topHdrPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topHdrPill.setLetterSpacing(0.04f);
        topHdrPill.setGravity(Gravity.CENTER);
        topHdrPill.setPadding(dp(9), dp(5), dp(9), dp(5));
        topHdrPill.setBackground(liquidGlassCapsule(true, dp(13)));
        topHdrPill.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            hdrEnabled = !hdrEnabled;
            if (engine != null) {
                engine.setDceStrength(hdrEnabled ? 0.35f : 0.12f);
            }
            showBanner(hdrEnabled ? "10-bit HLG HDR · Audio Estéreo +4.5dB" : "SDR Estándar · Audio Estéreo +4.5dB");
            refreshUi();
        });
        LinearLayout.LayoutParams hdrParams = new LinearLayout.LayoutParams(-2, -2);
        hdrParams.leftMargin = dp(6);
        topPillBar.addView(topHdrPill, hdrParams);

        // Espaciador derecho flexible
        View rightSpacer = new View(this);
        topPillBar.addView(rightSpacer, new LinearLayout.LayoutParams(0, 0, 1f));

        // 3. Controles derechos: Cuadrícula, PRO y Estilos/Ajustes
        topGridBtn = new IconView(this, IconView.Kind.GRID);
        topGridBtn.setDisc(true);
        topGridBtn.setActive(showGrid);
        topGridBtn.setContentDescription("Cuadrícula de Composición");
        topGridBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            toggleGrid();
        });
        LinearLayout.LayoutParams gbp = new LinearLayout.LayoutParams(dp(34), dp(34));
        topPillBar.addView(topGridBtn, gbp);

        topProPill = label(11, Color.WHITE);
        topProPill.setText("PRO");
        topProPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topProPill.setLetterSpacing(0.06f);
        topProPill.setGravity(Gravity.CENTER);
        topProPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        topProPill.setBackground(liquidGlassCapsule(false, dp(13)));
        topProPill.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            togglePopover(popoverSheet);
        });
        LinearLayout.LayoutParams proParams = new LinearLayout.LayoutParams(-2, -2);
        proParams.leftMargin = dp(8);
        topPillBar.addView(topProPill, proParams);

        topStyleBtn = new IconView(this, IconView.Kind.STYLES_A);
        topStyleBtn.setDisc(true);
        topStyleBtn.setContentDescription("Estilos y Piel");
        topStyleBtn.setOnClickListener(v -> togglePopover(stylesSheet));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(dp(34), dp(34));
        sbp.leftMargin = dp(8);
        topPillBar.addView(topStyleBtn, sbp);

        FrameLayout.LayoutParams topBarParams = new FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER_VERTICAL | Gravity.BOTTOM);
        topBarParams.bottomMargin = dp(2);
        topChrome.addView(topPillBar, topBarParams);
        root.addView(topChrome, new FrameLayout.LayoutParams(-1, dp(TOP_CHROME_DP), Gravity.TOP));

        // RAW y apertura viven en el popover de 6 puntos y en el botón ƒ; se mantienen fuera de la jerarquía
        rawPill = label(11, Color.WHITE);
        rawPill.setText("RAW");
        aperturePill = label(11, YELLOW);

        topStack = new LinearLayout(this);
        topStack.setOrientation(LinearLayout.VERTICAL);
        topStack.setGravity(Gravity.CENTER_HORIZONTAL);
        topStack.setPadding(dp(16), dp(8), dp(16), dp(4));

        timerPill = label(14, Color.WHITE);
        timerPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        timerPill.setFontFeatureSettings("tnum");
        timerPill.setPadding(dp(12), dp(4), dp(12), dp(4));
        timerPill.setBackground(rounded(RED, dp(14)));
        timerPill.setVisibility(View.GONE);
        topStack.addView(timerPill, new LinearLayout.LayoutParams(-2, -2));

        proHud = new ProHudView(this);
        proHud.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            cycleCaptureProfile();
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(28));
        hp.topMargin = dp(6);
        topStack.addView(proHud, hp);

        lockPill = label(11, Color.BLACK);
        lockPill.setText("BLOQUEO AE/AF");
        lockPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        lockPill.setLetterSpacing(0.06f);
        lockPill.setBackground(rounded(YELLOW, dp(6)));
        lockPill.setPadding(dp(10), dp(3), dp(10), dp(3));
        lockPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        topStack.addView(lockPill, lp);

        statusBanner = label(12, Color.WHITE);
        statusBanner.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusBanner.setGravity(Gravity.CENTER);
        statusBanner.setPadding(dp(14), dp(6), dp(14), dp(6));
        statusBanner.setBackground(liquidGlassCapsule(false, dp(16)));
        statusBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(6);
        topStack.addView(statusBanner, bp);

        FrameLayout.LayoutParams tsp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        tsp.topMargin = dp(TOP_CHROME_DP);
        root.addView(topStack, tsp);
    }

    private LinearLayout.LayoutParams topIconParams(boolean leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(36), dp(36));
        if (leftMargin) {
            p.leftMargin = dp(14);
        }
        return p;
    }

    private void buildBottom() {
        // 1. Capa sobre el visor: paneles, EV, iluminación de Retrato y zoom (anclada al borde inferior del visor)
        overlayStack = new LinearLayout(this);
        overlayStack.setOrientation(LinearLayout.VERTICAL);
        overlayStack.setGravity(Gravity.CENTER_HORIZONTAL);

        metricsLabel = label(10, Color.argb(215, 255, 255, 255));
        metricsLabel.setTypeface(Typeface.MONOSPACE);
        metricsLabel.setPadding(dp(10), dp(4), dp(10), dp(4));
        metricsLabel.setBackground(rounded(Color.argb(140, 0, 0, 0), dp(8)));
        metricsLabel.setVisibility(showMetrics ? View.VISIBLE : View.GONE);
        overlayStack.addView(metricsLabel, new LinearLayout.LayoutParams(-2, -2));

        buildStylesSheet(overlayStack);

        popoverSheet = new LinearLayout(this);
        popoverSheet.setOrientation(LinearLayout.VERTICAL);
        popoverSheet.setClickable(true);
        popoverSheet.setPadding(dp(14), dp(12), dp(14), dp(12));
        popoverSheet.setBackground(liquidGlassSheet(dp(24)));
        popoverSheet.setVisibility(View.GONE);
        LinearLayout.LayoutParams popP = new LinearLayout.LayoutParams(-1, -2);
        popP.leftMargin = dp(14);
        popP.rightMargin = dp(14);
        popP.bottomMargin = dp(10);
        overlayStack.addView(popoverSheet, popP);

        evRow = new LinearLayout(this);
        evRow.setGravity(Gravity.CENTER_VERTICAL);
        evRow.setClickable(true);
        evRow.setPadding(dp(16), dp(4), dp(16), dp(4));
        evRow.setBackground(liquidGlassCapsule(false, dp(18)));
        evValue = label(12, YELLOW);
        evValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        evValue.setFontFeatureSettings("tnum");
        evValue.setMinWidth(dp(52));
        evValue.setText(String.format(Locale.US, "%+.1f EV", ev));
        evBar = new SeekBar(this);
        evBar.setMax(40);
        evBar.setProgress(20);
        evBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    setEv((p - 20) / 10f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        TextView evReset = label(11, Color.argb(190, 255, 255, 255));
        evReset.setText("0.0");
        evReset.setPadding(dp(8), dp(4), dp(4), dp(4));
        evReset.setOnClickListener(v -> {
            evBar.setProgress(20);
            setEv(0.0f);
        });
        evRow.addView(evValue);
        evRow.addView(evBar, new LinearLayout.LayoutParams(0, -2, 1f));
        evRow.addView(evReset);
        evRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams erp = new LinearLayout.LayoutParams(-1, dp(44));
        erp.leftMargin = dp(20);
        erp.rightMargin = dp(20);
        erp.bottomMargin = dp(10);
        overlayStack.addView(evRow, erp);

        focalHint = label(10, Color.argb(195, 255, 255, 255));
        focalHint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        focalHint.setLetterSpacing(0.10f);
        overlayStack.addView(focalHint, new LinearLayout.LayoutParams(-2, -2));

        portraitLight = new PortraitLightView(this);
        portraitLight.select(Math.max(0, Math.min(PortraitLightView.Preset.values().length - 1, portraitPreset)), false);
        portraitLight.setListener(p -> {
            portraitPreset = p.ordinal();
            prefs.edit().putInt("portrait_light", portraitPreset).apply();
            applyPortraitStyle();
        });
        portraitLight.setVisibility(View.GONE);
        overlayStack.addView(portraitLight, new LinearLayout.LayoutParams(-1, dp(80)));

        // Zoom: discos de lente en reposo; al deslizar se abre la rueda en arco
        zoomDial = new ZoomDialView(this);
        zoomDial.setListener(new ZoomDialView.Listener() {
            @Override
            public void onZoomChanged(float targetZoom, boolean interactive) {
                closeAllPopovers();
                if (interactive) {
                    setZoomInteractive(targetZoom);
                } else {
                    float resolved = Math.abs(targetZoom - 0.55f) < 0.08f && caps != null
                            ? caps.zoomRange.getLower() : Math.round(targetZoom * 10f) / 10f;
                    setZoom(resolved, true);
                }
            }

            @Override
            public void onOneXRetapped() {
                closeAllPopovers();
                int i = 0;
                for (int k = 0; k < ONE_X_STEPS.length; k++) {
                    if (Math.abs(zoom - ONE_X_STEPS[k]) < 0.06f) {
                        i = k;
                    }
                }
                float next = ONE_X_STEPS[(i + 1) % ONE_X_STEPS.length];
                setZoom(next, true);
                showBanner(equivalentMmForZoom(next) + " mm");
            }
        });
        overlayStack.addView(zoomDial, new LinearLayout.LayoutParams(-1, dp(150)));

        FrameLayout.LayoutParams osp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        osp.bottomMargin = dp(BOTTOM_CHROME_DP + SHUTTER_ROW_DP);
        root.addView(overlayStack, osp);

        // 2. Fila del disparador: ƒ (izq) · disparador (centro) · 6 puntos (der)
        shutterRow = new FrameLayout(this);

        fStopBtn = new IconView(this, IconView.Kind.F_STOP);
        fStopBtn.setDisc(true);
        fStopBtn.setContentDescription("Apertura");
        fStopBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            cycleAperture();
        });
        FrameLayout.LayoutParams fsp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL);
        fsp.leftMargin = dp(40);
        shutterRow.addView(fStopBtn, fsp);

        shutter = new ShutterButton(this);
        shutter.setOnClickListener(v -> {
            closeAllPopovers();
            onShutter();
        });
        shutterRow.addView(shutter, new FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER));

        sixDotsBtn = new IconView(this, IconView.Kind.SIX_DOTS);
        sixDotsBtn.setDisc(true);
        sixDotsBtn.setContentDescription("Controles");
        sixDotsBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            togglePopover(popoverSheet);
        });
        FrameLayout.LayoutParams sdp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
        sdp.rightMargin = dp(40);
        shutterRow.addView(sixDotsBtn, sdp);

        liveSnapBtn = new View(this);
        liveSnapBtn.setBackground(liquidGlassCapsule(false, dp(22)));
        liveSnapBtn.setContentDescription("Capturar cuadro");
        liveSnapBtn.setVisibility(View.GONE);
        liveSnapBtn.setOnClickListener(v -> {
            if (engine != null && engine.isRecording()) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                sound.play(MediaActionSound.SHUTTER_CLICK);
                blink.setAlpha(0.55f);
                blink.animate().alpha(0f).setDuration(140).start();
                engine.takePortrait(deviceOrientation, 16f / 9f);
            }
        });
        FrameLayout.LayoutParams lsp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
        lsp.rightMargin = dp(40);
        shutterRow.addView(liveSnapBtn, lsp);

        FrameLayout.LayoutParams srp = new FrameLayout.LayoutParams(-1, dp(SHUTTER_ROW_DP), Gravity.BOTTOM);
        srp.bottomMargin = dp(BOTTOM_CHROME_DP);
        root.addView(shutterRow, srp);

        // 3. Chrome inferior negro: galería · modos · cambiar cámara (por encima de la barra de gestos)
        bottomChrome = new FrameLayout(this);
        bottomChrome.setBackgroundColor(Color.BLACK);
        bottomChrome.setPadding(0, 0, 0, dp(48));

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(rounded(Color.rgb(28, 28, 30), dp(22)));
        thumb.setClipToOutline(true);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        thumb.setContentDescription("Última captura");
        thumb.setOnClickListener(v -> openLast());
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL);
        tp.leftMargin = dp(18);
        bottomChrome.addView(thumb, tp);

        modeStrip = new ModeStrip(this);
        String[] labels = new String[MODES.length];
        for (int i = 0; i < MODES.length; i++) {
            labels[i] = MODES[i].label;
        }
        modeStrip.setLabels(labels, mode.ordinal());
        modeStrip.setListener(new ModeStrip.Listener() {
            @Override
            public void onModeSelected(int index) {
                selectMode(index);
            }

            @Override
            public void onActiveModeTapped() {
                if (mode == Mode.PRO) {
                    togglePopover(popoverSheet);
                } else {
                    closeAllPopovers();
                }
            }
        });
        FrameLayout.LayoutParams msp = new FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER_VERTICAL);
        msp.leftMargin = dp(72);
        msp.rightMargin = dp(72);
        bottomChrome.addView(modeStrip, msp);

        flip = new IconView(this, IconView.Kind.FLIP);
        flip.setDisc(true);
        flip.setContentDescription("Cambiar cámara");
        flip.setOnClickListener(v -> switchCamera());
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
        fp.rightMargin = dp(18);
        bottomChrome.addView(flip, fp);

        root.addView(bottomChrome, new FrameLayout.LayoutParams(-1, dp(BOTTOM_CHROME_DP), Gravity.BOTTOM));
    }

    /** Panel de Estilos y Piel (Pad 2D Tono×Color + Textura Piel Suave / Resplandor / Grano). */
    private void buildStylesSheet(LinearLayout parent) {
        stylesSheet = new LinearLayout(this);
        stylesSheet.setOrientation(LinearLayout.VERTICAL);
        stylesSheet.setClickable(true);
        stylesSheet.setPadding(dp(14), dp(12), dp(14), dp(12));
        stylesSheet.setBackground(liquidGlassSheet(dp(24)));
        stylesSheet.setVisibility(View.GONE);

        FrameLayout head = new FrameLayout(this);
        TextView title = label(11, YELLOW);
        title.setText("ESTILOS Y PIEL  ·  SUBTONO Y TEXTURA");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.07f);
        head.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout headRight = new LinearLayout(this);
        headRight.setOrientation(LinearLayout.HORIZONTAL);
        headRight.setGravity(Gravity.CENTER_VERTICAL);

        TextView resetBtn = label(11, Color.argb(215, 255, 255, 255));
        resetBtn.setText("Restaurar");
        resetBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        resetBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        resetBtn.setBackground(liquidGlassCapsule(false, dp(10)));
        resetBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            tonePad = 0f;
            colorPad = 0f;
            softSkin = 0.35f;
            glow = 0.15f;
            grain = 0f;
            if (engine != null) {
                engine.setProfile(ColorProfile.SIGNATURE_PRO);
                prefs.edit().putString("profile", ColorProfile.SIGNATURE_PRO.id).apply();
            }
            stylePad.setValues(0f, 0f);
            syncStyleParams();
            refreshStyleControls();
            buildStyles();
            showBanner("Perfil de piel y estilo restaurados");
        });
        headRight.addView(resetBtn);

        TextView closeBtn = label(11, Color.WHITE);
        closeBtn.setText("✕");
        closeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        closeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        closeBtn.setBackground(liquidGlassCapsule(false, dp(10)));
        closeBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            refreshUi();
        });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-2, -2);
        cbp.leftMargin = dp(6);
        headRight.addView(closeBtn, cbp);

        head.addView(headRight, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        stylesSheet.addView(head, new LinearLayout.LayoutParams(-1, -2));

        stylesScroll = new HorizontalScrollView(this);
        stylesScroll.setHorizontalScrollBarEnabled(false);
        stylesRow = new LinearLayout(this);
        stylesRow.setGravity(Gravity.CENTER_VERTICAL);
        stylesRow.setPadding(0, dp(6), 0, dp(6));
        stylesScroll.addView(stylesRow);
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, dp(44));
        srp.topMargin = dp(4);
        stylesSheet.addView(stylesScroll, srp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(4);
        stylesSheet.addView(mid, mp);

        stylePad = new StylePadView(this);
        stylePad.setValues(tonePad, colorPad);
        stylePad.setListener((t, c) -> {
            tonePad = t;
            colorPad = c;
            syncStyleParams();
            updatePadReadout();
        });
        mid.addView(stylePad, new LinearLayout.LayoutParams(dp(112), dp(112)));

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rcp = new LinearLayout.LayoutParams(0, -2, 1f);
        rcp.leftMargin = dp(12);
        mid.addView(rightCol, rcp);

        padReadout = label(11, Color.WHITE);
        padReadout.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        padReadout.setFontFeatureSettings("tnum");
        rightCol.addView(padReadout);

        TextView texTitle = label(10, Color.argb(175, 255, 255, 255));
        texTitle.setText("TEXTURA DE PIEL Y PELÍCULA");
        texTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        texTitle.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams ttp = new LinearLayout.LayoutParams(-2, -2);
        ttp.topMargin = dp(8);
        rightCol.addView(texTitle, ttp);

        textureTabsRow = new LinearLayout(this);
        textureTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, -2);
        trp.topMargin = dp(4);
        rightCol.addView(textureTabsRow, trp);

        LinearLayout sliderRow = new LinearLayout(this);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        textureValue = label(11, YELLOW);
        textureValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        textureValue.setFontFeatureSettings("tnum");
        textureValue.setMinWidth(dp(38));
        textureBar = new SeekBar(this);
        textureBar.setMax(100);
        textureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) {
                    return;
                }
                float v = p / 100f;
                if (textureTab == 0) {
                    softSkin = v;
                } else if (textureTab == 1) {
                    glow = v;
                } else {
                    grain = v;
                }
                syncStyleParams();
                textureValue.setText(p + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        sliderRow.addView(textureValue);
        sliderRow.addView(textureBar, new LinearLayout.LayoutParams(0, -2, 1f));
        rightCol.addView(sliderRow, slp);

        refreshStyleControls();

        LinearLayout.LayoutParams ssp = new LinearLayout.LayoutParams(-1, -2);
        ssp.leftMargin = dp(14);
        ssp.rightMargin = dp(14);
        ssp.bottomMargin = dp(10);
        parent.addView(stylesSheet, ssp);
    }

    private void refreshStyleControls() {
        if (textureTabsRow == null) {
            return;
        }
        updatePadReadout();
        textureTabsRow.removeAllViews();
        String[] names = {"Piel Suave", "Resplandor", "Grano"};
        for (int i = 0; i < names.length; i++) {
            int idx = i;
            boolean on = textureTab == i;
            TextView tab = label(10, on ? Color.BLACK : Color.WHITE);
            tab.setText(names[i]);
            tab.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dp(8), dp(5), dp(8), dp(5));
            tab.setBackground(on ? rounded(YELLOW, dp(12)) : liquidGlassCapsule(false, dp(12)));
            tab.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                textureTab = idx;
                refreshStyleControls();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i < names.length - 1) {
                lp.rightMargin = dp(5);
            }
            textureTabsRow.addView(tab, lp);
        }
        float cur = textureTab == 0 ? softSkin : (textureTab == 1 ? glow : grain);
        int pct = Math.round(cur * 100f);
        textureBar.setProgress(pct);
        textureValue.setText(pct + "%");
    }

    private void updatePadReadout() {
        if (padReadout != null) {
            padReadout.setText(String.format(Locale.US, "Tono %+.0f  ·  Color %+.0f",
                    tonePad * 100f, colorPad * 100f));
        }
    }

    /**
     * Foto/Retrato: visor 4:3 pegado al chrome superior, con negro debajo para el disparador.
     * Video: visor 16:9 que puede pasar por detrás de la fila del disparador.
     * Los discos de zoom quedan siempre sobre el borde inferior del visor.
     */
    private void layoutPreview(float aspect, boolean photo) {
        int w = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int screenH = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int h = Math.round(w * aspect);
        int topBar = dp(TOP_CHROME_DP);
        int avail = screenH - dp(BOTTOM_CHROME_DP) - topBar;
        int top = photo ? topBar : topBar + Math.max(0, (avail - h) / 2);
        for (View v : new View[] {preview, focusView, blink}) {
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.TOP);
            p.topMargin = top;
            v.setLayoutParams(p);
        }
        if (overlayStack != null) {
            int previewBottom = top + h;
            FrameLayout.LayoutParams op = (FrameLayout.LayoutParams) overlayStack.getLayoutParams();
            op.bottomMargin = Math.max(dp(BOTTOM_CHROME_DP + SHUTTER_ROW_DP - 8), screenH - previewBottom + dp(2));
            overlayStack.setLayoutParams(op);
        }
        if (root.getHeight() == 0) {
            root.post(() -> layoutPreview(aspect, photo));
        }
    }

    private void refreshUi() {
        boolean rec = engine != null && engine.isRecording();
        boolean photo = mode.photo;
        shutter.setStyle(photo ? ShutterButton.Style.PHOTO : ShutterButton.Style.VIDEO);
        shutter.setTickRing(mode == Mode.SLOWMO || mode == Mode.TIMELAPSE);
        modeStrip.setLocked(rec);
        if (modeStrip.selected() != mode.ordinal()) {
            modeStrip.select(mode.ordinal(), false);
        }

        boolean flashUsable = caps != null && caps.flash && mode != Mode.PORTRAIT && topShowFlash;
        flashBtn.setVisibility(flashUsable ? View.VISIBLE : View.GONE);
        if (photo) {
            flashBtn.setKind(flash == CameraController.Flash.OFF ? IconView.Kind.FLASH_OFF
                    : flash == CameraController.Flash.AUTO ? IconView.Kind.FLASH_AUTO : IconView.Kind.FLASH);
            flashBtn.setActive(flash == CameraController.Flash.ON);
        } else {
            flashBtn.setKind(torch ? IconView.Kind.FLASH : IconView.Kind.FLASH_OFF);
            flashBtn.setActive(torch);
        }

        if (topRatioPill != null) {
            topRatioPill.setVisibility(photo && !rec ? View.VISIBLE : View.GONE);
            topRatioPill.setText(RATIO_LABELS[ratioIndex]);
        }
        if (topTimerPill != null) {
            topTimerPill.setVisibility(photo && !rec ? View.VISIBLE : View.GONE);
            topTimerPill.setText(timerSec == 0 ? "⏱" : "⏱ " + timerSec + "s");
            topTimerPill.setTextColor(timerSec > 0 ? YELLOW : Color.WHITE);
            topTimerPill.setBackground(liquidGlassCapsule(timerSec > 0, dp(12)));
        }
        if (topProPill != null) {
            boolean proOpen = popoverSheet != null && popoverSheet.getVisibility() == View.VISIBLE;
            boolean hasManual = manualShutterNs > 0 || manualIso > 0 || manualWb != 1;
            topProPill.setVisibility(!rec ? View.VISIBLE : View.GONE);
            topProPill.setTextColor(proOpen || hasManual ? YELLOW : Color.WHITE);
            topProPill.setBackground(liquidGlassCapsule(proOpen || hasManual, dp(12)));
        }

        formatPill.setVisibility(!photo && !rec && topShowFormat ? View.VISIBLE : View.GONE);
        if (topHdrPill != null) {
            topHdrPill.setVisibility(!photo && !rec && topShowFormat ? View.VISIBLE : View.GONE);
            topHdrPill.setText(hdrEnabled ? "HDR · STEREO" : "SDR · STEREO");
            topHdrPill.setTextColor(hdrEnabled ? YELLOW : Color.WHITE);
            topHdrPill.setBackground(liquidGlassCapsule(hdrEnabled, dp(13)));
        }
        if (!photo && engine != null && engine.mode() != null) {
            VideoMode m = engine.mode();
            resText.setText(m.width >= 7680 ? "8K" : m.width >= 3840 ? "4K" : "HD");
            fpsText.setText(mode == Mode.TIMELAPSE ? TIMELAPSE_FACTOR + "×" : String.valueOf(m.fps));
        }
        nightBtn.setVisibility((mode == Mode.PHOTO || mode == Mode.PRO) && caps != null && caps.lowLightBoost && !rec
                ? View.VISIBLE : View.GONE);
        nightBtn.setActive(nightBoost);
        boolean rawAvailable = caps != null && caps.rawSize != null && (mode == Mode.PHOTO || mode == Mode.PRO);
        rawPill.setVisibility(rawAvailable && !rec ? View.VISIBLE : View.GONE);
        rawPill.setTextColor(raw ? YELLOW : Color.argb(175, 255, 255, 255));
        rawPill.setPaintFlags(raw ? rawPill.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG
                : rawPill.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        rawPill.setBackground(liquidGlassCapsule(raw, dp(12)));
        timerPill.setVisibility(!photo || rec ? View.VISIBLE : View.GONE);
        if (!rec) {
            timerPill.setAlpha(1f);
            timerPill.setText("00:00:00");
            timerPill.setBackground(liquidGlassCapsule(false, dp(14)));
        } else {
            timerPill.setBackground(rounded(RED, dp(14)));
        }
        boolean cine = mode == Mode.CINE || mode == Mode.PORTRAIT;
        fStopBtn.setVisibility(cine && !rec ? View.VISIBLE : View.GONE);
        topStyleBtn.setVisibility(!rec && topShowStyles ? View.VISIBLE : View.GONE);
        topStyleBtn.setActive(stylesSheet.getVisibility() == View.VISIBLE);
        portraitLight.setVisibility(mode == Mode.PORTRAIT && !rec ? View.VISIBLE : View.GONE);
        zoomDial.setSingle(mode == Mode.PORTRAIT);

        proHud.setVisibility(showProHud ? View.VISIBLE : View.GONE);
        focalHint.setVisibility(showProHud ? View.VISIBLE : View.GONE);
        sixDotsBtn.setVisibility(rec ? View.GONE : View.VISIBLE);
        sixDotsBtn.setActive(popoverSheet.getVisibility() == View.VISIBLE);
        flip.setVisibility(View.VISIBLE);
        flip.setAlpha(engine == null || engine.front == null ? 0.35f : 1f);
        liveSnapBtn.setVisibility(rec ? View.VISIBLE : View.GONE);
        thumb.setAlpha(rec ? 0.35f : 1f);
        updateFocalHint();
        buildZoomRow();
        buildStyles();
        buildPopoverSheet();
    }

    // ------------------------------------------------------------ zoom óptico flotante (Liquid Glass)

    private List<Float> zoomStops() {
        List<Float> stops = new ArrayList<>(caps.lensZooms);
        float max = caps.zoomRange.getUpper();
        if (stops.isEmpty()) {
            stops.add(1f);
        }
        if (!caps.front) {
            float tele = stops.get(stops.size() - 1);
            addStop(stops, 2f, max);
            if (tele >= 2.5f) {
                addStop(stops, Math.round(tele * 2f), max);
            }
        }
        stops.sort(Float::compare);
        return stops;
    }

    private static void addStop(List<Float> stops, float z, float max) {
        if (z > max + 0.01f) {
            return;
        }
        for (float s : stops) {
            if (Math.abs(s - z) < 0.3f) {
                return;
            }
        }
        stops.add(z);
    }

    private int equivalentMmForZoom(float z) {
        if (caps != null && caps.front) {
            return Math.round(23f * z);
        }
        return Math.round(24f * z);
    }

    private String sensorTagForZoom(float z) {
        if (caps != null && caps.front) {
            return "IMX816 AF · 23MM ƒ1.9";
        }
        if (z < 0.95f) {
            return "LEICA ULTRA WIDE · 12MM ƒ2.0 AF";
        }
        if (z < 1.85f) {
            return "LEICA MAIN · " + equivalentMmForZoom(z) + "MM ƒ1.4 OIS";
        }
        if (z < 2.85f) {
            return "LEICA 2× · 50MM ƒ1.4";
        }
        if (z < 5.2f) {
            return "LEICA TELE · 73MM ƒ2.4 OIS";
        }
        return "LEICA TELE 6× · " + equivalentMmForZoom(z) + "MM ƒ2.4";
    }

    private void updateFocalHint() {
        if (focalHint == null || caps == null) {
            return;
        }
        String profileLabel = engine != null && engine.profile() != null
                ? engine.profile().name.replace("Signature ", "").toUpperCase(Locale.US) : "PRO";
        focalHint.setText(sensorTagForZoom(zoom) + "  ·  " + profileLabel);
    }

    private void buildZoomRow() {
        if (zoomDial == null || caps == null) {
            return;
        }
        zoomDial.setConfig(zoomStops(), caps.zoomRange.getLower(), Math.min(10f, caps.zoomRange.getUpper()));
        zoomDial.setZoom(zoom);
        zoomDial.setIconRotation(iconRotation);
    }

    private static int opticalBand(float z) {
        if (z < 0.95f) {
            return 0;
        }
        if (z < 2.85f) {
            return 1;
        }
        return 2;
    }

    private void initSmoothZoom() {
        if (smoothZoom == null) {
            smoothZoom = new SmoothZoomController(
                    z -> {
                        int prevBand = opticalBand(zoom);
                        zoom = z;
                        if (opticalBand(zoom) != prevBand && engine != null) {
                            engine.triggerLensTransition(0.65f);
                        }
                        updateFocalHint();
                        if (zoomDial != null) {
                            zoomDial.setZoom(zoom);
                        }
                    },
                    halZ -> {
                        if (engine != null) {
                            engine.setZoom(halZ);
                        }
                    }
            );
        }
    }

    /** Zoom continuo al pasar el dedo sobre los números o al pellizcar el visor (con filtro amortiguado sin tirones). */
    private void setZoomInteractive(float target) {
        if (caps == null || engine == null) {
            return;
        }
        float clamped = Math.max(caps.zoomRange.getLower(), Math.min(caps.zoomRange.getUpper(), target));
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
        }
        initSmoothZoom();
        smoothZoom.setTargetZoom(clamped);
    }

    private void setZoom(float target, boolean animate) {
        if (caps == null || engine == null) {
            return;
        }
        float clamped = Math.max(caps.zoomRange.getLower(), Math.min(caps.zoomRange.getUpper(), target));
        if (zoomAnimator != null) {
            zoomAnimator.cancel();
        }
        initSmoothZoom();
        if (!animate || Math.abs(clamped - zoom) < 0.01f) {
            if (zoomDial != null) {
                zoomDial.clearTargetStopZoom();
            }
            smoothZoom.setImmediateZoom(clamped);
            return;
        }
        if (zoomDial != null) {
            zoomDial.setTargetStopZoom(clamped);
            zoomDial.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
        smoothZoom.setTargetZoom(clamped);
    }

    // ------------------------------------------------------------ Popover de Controles (botón de 6 puntos + Editar)

    private void closeAllPopovers() {
        editingWidgets = false;
        if (popoverSheet != null) {
            popoverSheet.setVisibility(View.GONE);
        }
        if (stylesSheet != null) {
            stylesSheet.setVisibility(View.GONE);
        }
        if (evRow != null) {
            evRow.setVisibility(View.GONE);
        }
        if (sixDotsBtn != null) {
            sixDotsBtn.setActive(false);
        }
        if (topProPill != null) {
            boolean hasManual = manualShutterNs > 0 || manualIso > 0 || manualWb != 1;
            topProPill.setTextColor(hasManual ? YELLOW : Color.WHITE);
            topProPill.setBackground(liquidGlassCapsule(hasManual, dp(12)));
        }
        if (topStyleBtn != null) {
            topStyleBtn.setActive(engine != null && engine.profile() != ColorProfile.NATURAL);
        }
        if (topGridBtn != null) {
            topGridBtn.setActive(showGrid);
        }
    }

    private void togglePopover(View target) {
        boolean show = target.getVisibility() != View.VISIBLE;
        closeAllPopovers();
        if (show) {
            target.setVisibility(View.VISIBLE);
            target.setAlpha(0f);
            target.setTranslationY(dp(12));
            target.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        refreshUi();
    }

    private void buildPopoverSheet() {
        if (popoverSheet == null || caps == null) {
            return;
        }
        popoverSheet.removeAllViews();

        FrameLayout header = new FrameLayout(this);
        TextView title = label(11, YELLOW);
        title.setText(editingWidgets ? "PERSONALIZAR WIDGETS" : "CONTROLES PRO · PIXEL & CINEMATIC");
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLetterSpacing(0.08f);
        header.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout headRight = new LinearLayout(this);
        headRight.setOrientation(LinearLayout.HORIZONTAL);
        headRight.setGravity(Gravity.CENTER_VERTICAL);

        boolean hasManual = manualShutterNs > 0 || manualIso > 0 || manualWb != 1 || Math.abs(ev) > 0.05f;
        if (!editingWidgets && hasManual) {
            TextView resetAuto = label(10, YELLOW);
            resetAuto.setText("Restaurar Auto");
            resetAuto.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            resetAuto.setPadding(dp(8), dp(4), dp(8), dp(4));
            resetAuto.setBackground(liquidGlassCapsule(true, dp(10)));
            resetAuto.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                manualShutterNs = 0;
                manualIso = 0;
                manualWb = 1;
                setEv(0f);
                if (engine != null) {
                    engine.setManualExposure(0, 0);
                    engine.setAwbMode(1);
                }
                showBanner("Exposición y Balance de Blancos restaurados a Auto");
                buildPopoverSheet();
                refreshUi();
            });
            LinearLayout.LayoutParams rap = new LinearLayout.LayoutParams(-2, -2);
            rap.rightMargin = dp(6);
            headRight.addView(resetAuto, rap);
        }

        TextView editBtn = label(11, Color.argb(220, 255, 255, 255));
        editBtn.setText(editingWidgets ? "Listo" : "Editar");
        editBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        editBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        editBtn.setBackground(liquidGlassCapsule(editingWidgets, dp(10)));
        editBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            editingWidgets = !editingWidgets;
            buildPopoverSheet();
        });
        headRight.addView(editBtn);

        TextView closeBtn = label(11, Color.WHITE);
        closeBtn.setText("✕");
        closeBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        closeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        closeBtn.setBackground(liquidGlassCapsule(false, dp(10)));
        closeBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            closeAllPopovers();
            refreshUi();
        });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-2, -2);
        cbp.leftMargin = dp(6);
        headRight.addView(closeBtn, cbp);

        header.addView(headRight, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        popoverSheet.addView(header, new LinearLayout.LayoutParams(-1, -2));

        if (editingWidgets) {
            LinearLayout editRow = new LinearLayout(this);
            editRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams erp = new LinearLayout.LayoutParams(-1, -2);
            erp.topMargin = dp(10);
            popoverSheet.addView(editRow, erp);

            editRow.addView(popoverTile("Flash superior", topShowFlash ? "Visible" : "Oculto", topShowFlash, () -> {
                topShowFlash = !topShowFlash;
                prefs.edit().putBoolean("top_flash", topShowFlash).apply();
                refreshUi();
            }), tileParams(true));

            editRow.addView(popoverTile("Formato 4K/FPS", topShowFormat ? "Visible" : "Oculta", topShowFormat, () -> {
                topShowFormat = !topShowFormat;
                prefs.edit().putBoolean("top_format", topShowFormat).apply();
                refreshUi();
            }), tileParams(true));

            editRow.addView(popoverTile("Botón Estilos", topShowStyles ? "Visible" : "Oculto", topShowStyles, () -> {
                topShowStyles = !topShowStyles;
                prefs.edit().putBoolean("top_styles", topShowStyles).apply();
                refreshUi();
            }), tileParams(false));

            TextView resetEssentials = sheetButton("Restaurar esenciales", false, () -> {
                topShowFlash = true;
                topShowFormat = true;
                topShowStyles = true;
                showProHud = false;
                showGrid = true;
                prefs.edit().putBoolean("top_flash", true).putBoolean("top_format", true)
                        .putBoolean("top_styles", true).putBoolean("pro_hud_v2", false).putBoolean("grid", true).apply();
                editingWidgets = false;
                refreshUi();
                showBanner("Controles restaurados a Esenciales");
            });
            LinearLayout.LayoutParams rep = new LinearLayout.LayoutParams(-1, -2);
            rep.topMargin = dp(8);
            popoverSheet.addView(resetEssentials, rep);
            return;
        }

        // 1. Selector forzado de lentes físicas
        buildLensSelectorRow(popoverSheet);

        // 2. Velocidad de Obturador manual
        buildShutterRowPro(popoverSheet);

        // 3. Sensibilidad ISO manual
        buildIsoRowPro(popoverSheet);

        // 4. Balance de Blancos manual
        buildWbRowPro(popoverSheet);

        // 5. Compensación EV
        buildEvRowPro(popoverSheet);

        // 6. Controles secundarios
        buildSecondaryControls(popoverSheet);
    }

    private void buildLensSelectorRow(LinearLayout parent) {
        TextView secTitle = label(9, Color.argb(190, 255, 255, 255));
        secTitle.setText("LENTES FÍSICAS  ·  HARDWARE DIRECTO");
        secTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        secTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
        stp.topMargin = dp(6);
        parent.addView(secTitle, stp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        float[] stops = caps != null && caps.front ? new float[] {1f} : new float[] {0.5f, 1f, 2f, 3f, 6f};
        String[] labels = caps != null && caps.front ? new String[] {"1× (23MM AF)"}
                : new String[] {".5× 12MM", "1× 24MM", "2× 50MM CROP", "3× 73MM", "6× 146MM CROP"};

        for (int i = 0; i < stops.length; i++) {
            float z = stops[i];
            String text = labels[i];
            boolean selected = Math.abs(zoom - z) < 0.08f;
            TextView btn = label(10, selected ? Color.BLACK : Color.WHITE);
            btn.setText(text);
            btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            btn.setPadding(dp(9), dp(5), dp(9), dp(5));
            btn.setBackground(selected ? rounded(YELLOW, dp(12)) : liquidGlassCapsule(false, dp(12)));
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                setZoom(z, true);
                showBanner(sensorTagForZoom(z));
                buildPopoverSheet();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            row.addView(btn, lp);
        }
        scroll.addView(row);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildShutterRowPro(LinearLayout parent) {
        TextView secTitle = label(9, Color.argb(190, 255, 255, 255));
        secTitle.setText("VELOCIDAD DE OBTURADOR" + (manualShutterNs > 0 ? "  [MANUAL]" : ""));
        secTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        secTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
        stp.topMargin = dp(5);
        parent.addView(secTitle, stp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        for (int i = 0; i < SHUTTER_SPEEDS_NS.length; i++) {
            long ns = SHUTTER_SPEEDS_NS[i];
            String label = SHUTTER_SPEEDS_LABELS[i];
            boolean selected = manualShutterNs == ns;
            TextView btn = label(10, selected ? Color.BLACK : Color.WHITE);
            btn.setText(label);
            btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            btn.setPadding(dp(8), dp(4), dp(8), dp(4));
            btn.setBackground(selected ? rounded(YELLOW, dp(12)) : liquidGlassCapsule(false, dp(12)));
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                manualShutterNs = ns;
                if (engine != null) {
                    engine.setManualExposure(manualShutterNs, manualIso);
                }
                showBanner(ns == 0 ? "Obturador: Automático" : "Obturador: " + label + " s");
                buildPopoverSheet();
                refreshUi();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(5);
            row.addView(btn, lp);
        }
        scroll.addView(row);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildIsoRowPro(LinearLayout parent) {
        TextView secTitle = label(9, Color.argb(190, 255, 255, 255));
        secTitle.setText("SENSIBILIDAD ISO" + (manualIso > 0 ? "  [MANUAL]" : ""));
        secTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        secTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
        stp.topMargin = dp(5);
        parent.addView(secTitle, stp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        for (int i = 0; i < ISO_STEPS.length; i++) {
            int iso = ISO_STEPS[i];
            String label = ISO_LABELS[i];
            boolean selected = manualIso == iso;
            TextView btn = label(10, selected ? Color.BLACK : Color.WHITE);
            btn.setText(label);
            btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            btn.setPadding(dp(8), dp(4), dp(8), dp(4));
            btn.setBackground(selected ? rounded(YELLOW, dp(12)) : liquidGlassCapsule(false, dp(12)));
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                manualIso = iso;
                if (engine != null) {
                    engine.setManualExposure(manualShutterNs, manualIso);
                }
                showBanner(iso == 0 ? "ISO: Automático" : "ISO: " + label);
                buildPopoverSheet();
                refreshUi();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(5);
            row.addView(btn, lp);
        }
        scroll.addView(row);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildWbRowPro(LinearLayout parent) {
        TextView secTitle = label(9, Color.argb(190, 255, 255, 255));
        secTitle.setText("BALANCE DE BLANCOS" + (manualWb != 1 ? "  [CALIBRADO]" : ""));
        secTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        secTitle.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-2, -2);
        stp.topMargin = dp(5);
        parent.addView(secTitle, stp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        for (int i = 0; i < WB_MODES.length; i++) {
            int mode = WB_MODES[i];
            String label = WB_LABELS[i];
            boolean selected = manualWb == mode;
            TextView btn = label(10, selected ? Color.BLACK : Color.WHITE);
            btn.setText(label);
            btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            btn.setPadding(dp(8), dp(4), dp(8), dp(4));
            btn.setBackground(selected ? rounded(YELLOW, dp(12)) : liquidGlassCapsule(false, dp(12)));
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                manualWb = mode;
                if (engine != null) {
                    engine.setAwbMode(manualWb);
                }
                showBanner("Balance de Blancos: " + label);
                buildPopoverSheet();
                refreshUi();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(5);
            row.addView(btn, lp);
        }
        scroll.addView(row);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildEvRowPro(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(5);

        TextView label = label(10, YELLOW);
        label.setText(String.format(Locale.US, "EV %+.1f", ev));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        label.setMinWidth(dp(44));
        row.addView(label);

        SeekBar bar = new SeekBar(this);
        bar.setMax(40);
        bar.setProgress(Math.round(ev * 10) + 20);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) {
                    setEv((p - 20) / 10f);
                    label.setText(String.format(Locale.US, "EV %+.1f", ev));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView reset = label(10, Color.argb(190, 255, 255, 255));
        reset.setText("0.0");
        reset.setPadding(dp(6), dp(3), dp(4), dp(3));
        reset.setOnClickListener(v -> {
            bar.setProgress(20);
            setEv(0f);
            label.setText("EV +0.0");
        });
        row.addView(reset);
        parent.addView(row, rlp);
    }

    private void buildSecondaryControls(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(5);
        parent.addView(row, rlp);

        String styleShort = engine != null ? engine.profile().name.replace("Signature ", "") : "Pro";
        row.addView(popoverTile("Estilos", styleShort, true, () -> togglePopover(stylesSheet)), tileParams(true));

        if (mode.photo) {
            row.addView(popoverTile("Aspecto", RATIO_LABELS[ratioIndex], ratioIndex != 0, this::cycleRatio), tileParams(true));
            if (caps != null && caps.rawSize != null) {
                row.addView(popoverTile("RAW DNG", raw ? "Activo" : "Off", raw, this::toggleRaw), tileParams(false));
            } else {
                row.addView(popoverTile("Temporizador", timerSec == 0 ? "Off" : timerSec + "s", timerSec > 0, this::cycleTimer), tileParams(false));
            }
        } else {
            String stabStr = stabilization == CameraController.Stabilization.ENHANCED ? "Acción"
                    : stabilization == CameraController.Stabilization.STANDARD ? "SmartEIS" : "Off";
            row.addView(popoverTile("Estabilizar", stabStr, stabilization != CameraController.Stabilization.OFF, this::cycleStabilization), tileParams(true));
            boolean cineAe = videoAe == ExposureController.Mode.CINEMATIC;
            row.addView(popoverTile("Obturador Cine", cineAe ? "180°" : "Auto", cineAe, () -> {
                videoAe = cineAe ? ExposureController.Mode.AUTO : ExposureController.Mode.CINEMATIC;
                prefs.edit().putString("ae", videoAe.name()).apply();
                if (engine != null) {
                    engine.setExposureMode(videoAe);
                }
                buildPopoverSheet();
            }), tileParams(false));
        }

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(-1, -2);
        r2p.topMargin = dp(5);
        parent.addView(row2, r2p);

        row2.addView(popoverTile("Cuadrícula", showGrid ? "Activa" : "Off", showGrid, this::toggleGrid), tileParams(true));
        row2.addView(popoverTile("HUD Pro", showProHud ? "Visible" : "Off", showProHud, () -> {
            showProHud = !showProHud;
            prefs.edit().putBoolean("pro_hud_v2", showProHud).apply();
            refreshUi();
        }), tileParams(true));
        row2.addView(sheetButton("Perfil: " + captureProfile.getName(), true, this::cycleCaptureProfile), tileParams(false));
    }

    private LinearLayout.LayoutParams tileParams(boolean rightMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        if (rightMargin) {
            p.rightMargin = dp(7);
        }
        return p;
    }

    private LinearLayout popoverTile(String title, String value, boolean active, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(8), dp(9), dp(8), dp(9));
        box.setBackground(liquidGlassCapsule(active, dp(16)));

        TextView t = label(10, Color.argb(185, 255, 255, 255));
        t.setText(title);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);

        TextView v = label(12, active ? YELLOW : Color.WHITE);
        v.setText(value);
        v.setSingleLine(true);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        box.addView(v);

        box.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            action.run();
        });
        return box;
    }

    private TextView sheetButton(String text, boolean active, Runnable onClick) {
        TextView b = label(11, active ? YELLOW : Color.WHITE);
        b.setText(text);
        b.setSingleLine(true);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(8), dp(9), dp(8), dp(9));
        b.setBackground(liquidGlassCapsule(active, dp(14)));
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            onClick.run();
        });
        return b;
    }

    private void buildStyles() {
        if (stylesRow == null || engine == null) {
            return;
        }
        stylesRow.removeAllViews();
        for (ColorProfile p : ColorProfile.ALL) {
            boolean on = p == engine.profile();
            TextView t = label(11, on ? Color.BLACK : Color.WHITE);
            t.setText(p.name.replace("Signature ", ""));
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            t.setBackground(on ? rounded(YELLOW, dp(15)) : liquidGlassCapsule(false, dp(15)));
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                engine.setProfile(p);
                prefs.edit().putString("profile", p.id).apply();
                showBanner(p.name + "  ·  " + p.description);
                updateFocalHint();
                buildStyles();
                buildPopoverSheet();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(7);
            lp.gravity = Gravity.CENTER_VERTICAL;
            stylesRow.addView(t, lp);
        }
    }

    private void showBanner(String message) {
        if (statusBanner == null) {
            return;
        }
        main.removeCallbacks(hideBanner);
        statusBanner.setText(message);
        if (statusBanner.getVisibility() != View.VISIBLE) {
            statusBanner.setAlpha(0f);
            statusBanner.setTranslationY(-dp(6));
            statusBanner.setVisibility(View.VISIBLE);
        }
        statusBanner.animate().alpha(1f).translationY(0f).setDuration(160).start();
        main.postDelayed(hideBanner, 2200);
    }

    // ------------------------------------------------------------ acciones

    private void toggleGrid() {
        showGrid = !showGrid;
        prefs.edit().putBoolean("grid", showGrid).apply();
        if (focusView != null) {
            focusView.setGridEnabled(showGrid);
        }
        if (topGridBtn != null) {
            topGridBtn.setActive(showGrid);
        }
        showBanner(showGrid ? "Cuadrícula y nivel activos" : "Cuadrícula desactivada");
        buildPopoverSheet();
    }

    private void toggleFlash() {
        if (engine == null || caps == null || !caps.flash) {
            return;
        }
        if (mode.photo) {
            CameraController.Flash[] all = CameraController.Flash.values();
            flash = all[(flash.ordinal() + 1) % all.length];
            prefs.edit().putString("flash", flash.name()).apply();
            engine.setFlash(flash);
        } else {
            torch = !torch;
            // No encender la linterna en vista previa; se encenderá exclusivamente
            // al iniciar la grabación y se apagará al finalizar.
            if (!torch && engine.isRecording()) {
                engine.setTorch(false);
            }
        }
        refreshUi();
    }

    private void cycleTimer() {
        int i = 0;
        for (int k = 0; k < TIMERS.length; k++) {
            if (TIMERS[k] == timerSec) {
                i = k;
            }
        }
        timerSec = TIMERS[(i + 1) % TIMERS.length];
        prefs.edit().putInt("timer", timerSec).apply();
        showBanner(timerSec == 0 ? "Temporizador desactivado" : "Temporizador: " + timerSec + " s");
        buildPopoverSheet();
    }

    private void cycleRatio() {
        ratioIndex = (ratioIndex + 1) % RATIOS.length;
        prefs.edit().putInt("ratio", ratioIndex).apply();
        focusView.setAspect(RATIOS[ratioIndex]);
        showBanner("Proporción " + RATIO_LABELS[ratioIndex]);
        buildPopoverSheet();
    }

    private void toggleRaw() {
        if (caps == null || caps.rawSize == null || mode != Mode.PHOTO) {
            return;
        }
        raw = !raw;
        prefs.edit().putBoolean("raw", raw).apply();
        applyMode();
        if (raw) {
            showBanner("Captura DNG + JPEG activa");
            main.postDelayed(() -> {
                if (engine != null && !engine.hasRaw()) {
                    showBanner("Esta lente entrega solo JPEG en esta sesión");
                }
            }, 1500);
        } else {
            showBanner("Captura JPEG");
        }
    }

    private void setEv(float value) {
        ev = Math.max(-2f, Math.min(2f, value));
        if (engine != null) {
            engine.setEvBias(ev);
        }
        evValue.setText(String.format(Locale.US, "%+.1f EV", ev));
        focusView.setEv(ev);
    }

    private void cycleAperture() {
        if (engine == null) {
            return;
        }
        float[] stops = DepthMath.F_STOPS;
        float cur = engine.fNumber();
        int i = 0;
        for (int k = 0; k < stops.length; k++) {
            if (Math.abs(stops[k] - cur) < 0.05f) {
                i = k;
            }
        }
        float next = stops[(i + 1) % stops.length];
        engine.setFNumber(next);
        prefs.edit().putFloat("fnumber", next).apply();
        showBanner(String.format(Locale.US, "Apertura Variable ƒ%.2f", next).replace(".00", ".0").replace("80", "8"));
        refreshUi();
    }

    private void cycleStabilization() {
        CameraController.Stabilization[] all = CameraController.Stabilization.values();
        stabilization = all[(stabilization.ordinal() + 1) % all.length];
        prefs.edit().putString("stab", stabilization.name()).apply();
        if (engine != null) {
            engine.setStabilization(stabilization);
        }
        String label = stabilization == CameraController.Stabilization.ENHANCED ? "Modo Acción (Estabilización máxima)"
                : stabilization == CameraController.Stabilization.STANDARD ? "Estabilización estándar activa" : "Estabilización desactivada";
        showBanner(label);
        buildPopoverSheet();
    }

    private void cycleVideoMode(boolean resolution) {
        if (engine == null || engine.isRecording() || mode == Mode.TIMELAPSE || mode == Mode.SLOWMO) {
            return;
        }
        VideoMode cur = engine.mode();
        List<VideoMode> modes = caps.modes;
        int start = modes.indexOf(cur);
        for (int step = 1; step <= modes.size(); step++) {
            VideoMode m = modes.get((start + step) % modes.size());
            boolean ok = resolution ? m.fps == cur.fps && m.width != cur.width : m.width == cur.width && m.fps != cur.fps;
            if (ok && (mode != Mode.CINE || VideoEngine.cinematicAllowed(m))) {
                prefs.edit().putInt("mode_" + caps.id, modes.indexOf(m)).apply();
                applyMode();
                showBanner(m.label() + "  ·  HEVC " + (m.targetBitrate() / 1_000_000) + " Mbps");
                return;
            }
        }
    }

    private void switchCamera() {
        if (engine == null || engine.front == null || engine.back == null) {
            return;
        }
        closeAllPopovers();
        flip.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        boolean toFront = (caps == null || !caps.front);
        caps = toFront ? engine.front : engine.back;
        prefs.edit().putBoolean("front", toFront).apply();
        flip.animate().rotationYBy(180f).scaleX(0.82f).scaleY(0.82f).setDuration(160)
                .withEndAction(() -> flip.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                .start();
        engine.freezeLastFrame();
        engine.triggerLensTransition(0.55f);
        if (engine.isRecording()) {
            zoom = 1f;
            initSmoothZoom();
            smoothZoom.setImmediateZoom(1f);
            if (torch && toFront) {
                engine.setTorch(false);
            }
            engine.switchCameraWhileRecording(caps);
            refreshUi();
        } else {
            suppressNextPulse = true;
            applyMode();
        }
        showBanner(toFront ? "Cámara Frontal · 23MM ƒ1.9 Smart HDR" : "Cámara Trasera Triple · 12MM / 24MM / 73MM");
    }

    private void cycleCaptureProfile() {
        if (engine == null || engine.isRecording() || caps == null) {
            return;
        }
        List<CaptureProfile> usable = CaptureProfile.selectable(caps.modes);
        if (usable.isEmpty()) {
            return;
        }
        int i = usable.indexOf(captureProfile);
        applyCaptureProfile(usable.get((i + 1) % usable.size()));
    }

    private void applyCaptureProfile(CaptureProfile p) {
        if (engine == null || caps == null || engine.isRecording()) {
            return;
        }
        captureProfile = p;
        int modeIndex = Math.max(0, caps.modes.indexOf(p.resolve(caps.modes)));
        stabilization = VideoEngine.toCamera(p.getStabilization());
        videoAe = p.getExposureMode();
        engine.setProfile(ColorProfile.byId(p.getColorProfileId()));
        prefs.edit().putString("capture", p.getId()).putInt("mode_" + caps.id, modeIndex)
                .putString("stab", stabilization.name()).putString("ae", videoAe.name())
                .putString("profile", p.getColorProfileId()).apply();
        showBanner(p.getName() + "  ·  " + p.getDescription());
        if (!mode.photo) {
            applyMode();
        } else {
            refreshUi();
        }
    }

    // ------------------------------------------------------------ disparo

    private void onShutter() {
        if (engine == null || countingDown) {
            return;
        }
        if (!mode.photo) {
            toggleRecording();
            return;
        }
        if (timerSec > 0) {
            startCountdown(timerSec);
        } else {
            capturePhoto();
        }
    }

    private void startCountdown(int seconds) {
        countingDown = true;
        countdown.setVisibility(View.VISIBLE);
        int[] left = {seconds};
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!countingDown) {
                    return;
                }
                if (left[0] == 0) {
                    countingDown = false;
                    countdown.setVisibility(View.GONE);
                    capturePhoto();
                    return;
                }
                countdown.setText(String.valueOf(left[0]--));
                main.postDelayed(this, 1000);
            }
        });
    }

    private void capturePhoto() {
        if (engine == null || !shutter.isEnabled()) {
            return;
        }
        shutter.setEnabled(false);
        // Zero Shutter Lag (ZSL < 35ms): reactivar disparador en 45ms mientras el guardado corre en background
        main.postDelayed(() -> shutter.setEnabled(true), 45);
        sound.play(MediaActionSound.SHUTTER_CLICK);
        blink.setAlpha(0.75f);
        blink.animate().alpha(0f).setDuration(85).start();
        if (mode == Mode.PORTRAIT) {
            engine.takePortrait(deviceOrientation, RATIOS[ratioIndex]);
        } else {
            engine.takePhoto(flash, deviceOrientation, RATIOS[ratioIndex]);
        }
    }

    private void toggleRecording() {
        if (engine == null) {
            return;
        }
        if (engine.isRecording()) {
            engine.stopRecording();
            if (torch) {
                engine.setTorch(false);
            }
            sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
            shutter.setRecording(false);
            main.removeCallbacks(tick);
        } else {
            closeAllPopovers();
            int slowdown = mode == Mode.SLOWMO && engine.mode() != null ? Math.max(1, engine.mode().fps / 30) : 1;
            engine.startRecording(deviceOrientation, mode == Mode.TIMELAPSE ? TIMELAPSE_FACTOR : 1, slowdown);
            if (engine.isRecording()) {
                if (torch && caps != null && !caps.front) {
                    engine.setTorch(true);
                }
                sound.play(MediaActionSound.START_VIDEO_RECORDING);
                shutter.setRecording(true);
                recordStart = SystemClock.elapsedRealtime();
                main.post(tick);
            }
        }
        refreshUi();
    }

    private final Runnable tick = new Runnable() {
        private boolean pulsePhase;
        @Override
        public void run() {
            long s = (SystemClock.elapsedRealtime() - recordStart) / 1000;
            timerPill.setText(String.format(Locale.US, "● %02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60));
            pulsePhase = !pulsePhase;
            timerPill.animate().alpha(pulsePhase ? 0.84f : 1.0f).setDuration(450).start();
            main.postDelayed(this, 500);
        }
    };

    private void openLast() {
        try {
            Intent i = lastUri != null ? new Intent(Intent.ACTION_VIEW).setDataAndType(lastUri, lastMime)
                    : new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (RuntimeException e) {
            showBanner("No hay una galería para abrir la captura");
        }
    }

    // ------------------------------------------------------------ gestos sobre la vista previa

    private void installGestures() {
        ScaleGestureDetector scale = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        initSmoothZoom();
                        if (zoomDial != null) {
                            zoomDial.clearTargetStopZoom();
                        }
                        float factor = 1.0f + (d.getScaleFactor() - 1.0f) * 0.75f;
                        smoothZoom.setTargetZoom(smoothZoom.getCurrentZoom() * factor);
                        return true;
                    }
                });
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private boolean focused;
            private boolean downTriggeredFocus;

            @Override
            public boolean onDown(MotionEvent e) {
                downTriggeredFocus = false;
                if (engine != null && e.getPointerCount() == 1
                        && popoverSheet.getVisibility() != View.VISIBLE
                        && stylesSheet.getVisibility() != View.VISIBLE
                        && evRow.getVisibility() != View.VISIBLE
                        && preview.getWidth() > 0 && preview.getHeight() > 0) {
                    if (engine.isLocked()) {
                        engine.setLocked(false);
                        lockPill.setVisibility(View.GONE);
                    }
                    // Respuesta instantánea en MotionEvent.ACTION_DOWN (< 16ms de latencia táctil)
                    engine.focusAt(e.getX() / preview.getWidth(), e.getY() / preview.getHeight());
                    focusView.showAt(e.getX(), e.getY());
                    preview.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    focused = true;
                    downTriggeredFocus = true;
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (engine == null) {
                    return false;
                }
                if (popoverSheet.getVisibility() == View.VISIBLE || stylesSheet.getVisibility() == View.VISIBLE
                        || evRow.getVisibility() == View.VISIBLE) {
                    closeAllPopovers();
                    return true;
                }
                if (!downTriggeredFocus) {
                    if (engine.isLocked()) {
                        engine.setLocked(false);
                        lockPill.setVisibility(View.GONE);
                    }
                    engine.focusAt(e.getX() / preview.getWidth(), e.getY() / preview.getHeight());
                    focusView.showAt(e.getX(), e.getY());
                    preview.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    focused = true;
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (engine == null) {
                    return false;
                }
                if (engine.isCinematic()) {
                    engine.setCinematicAutoFocus();
                    focusView.hide();
                    showBanner("Enfoque automático en sujeto");
                } else {
                    engine.focusAt(e.getX() / preview.getWidth(), e.getY() / preview.getHeight());
                    focusView.showAt(e.getX(), e.getY());
                    showBanner("Seguimiento de enfoque en sujeto");
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (engine == null) {
                    return;
                }
                engine.focusAt(e.getX() / preview.getWidth(), e.getY() / preview.getHeight());
                focusView.showAt(e.getX(), e.getY());
                main.postDelayed(() -> {
                    engine.setLocked(true);
                    focusView.hold();
                    lockPill.setVisibility(View.VISIBLE);
                    preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }, 380);
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (engine == null || b.getPointerCount() > 1 || Math.abs(dy) < Math.abs(dx) * 1.4f || !focused) {
                    return false;
                }
                // Curva de respuesta logarítmica/progresiva iOS 27.2 para control fino de exposición tras enfocar
                float normDy = dy / Math.max(1f, preview.getHeight());
                float logWeight = 0.65f + 0.55f * (float) Math.log1p(Math.abs(ev));
                setEv(ev + normDy * 2.4f * logWeight);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || engine == null || engine.isRecording() || Math.abs(vx) < Math.abs(vy) * 1.5f
                        || Math.abs(vx) < 800) {
                    return false;
                }
                modeStrip.select(mode.ordinal() + (vx < 0 ? 1 : -1), true);
                return true;
            }
        });
        preview.setOnTouchListener((v, e) -> {
            scale.onTouchEvent(e);
            if (!scale.isInProgress()) {
                gestures.onTouchEvent(e);
            }
            return true;
        });
    }

    private void rotateIcons() {
        iconRotation = -deviceOrientation;
        if (iconRotation < -180) {
            iconRotation += 360;
        }
        for (View v : new View[] {flashBtn, topStyleBtn, nightBtn, fStopBtn, sixDotsBtn, thumb, flip}) {
            if (v != null) {
                v.animate().rotation(iconRotation).setDuration(250).start();
            }
        }
        if (zoomDial != null) {
            zoomDial.setIconRotation(iconRotation);
        }
    }

    // ------------------------------------------------------------ VideoEngine.Ui

    @Override
    public void onLiveHud(int iso, long exposureNs, float aperture, float liveZoom, String physicalId,
            float audioDb, float[] histogram, float clipFraction) {
        if (proHud == null || engine == null) {
            return;
        }
        String look = engine.profile() != null ? engine.profile().name.replace("Signature ", "") : "Pro";
        String stab = stabilization == CameraController.Stabilization.ENHANCED ? "ACTION"
                : stabilization == CameraController.Stabilization.STANDARD ? "SMART EIS" : "OFF";
        float ap = aperture;
        if (caps != null && !caps.front) {
            if (zoom < 0.95f) {
                ap = 2.0f;
            } else if (zoom >= 2.85f) {
                ap = 2.4f;
            } else {
                ap = 1.6f;
            }
        }
        proHud.update(iso, exposureNs, ap, equivalentMmForZoom(zoom), look, stab, audioDb, !mode.photo,
                histogram, clipFraction);
    }

    @Override
    public void onMetrics(String text, float audioDb) {
        if (showMetrics) {
            metricsLabel.setText(text);
        }
    }

    @Override
    public void onRecordingSaved(Uri video, boolean ok) {
        if (!ok) {
            showBanner("La grabación no se pudo guardar");
            return;
        }
        lastUri = video;
        lastMime = "video/mp4";
        showBanner("Video guardado en Galería");
        new Thread(() -> {
            Bitmap t = PhotoSaver.videoThumbnail(this, video);
            main.post(() -> {
                if (t != null) {
                    thumb.setImageBitmap(t);
                }
            });
        }, "thumb").start();
    }

    @Override
    public void onPhotoSaved(Uri photo, Bitmap thumbnail) {
        shutter.setEnabled(true);
        lastUri = photo;
        lastMime = "image/jpeg";
        if (thumbnail != null) {
            thumb.setImageBitmap(thumbnail);
            thumb.setScaleX(0.6f);
            thumb.setScaleY(0.6f);
            thumb.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(1.6f)).setDuration(240).start();
        }
    }

    @Override
    public void onPhotoFailed(String message) {
        shutter.setEnabled(true);
        showBanner(message);
    }

    @Override
    public void onError(String message) {
        showBanner(message);
    }

    // ------------------------------------------------------------ materiales Liquid Glass

    private TextView label(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private void requestHighRefreshRate120Hz() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.Display display = getDisplay();
                if (display != null) {
                    android.view.Display.Mode[] modes = display.getSupportedModes();
                    android.view.Display.Mode best = null;
                    for (android.view.Display.Mode m : modes) {
                        if (m.getRefreshRate() >= 119f && (best == null || Math.abs(m.getRefreshRate() - 120f) < Math.abs(best.getRefreshRate() - 120f))) {
                            best = m;
                        }
                    }
                    if (best != null) {
                        WindowManager.LayoutParams lp = getWindow().getAttributes();
                        lp.preferredDisplayModeId = best.getModeId();
                        getWindow().setAttributes(lp);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private GradientDrawable liquidGlassCapsule(boolean active, float radius) {
        int top = active ? Color.argb(95, 255, 214, 10) : Color.argb(145, 58, 58, 66);
        int bot = active ? Color.argb(42, 255, 214, 10) : Color.argb(125, 20, 20, 24);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bot});
        d.setCornerRadius(radius);
        // Borde especular sutil de 1dp con blanco al 18% de opacidad (Color.argb(46, 255, 255, 255))
        d.setStroke(Math.max(1, dp(1)), active ? Color.argb(190, 255, 214, 10) : Color.argb(46, 255, 255, 255));
        return d;
    }

    private GradientDrawable liquidGlassSheet(float radius) {
        int top = Color.argb(228, 38, 38, 44);
        int bot = Color.argb(236, 16, 16, 20);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bot});
        d.setCornerRadius(radius);
        d.setStroke(Math.max(1, dp(1)), Color.argb(46, 255, 255, 255));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
