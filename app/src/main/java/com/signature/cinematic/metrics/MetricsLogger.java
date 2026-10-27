package com.signature.cinematic.metrics;

import android.content.Context;
import android.os.BatteryManager;
import android.os.PowerManager;

/**
 * Cadencia real, fotogramas perdidos, tiempo de render, térmica, batería y memoria.
 * Se alimenta desde el hilo GL; las filas por segundo se guardan como CSV al terminar.
 */
public final class MetricsLogger {
    public static final String CSV_HEADER =
            "t_s,fps,dropped,render_ms_avg,render_ms_max,thermal,headroom,battery_ua,heap_mb,exp_ms,iso,gain,lens,zoom\n";

    private final PowerManager power;
    private final BatteryManager battery;
    private final StringBuilder csv = new StringBuilder(CSV_HEADER);

    private float expectedFps = 30f;
    private long lastTs;
    private long windowStartNs;
    private long startNs;
    private int frames;
    private int dropped;
    private long renderSum;
    private long renderMax;
    private boolean recording;

    private volatile String overlay = "";
    private volatile int thermalStatus;
    private volatile long totalDropped;

    public MetricsLogger(Context context) {
        power = context.getSystemService(PowerManager.class);
        battery = context.getSystemService(BatteryManager.class);
    }

    public synchronized void reset(float fps, boolean recording) {
        expectedFps = fps;
        this.recording = recording;
        lastTs = 0;
        windowStartNs = 0;
        startNs = 0;
        frames = 0;
        dropped = 0;
        renderSum = 0;
        renderMax = 0;
        totalDropped = 0;
        if (recording) {
            csv.setLength(0);
            csv.append(CSV_HEADER);
        }
    }

    /** @return true si se cerró una ventana de un segundo (para refrescar la interfaz). */
    public synchronized boolean onFrame(long timestampNs, long renderNs, long exposureNs, int iso, float gain,
                                        String lens, float zoom) {
        if (lastTs != 0) {
            double interval = (timestampNs - lastTs) / 1e9;
            int missing = (int) Math.round(interval * expectedFps) - 1;
            if (missing > 0 && interval > 1.5 / expectedFps) {
                dropped += missing;
                totalDropped += missing;
            }
        }
        lastTs = timestampNs;
        if (windowStartNs == 0) {
            windowStartNs = timestampNs;
            startNs = timestampNs;
        }
        frames++;
        renderSum += renderNs;
        renderMax = Math.max(renderMax, renderNs);
        long window = timestampNs - windowStartNs;
        if (window < 1_000_000_000L) {
            return false;
        }
        float fps = frames * 1e9f / window;
        float avgMs = renderSum / 1e6f / frames;
        thermalStatus = power.getCurrentThermalStatus();
        float headroom = power.getThermalHeadroom(10);
        long currentUa = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        if (recording) {
            csv.append(String.format(java.util.Locale.US, "%.1f,%.2f,%d,%.2f,%.2f,%d,%.3f,%d,%d,%.2f,%d,%.3f,%s,%.2f\n",
                    (timestampNs - startNs) / 1e9, fps, dropped, avgMs, renderMax / 1e6f, thermalStatus, headroom,
                    currentUa, heapMb, exposureNs / 1e6, iso, gain, lens == null ? "" : lens, zoom));
        }
        overlay = String.format(java.util.Locale.US, "%.1f fps · perdidos %d · GPU %.1f ms · ISO %d · 1/%d · T%d",
                fps, totalDropped, avgMs, iso, exposureNs > 0 ? Math.round(1e9 / exposureNs) : 0, thermalStatus);
        windowStartNs = timestampNs;
        frames = 0;
        dropped = 0;
        renderSum = 0;
        renderMax = 0;
        return true;
    }

    public String overlay() {
        return overlay;
    }

    public int thermalStatus() {
        return thermalStatus;
    }

    public synchronized String csv() {
        return csv.toString();
    }
}
