package com.signature.cinematic.engine;

/** Resolución y cadencia de grabación ya validadas contra cámara y codificador. */
public final class VideoMode {
    public final int width;
    public final int height;
    public final int fps;
    /** Límite inferior del rango de AE declarado por la cámara (igual a fps si es fijo). */
    public final int fpsLower;
    /** El codificador acepta el cuadro en vertical (alto mayor que ancho). */
    public final boolean encoderPortrait;

    public VideoMode(int width, int height, int fps, int fpsLower, boolean encoderPortrait) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.fpsLower = fpsLower;
        this.encoderPortrait = encoderPortrait;
    }

    public String label() {
        String res;
        if (width >= 7680) {
            res = "8K";
        } else if (width >= 3840) {
            res = "4K";
        } else if (width >= 1920) {
            res = "HD";
        } else {
            res = height + "p";
        }
        return res + " · " + fps;
    }

    /** Bitrate HEVC orientativo; se recorta al rango que declare el codificador. */
    public int targetBitrate() {
        double pixelsPerSecond = (double) width * height * fps;
        // ~0.24 bits por píxel a 4K30 (~60 Mbps) y ~0.20 bpp a 4K60 (~100 Mbps) para textura limpia tipo Pro.
        double bpp = fps > 30 ? 0.20 : 0.24;
        return (int) Math.min(220_000_000L, Math.round(pixelsPerSecond * bpp));
    }

    @Override
    public String toString() {
        return width + "x" + height + "@" + fps;
    }
}
