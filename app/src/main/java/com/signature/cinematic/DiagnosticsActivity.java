package com.signature.cinematic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import com.signature.cinematic.diag.CameraCapabilityScanner;
import com.signature.cinematic.diag.CapabilityProbe;
import com.signature.cinematic.diag.ReportWriter;
import com.signature.cinematic.diag.VendorTagProbe;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Ejecuta la inspección de hardware y guarda el informe. */
public final class DiagnosticsActivity extends Activity {
    private TextView text;
    private Uri savedJson;
    private Uri savedMd;
    private Uri savedCsv;
    private Uri savedVendorCsv;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = dp(16);
        root.setPadding(pad, dp(32), pad, pad);

        Button share = new Button(this);
        share.setText("Compartir informe");
        share.setOnClickListener(v -> share());
        root.addView(share, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        text = new TextView(this);
        text.setTextColor(Color.WHITE);
        text.setTextSize(12);
        text.setTypeface(Typeface.MONOSPACE);
        text.setGravity(Gravity.START);
        text.setText("Inspeccionando cámaras, codificadores, GPU, pantalla y audio…");
        scroll.addView(text);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
        } else {
            runProbe();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        runProbe();
    }

    private void runProbe() {
        new Thread(() -> {
            String shown;
            try {
                runOnUiThread(() -> text.setText("Inspeccionando… (la tabla de compatibilidad puede tardar)"));
                JSONObject report = new CapabilityProbe(this).run();
                VendorTagProbe vendor = new VendorTagProbe(this);
                List<VendorTagProbe.Tag> tags = vendor.scan();
                report.put("vendor_tags", vendor.toJson(tags));
                CameraCapabilityScanner scanner = new CameraCapabilityScanner(this);
                List<CameraCapabilityScanner.Row> rows = scanner.scan();
                report.put("compatibility", scanner.toJson(rows));
                String backId = null;
                for (String id : getSystemService(CameraManager.class).getCameraIdList()) {
                    Integer f = getSystemService(CameraManager.class).getCameraCharacteristics(id)
                            .get(CameraCharacteristics.LENS_FACING);
                    if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                        backId = id;
                        break;
                    }
                }
                String md = ReportWriter.markdown(report) + "\n## Tabla de compatibilidad\n"
                        + rows.size() + " combinaciones · " + scanner.statusCounts(rows) + "\n\n"
                        + scanner.profileSummary(rows, backId) + "\n" + vendor.markdown(tags);
                String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                savedJson = ReportWriter.saveToDownloads(this, "diagnostico_" + stamp + ".json",
                        "application/json", report.toString(2));
                savedMd = ReportWriter.saveToDownloads(this, "diagnostico_" + stamp + ".md", "text/markdown", md);
                savedCsv = ReportWriter.saveToDownloads(this, "compatibilidad_" + stamp + ".csv", "text/csv",
                        scanner.toCsv(rows));
                savedVendorCsv = ReportWriter.saveToDownloads(this, "claves_motorola_" + stamp + ".csv", "text/csv",
                        vendor.toCsv(tags));
                shown = "Guardado en Descargas/SignatureCinematic/: diagnostico_" + stamp
                        + ".json/.md, compatibilidad_" + stamp + ".csv y claves_motorola_" + stamp + ".csv\n\n" + md;
            } catch (Throwable t) {
                shown = "Error en el diagnóstico: " + t;
            }
            String finalText = shown;
            runOnUiThread(() -> text.setText(finalText));
        }, "probe").start();
    }

    private void share() {
        if (savedJson == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
        send.setType("*/*");
        java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
        uris.add(savedJson);
        if (savedMd != null) {
            uris.add(savedMd);
        }
        if (savedCsv != null) {
            uris.add(savedCsv);
        }
        if (savedVendorCsv != null) {
            uris.add(savedVendorCsv);
        }
        send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Compartir diagnóstico"));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
