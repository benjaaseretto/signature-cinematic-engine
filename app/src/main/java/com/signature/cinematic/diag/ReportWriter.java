package com.signature.cinematic.diag;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Informe legible y guardado en Descargas/SignatureCinematic. */
public final class ReportWriter {
    private ReportWriter() {}

    public static String markdown(JSONObject report) throws Exception {
        StringBuilder sb = new StringBuilder();
        JSONObject d = report.getJSONObject("device");
        sb.append("# Diagnóstico de video\n\n")
                .append(d.optString("manufacturer")).append(' ').append(d.optString("model"))
                .append(" · ").append(d.optString("soc_model"))
                .append(" · Android ").append(d.optString("android"))
                .append(" (SDK ").append(d.optInt("sdk")).append(")\n\n");
        JSONObject s = report.getJSONObject("summary");
        section(sb, "Confirmado", s.getJSONArray("confirmado"));
        section(sb, "No disponible", s.getJSONArray("no_disponible"));
        section(sb, "No determinado", s.getJSONArray("no_determinado"));

        sb.append("## Modos validados por cámara (stream único a GPU)\n\n");
        JSONArray cams = report.getJSONArray("cameras");
        for (int i = 0; i < cams.length(); i++) {
            JSONObject c = cams.getJSONObject(i);
            JSONArray checks = c.optJSONArray("session_checks");
            if (checks == null || checks.length() == 0) {
                continue;
            }
            sb.append("### Cámara ").append(c.optString("id")).append(" (").append(c.optString("facing"))
                    .append(") zoom ").append(c.optString("zoom_ratio_range")).append("\n");
            for (int k = 0; k < checks.length(); k++) {
                JSONObject o = checks.getJSONObject(k);
                Object ok = o.opt("supported");
                sb.append("- ").append(o.optString("mode")).append(' ').append(o.optString("dynamic_range"))
                        .append(" estab=").append(o.optString("stabilization")).append(": ")
                        .append(Boolean.TRUE.equals(ok) ? "sí" : (Boolean.FALSE.equals(ok) ? "no" : "error " + o.optString("error")));
                if (o.has("zoom_ratio_range")) {
                    sb.append(" (zoom ").append(o.optString("zoom_ratio_range")).append(')');
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void section(StringBuilder sb, String title, JSONArray items) throws Exception {
        sb.append("## ").append(title).append("\n\n");
        for (int i = 0; i < items.length(); i++) {
            sb.append("- ").append(items.getString(i)).append('\n');
        }
        sb.append('\n');
    }

    /** Devuelve la Uri del archivo guardado. */
    public static Uri saveToDownloads(Context context, String name, String mime, String content) throws Exception {
        ContentResolver cr = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.MIME_TYPE, mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SignatureCinematic");
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IllegalStateException("No se pudo crear " + name);
        }
        try (OutputStream os = cr.openOutputStream(uri)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return uri;
    }
}
