package com.signature.cinematic;

import com.signature.cinematic.color.ColorProfile;
import com.signature.cinematic.color.ToneCurve;
import com.signature.cinematic.control.ExposureControllerAccess;
import com.signature.cinematic.control.FrameStats;
import com.signature.cinematic.control.WhiteBalanceSmoother;

/** Pruebas de la lógica pura (sin Android). Ejecutar con test.sh. */
public final class EngineLogicTest {
    private static int failures;

    public static void main(String[] args) {
        toneIdentity();
        toneMonotonicAndBounded();
        profilesKeepMidGray();
        meteringError();
        exposurePlan();
        cinematicHysteresisAndSlew();
        apertureCompensation();
        whiteBalanceSmoothing();
        frameStats();
        if (failures > 0) {
            System.out.println(failures + " fallos");
            System.exit(1);
        }
        System.out.println("OK");
    }

    static void toneIdentity() {
        ToneCurve id = ToneCurve.identity();
        for (int i = 0; i <= 100; i++) {
            float x = i / 100f;
            check(Math.abs(id.apply(x) - x) < 1e-5, "identidad en " + x);
        }
        byte[] lut = id.toLut8();
        for (int i = 0; i < 256; i++) {
            check((lut[i] & 0xff) == i, "LUT identidad en " + i);
        }
    }

    static void toneMonotonicAndBounded() {
        for (ColorProfile p : ColorProfile.ALL) {
            float prev = -1f;
            for (int i = 0; i <= 1000; i++) {
                float y = p.tone.apply(i / 1000f);
                check(y >= prev - 1e-6, p.id + ": curva no monótona en " + i);
                check(y >= 0f && y <= 1f, p.id + ": fuera de rango en " + i);
                prev = y;
            }
        }
    }

    static void profilesKeepMidGray() {
        for (ColorProfile p : ColorProfile.ALL) {
            float[] m = p.matrix;
            float y = 0f;
            float[] w = {0.2126f, 0.7152f, 0.0722f};
            for (int r = 0; r < 3; r++) {
                y += w[r] * (m[r * 3] + m[r * 3 + 1] + m[r * 3 + 2]);
            }
            check(Math.abs(y - 1f) < 0.01f, p.id + ": la matriz cambia la luminancia del gris (" + y + ")");
        }
        check(ColorProfile.NEUTRAL_FLAT.tone.apply(0f) > 0.03f, "flat levanta negros");
        check(ColorProfile.NEUTRAL_FLAT.tone.apply(1f) < 0.97f, "flat baja el blanco");
    }

    static void meteringError() {
        float target = (float) Math.log(0.18);
        FrameStats ok = new FrameStats((float) Math.log(0.18), 0f, 0.6f);
        check(Math.abs(ExposureControllerAccess.meteringError(ok, 0f, target)) < 1e-4, "error nulo en el objetivo");
        FrameStats dark = new FrameStats((float) Math.log(0.045), 0f, 0.2f);
        check(Math.abs(ExposureControllerAccess.meteringError(dark, 0f, target) - 2f) < 1e-3, "dos pasos de falta de luz");
        FrameStats clipped = new FrameStats((float) Math.log(0.18), 0.08f, 1f);
        check(ExposureControllerAccess.meteringError(clipped, 0f, target) < -0.9f, "altas luces quemadas bajan la exposición");
        check(Math.abs(ExposureControllerAccess.meteringError(ok, 1f, target) - 1f) < 1e-3, "compensación +1 EV");
    }

