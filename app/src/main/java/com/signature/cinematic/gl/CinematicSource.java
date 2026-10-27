package com.signature.cinematic.gl;

/** Lo que el modo Cine aporta al hilo GL. Todos los métodos se llaman en el hilo GL y no deben bloquear. */
public interface CinematicSource {
    /** Mapas de profundidad (0..1, 1 = cerca) y de persona (0..1), fila 0 arriba del cuadro. */
    final class Maps {
        public final byte[] depth;
        public final int depthW;
        public final int depthH;
        public final byte[] mask;
        public final int maskW;
        public final int maskH;

        public Maps(byte[] depth, int depthW, int depthH, byte[] mask, int maskW, int maskH) {
            this.depth = depth;
            this.depthW = depthW;
            this.depthH = depthH;
            this.mask = mask;
            this.maskW = maskW;
            this.maskH = maskH;
        }
    }

    /** true si el modelo está libre para un fotograma nuevo (si no, no se lee nada de la GPU). */
    boolean wantsFrame();

    /** Fotograma sin procesar en orientación de la vista, fila 0 arriba. El receptor se queda con el array. */
    void onFrame(byte[] rgba, int width, int height, long timestampNs);

    /** Mapas nuevos desde la última llamada, o null. */
    Maps pollMaps();

    /** Profundidad de foco para este fotograma (con la transición de foco aplicada). */
    float focusDepth(long timestampNs);

    /** 0..1: cuánto se protege del desenfoque la máscara de persona. */
    float maskFocus();

    /** Radio máximo de desenfoque como fracción de la altura del cuadro. */
    float maxBlurFraction();
}
