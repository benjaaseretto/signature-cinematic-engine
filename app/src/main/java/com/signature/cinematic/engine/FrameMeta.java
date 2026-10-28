package com.signature.cinematic.engine;

/** Ajustes que el ISP aplicó a un fotograma, indexados por timestamp del sensor. */
public final class FrameMeta {
    public long timestampNs;
    public long exposureNs;
    public int iso;
    public float aperture;
    public float zoom;
    /** Ganancias de balance de blancos R, Geven, Godd, B. */
    public final float[] wbGains = {1f, 1f, 1f, 1f};
    public String physicalId;

    void copyFrom(FrameMeta o) {
        timestampNs = o.timestampNs;
        exposureNs = o.exposureNs;
        iso = o.iso;
        aperture = o.aperture;
        zoom = o.zoom;
        System.arraycopy(o.wbGains, 0, wbGains, 0, 4);
        physicalId = o.physicalId;
    }

    /** Buffer circular sin asignaciones por fotograma. */
    public static final class Ring {
        private final FrameMeta[] items;
        private int next;

        public Ring(int capacity) {
            items = new FrameMeta[capacity];
            for (int i = 0; i < capacity; i++) {
                items[i] = new FrameMeta();
            }
        }

        public synchronized void put(FrameMeta m) {
            items[next].copyFrom(m);
            next = (next + 1) % items.length;
        }

        /** Copia en out el metadato más cercano a timestampNs (tolerancia 2 ms). */
        public synchronized boolean find(long timestampNs, FrameMeta out) {
            FrameMeta best = null;
            long bestDiff = 2_000_000L;
            for (FrameMeta m : items) {
                long d = Math.abs(m.timestampNs - timestampNs);
                if (m.timestampNs != 0 && d <= bestDiff) {
                    best = m;
                    bestDiff = d;
                }
            }
            if (best == null) {
                return false;
            }
            out.copyFrom(best);
            return true;
        }

        /**
         * Busca el metadato exacto (≤ 2 ms) o, si la cola EIS desplazó el timestamp o el callback
         * de captura aún está en vuelo, devuelve el metadato válido más cercano/reciente para
         * evitar caídas a matriz identidad entre fotogramas.
         */
        public synchronized boolean findOrLatest(long timestampNs, FrameMeta out) {
            if (find(timestampNs, out)) {
                return true;
            }
            FrameMeta closest = null;
            long closestDiff = Long.MAX_VALUE;
            FrameMeta newest = null;
            long newestTs = Long.MIN_VALUE;
            for (FrameMeta m : items) {
                if (m.timestampNs == 0) {
                    continue;
                }
                long d = Math.abs(m.timestampNs - timestampNs);
                if (d < closestDiff && d <= 60_000_000L) {
                    closestDiff = d;
                    closest = m;
                }
                if (m.timestampNs > newestTs) {
                    newestTs = m.timestampNs;
                    newest = m;
                }
            }
            FrameMeta pick = closest != null ? closest : newest;
            if (pick == null) {
                return false;
            }
            out.copyFrom(pick);
            return true;
        }
    }
}