    static void exposurePlan() {
        // Escena interior: debe usar múltiplos de 10 ms (red de 50 Hz) a 30 fps.
        long[] indoor = ExposureControllerAccess.plan(1600, 30f);
        check(indoor[0] % 10_000_000L == 0, "anti-parpadeo: " + indoor[0]);
        check(indoor[0] <= 33_333_333L, "no supera la duración de cuadro");
        // Exteriores: obturador corto con ISO mínimo.
        long[] outdoor = ExposureControllerAccess.plan(-1, 30f);
        check(outdoor[1] == 100, "ISO mínimo en exteriores: " + outdoor[1]);
        // A 60 fps nunca más de 16.6 ms.
        long[] sixty = ExposureControllerAccess.plan(3200, 60f);
        check(sixty[0] <= 16_666_667L, "60 fps respeta la duración de cuadro");
        // Luz media: 180° (1/60 a 30 fps).
        long[] mid = ExposureControllerAccess.plan(200, 30f);
        check(Math.abs(mid[0] - 16_666_667L) < 2, "obturador a 180°: " + mid[0]);
        check(Math.abs(mid[1] - 200) <= 1, "ISO a 180°: " + mid[1]);
        // Perfil B (60 fps) con buena luz: 1/120.
        long[] action = ExposureControllerAccess.planProfile(8_333_333.0 * 150, 60f, 0.5f, 6400);
        check(Math.abs(action[0] - 8_333_333L) < 2, "acción a 1/120: " + action[0]);
        // Perfil C (24 fps) con buena luz: 1/48.
        long[] cine = ExposureControllerAccess.planProfile(20_833_333.0 * 200, 24f, 0.5f, 3200);
        check(Math.abs(cine[0] - 20_833_333L) < 2, "24p a 1/48: " + cine[0]);
        // Con poca luz el tope de ISO alarga el obturador en lugar de subexponer.
        long[] dim = ExposureControllerAccess.planProfile(20_000_000.0 * 3000, 30f, 0.5f, 1600);
        check(dim[1] <= 1600, "respeta el tope de ISO: " + dim[1]);
        check(dim[0] > 16_666_667L && dim[0] <= 33_333_333L, "alarga el obturador: " + dim[0]);
        // Nocturno: hasta la duración de cuadro.
        long[] night = ExposureControllerAccess.planProfile(33_000_000.0 * 6000, 30f, 1.0f, 6400);
        check(night[0] >= 30_000_000L && night[0] <= 33_333_333L, "nocturno usa cuadro completo: " + night[0]);
    }

    static void cinematicHysteresisAndSlew() {
        ExposureControllerAccess c = new ExposureControllerAccess(true);
        c.seed(16_666_667L, 400);
        long t = 0;
        // Cambio pequeño (0.2 EV): dentro de la zona muerta, no se mueve.
        double before = c.logE();
        for (int i = 0; i < 30; i++) {
            t += 33_333_333L;
            c.stats(c.target() - 0.2f * (float) Math.log(2), t);
        }
        check(Math.abs(c.logE() - before) < 1e-9, "zona muerta evita bombeo");
        // Cambio grande (+3 EV de luz): sube despacio, como mucho 0.75 EV/s.
        before = c.logE();
        for (int i = 0; i < 30; i++) {
            t += 33_333_333L;
            c.stats(c.target() + 3f * (float) Math.log(2), t);
        }
        double moved = before - c.logE();
        check(moved > 0.4 && moved <= 0.76, "transición suave en 1 s: " + moved + " EV");
    }

    static void apertureCompensation() {
        ExposureControllerAccess c = new ExposureControllerAccess(true);
        c.seed(16_666_667L, 400);
        c.result(16_666_667L, 400, 1.68f);
        double before = c.logE();
        c.result(16_666_667L, 400, 2.48f);
        double expected = 2 * Math.log(2.48 / 1.68) / Math.log(2);
        check(Math.abs(c.logE() - before - expected) < 1e-6, "compensa el diafragma al cambiar de lente");
    }

    static void whiteBalanceSmoothing() {
        WhiteBalanceSmoother wb = new WhiteBalanceSmoother();
        wb.setEnabled(true);
        long t = 0;
        for (int i = 0; i < 30; i++) {
            t += 33_333_333L;
            wb.update(2.0f, 1f, 1f, 1.6f, "3", t);
        }
        float[] c0 = wb.correctionFor(2.0f, 1f, 1f, 1.6f, "3");
        check(Math.abs(c0[0] - 1f) < 1e-4 && Math.abs(c0[2] - 1f) < 1e-4, "estable: sin corrección");

        // Conservación estricta de luminancia Rec.709 (anti-quemado de blancos)
        float luma0 = 0.2126f * c0[0] + 0.7152f * c0[1] + 0.0722f * c0[2];
        check(Math.abs(luma0 - 1f) < 1e-4, "luminancia neutra conservada");

        // Salto brusco del AWB: el primer fotograma se compensa sin quemar
        t += 33_333_333L;
        wb.update(2.3f, 1f, 1f, 1.4f, "3", t);
        float[] c1 = wb.correctionFor(2.3f, 1f, 1f, 1.4f, "3");
        check(c1[0] < 0.92f && c1[2] > 1.08f, "salto compensado: " + c1[0] + "," + c1[2]);
        float luma1 = 0.2126f * c1[0] + 0.7152f * c1[1] + 0.0722f * c1[2];
        check(Math.abs(luma1 - 1f) < 1e-4, "luminancia con salto conservada: " + luma1);

        // Tras varios segundos llega al nuevo valor.
        for (int i = 0; i < 300; i++) {
            t += 33_333_333L;
            wb.update(2.3f, 1f, 1f, 1.4f, "3", t);
        }
        float[] c2 = wb.correctionFor(2.3f, 1f, 1f, 1.4f, "3");
        check(Math.abs(c2[0] - 1f) < 0.02f && Math.abs(c2[2] - 1f) < 0.02f, "converge: " + c2[0] + "," + c2[2]);

        // Conmutación a Ultra Gran Angular (.5x / Sensor "2"):
        // Sensor "2" tiene offset natural (+0.20, -0.13). Para la misma escena entrega gains mayores en R y menores en B:
        float r2 = (float) (2.3f * Math.exp(0.20));
        float b2 = (float) (1.4f * Math.exp(-0.13));
        t += 33_333_333L;
        wb.update(r2, 1f, 1f, b2, "2", t);
        float[] cSwitch = wb.correctionFor(r2, 1f, 1f, b2, "2");
        check(Math.abs(cSwitch[0] - 1f) < 0.08f && Math.abs(cSwitch[2] - 1f) < 0.08f,
                "conmutación multi-cámara correlacionada: " + cSwitch[0] + "," + cSwitch[2]);
        float lumaSwitch = 0.2126f * cSwitch[0] + 0.7152f * cSwitch[1] + 0.0722f * cSwitch[2];
        check(Math.abs(lumaSwitch - 1f) < 1e-4, "luminancia en conmutación conservada");

        wb.setEnabled(false);
        float[] off = wb.correctionFor(2.0f, 1f, 1f, 1.6f, "3");
        check(off[0] == 1f && off[2] == 1f, "desactivado = identidad");
    }

    static void frameStats() {
        int w = 8;
        int h = 8;
        byte[] gray = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            gray[i * 4] = (byte) 118;
            gray[i * 4 + 1] = (byte) 118;
            gray[i * 4 + 2] = (byte) 118;
        }
        FrameStats s = FrameStats.compute(gray, w, h, 0.5f, 0.5f);
        check(Math.abs(Math.exp(s.logMeanLuma) - 0.181) < 0.01, "gris sRGB 118 ≈ 18 %: " + Math.exp(s.logMeanLuma));
        check(s.clipFraction == 0f, "sin recorte");
        for (int i = 0; i < 16; i++) {
            gray[i * 4] = (byte) 255;
        }
        check(FrameStats.compute(gray, w, h, 0.5f, 0.5f).clipFraction == 0.25f, "25 % recortado");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FALLO: " + what);
        }
    }
}
