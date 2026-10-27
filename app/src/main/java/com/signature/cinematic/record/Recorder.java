package com.signature.cinematic.record;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import com.signature.cinematic.engine.VideoMode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Codificador de video (entrada por superficie) + audio + MP4 en Movies/SignatureCinematic. */
public final class Recorder {
    private static final String TAG = "Recorder";

    private final Context context;
    private final VideoMode mode;
    private final boolean cameraRealtimeTimestamps;
    /** Aceleración del time-lapse (1 = tiempo real). Con >1 no se graba audio. */
    private final int timelapse;
    /** Cámara lenta: los PTS se estiran este factor (p. ej. 120 fps → 4× a 30 fps). Sin audio. */
    private int slowdown = 1;

    private MediaCodec video;
    private Surface inputSurface;
    private boolean warmedUp;
    private Muxer muxer;
    private AudioCapture audio;
    private Thread drainThread;
    private Uri uri;
    private ParcelFileDescriptor pfd;
    private long baseNs;
    private long bootToMonoNs;
    private long lastVideoPtsNs = -1;
    private final boolean frontCamera;
    public String mime;
    public int bitrate;
    public int width;
    public int height;

    public Recorder(Context context, VideoMode mode, boolean cameraRealtimeTimestamps) {
        this(context, mode, cameraRealtimeTimestamps, false, 1);
    }

    public Recorder(Context context, VideoMode mode, boolean cameraRealtimeTimestamps, int timelapse) {
        this(context, mode, cameraRealtimeTimestamps, false, timelapse);
    }

    public Recorder(Context context, VideoMode mode, boolean cameraRealtimeTimestamps, boolean frontCamera, int timelapse) {
        this.context = context.getApplicationContext();
        this.mode = mode;
        this.cameraRealtimeTimestamps = cameraRealtimeTimestamps;
        this.frontCamera = frontCamera;
        this.timelapse = Math.max(1, timelapse);
    }

    public void setSlowdown(int factor) {
        slowdown = Math.max(1, factor);
    }

    /**
     * Pre-inicializa el codificador de video por hardware y su InputSurface en segundo plano
     * mientras el usuario está en el visor, eliminando la latencia al presionar Grabar (<50 ms).
     */
    public synchronized void warmUp() throws IOException {
        if (warmedUp && video != null && inputSurface != null) {
            return;
        }
        width = mode.encoderPortrait ? mode.height : mode.width;
        height = mode.encoderPortrait ? mode.width : mode.height;
        MediaFormat format = null;
        for (String m : new String[] {MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC}) {
            MediaFormat f = videoFormat(m);
            String name = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f);
            if (name != null) {
                video = MediaCodec.createByCodecName(name);
                format = f;
                mime = m;
                break;
            }
        }
        if (video == null) {
            throw new IOException("Ningún codificador acepta " + mode);
        }
        video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = video.createInputSurface();
        warmedUp = true;
    }

    public synchronized void releaseWarmUp() {
        warmedUp = false;
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (RuntimeException ignored) {
            }
            inputSurface = null;
        }
        if (video != null) {
            try {
                video.release();
            } catch (RuntimeException ignored) {
            }
            video = null;
        }
    }

    /**
     * Prepara el codificador y el archivo. Devuelve la superficie donde dibujar los fotogramas.
     * @param orientationHint giro que debe aplicar el reproductor (0/90/180/270)
     */
    public synchronized Surface prepare(int orientationHint) throws IOException {
        if (!warmedUp || video == null || inputSurface == null) {
            warmUp();
        }
        warmedUp = false;

        String name = "SC_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SignatureCinematic");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        ContentResolver cr = context.getContentResolver();
        uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) {
            throw new IOException("No se pudo crear el archivo de video");
        }
        pfd = cr.openFileDescriptor(uri, "rw");
        muxer = new Muxer(pfd.getFileDescriptor(), orientationHint, true);
        return inputSurface;
    }

    private MediaFormat videoFormat(String m) {
        int w = mode.encoderPortrait ? mode.height : mode.width;
        int h = mode.encoderPortrait ? mode.width : mode.height;
        MediaFormat f = MediaFormat.createVideoFormat(m, w, h);
        bitrate = mode.targetBitrate();
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, mode.fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        f.setInteger(MediaFormat.KEY_COMPLEXITY, 100);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        if (m.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
        } else {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        }
        return f;
    }

    /** Arranca el codificador; los fotogramas deben dibujarse después de esta llamada. */
    public void start() {
        video.start();
        drainThread = new Thread(this::drainVideo, "video-drain");
        drainThread.start();
        if (timelapse > 1 || slowdown > 1) {
            long m0 = System.nanoTime();
            long b0 = SystemClock.elapsedRealtimeNanos();
            long m1 = System.nanoTime();
            bootToMonoNs = b0 - ((m0 + m1) / 2);
            baseNs = m1;
            audio = null;
            muxer.audioUnavailable();
            return;
        }
        audio = new AudioCapture(context, muxer, frontCamera);
        long audioStartMonoNs = audio.start();
        long m0 = System.nanoTime();
        long b0 = SystemClock.elapsedRealtimeNanos();
        long m1 = System.nanoTime();
        bootToMonoNs = b0 - ((m0 + m1) / 2);
        if (audioStartMonoNs <= 0L) {
            Log.w(TAG, "Grabando sin audio");
            baseNs = m1;
            audio = null;
            muxer.audioUnavailable();
        } else {
            baseNs = audioStartMonoNs;
        }
    }

    /**
     * Convierte el timestamp físico del sensor de cámara al reloj del archivo (ns desde baseNs),
     * anclado 1:1 al reloj monotónico real (CLOCK_MONOTONIC) compartido con AudioRecord para
     * garantizar sincronización labial (A/V lip-sync) exacta con cero deriva.
     */
    public long presentationNs(long cameraTimestampNs) {
        long frameNs = 1_000_000_000L / Math.max(1, mode.fps);
        long stepNs = Math.max(1000L, frameNs * slowdown / timelapse);
        long mono;
        if (cameraTimestampNs <= 0L) {
            // Cuadro puente sintetizado por framePacer durante conmutación de cámara
            mono = System.nanoTime() - frameNs;
        } else {
            mono = cameraRealtimeTimestamps ? (cameraTimestampNs - bootToMonoNs) : cameraTimestampNs;
        }
        long deltaFromStart = mono - baseNs;
        if (deltaFromStart < 0L && lastVideoPtsNs < 0L) {
            // Descartar cuadros en vuelo en la cola ISP/SmartEIS expuestos antes de pulsar Grabar
            return -1L;
        }
        long rawPts = Math.max(0L, deltaFromStart) / timelapse * slowdown;
        long pts;
        if (lastVideoPtsNs < 0L) {
            pts = rawPts;
        } else if (cameraTimestampNs <= 0L) {
            pts = Math.max(rawPts, lastVideoPtsNs + stepNs);
        } else {
            pts = Math.max(rawPts, lastVideoPtsNs + stepNs / 4);
        }
        lastVideoPtsNs = pts;
        return pts;
    }

    public float audioLevelDb() {
        AudioCapture a = audio;
        return a != null ? a.levelDb : -90f;
    }

    public boolean hasAudio() {
        return audio != null;
    }

    private void drainVideo() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int idx;
            try {
                idx = video.dequeueOutputBuffer(info, 10_000);
            } catch (IllegalStateException e) {
                break;
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.addTrack(true, video.getOutputFormat());
                continue;
            }
            if (idx < 0) {
                continue;
            }
            ByteBuffer out = video.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && out != null) {
                muxer.writeSample(true, out, info);
            }
            video.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
    }

    /**
     * Cierra el archivo. Llamar cuando el hilo GL ya dejó de dibujar en la superficie.
     * Devuelve la Uri del video o null si falló.
     */
    public Uri stop() {
        Uri result = uri;
        try {
            if (audio != null) {
                audio.stop();
            }
            video.signalEndOfInputStream();
            drainThread.join(5000);
            if (audio != null) {
                audio.join();
            }
            boolean hasFrames = muxer.videoSamples() > 0;
            muxer.release();
            if (!hasFrames) {
                result = null;
            }
        } catch (InterruptedException | RuntimeException e) {
            Log.w(TAG, "stop", e);
            result = null;
        } finally {
            try {
                video.stop();
            } catch (IllegalStateException ignored) {
                // Ya detenido.
            }
            video.release();
            inputSurface.release();
            try {
                pfd.close();
            } catch (IOException ignored) {
                // El archivo ya quedó escrito o se descarta abajo.
            }
            ContentResolver cr = context.getContentResolver();
            if (result != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, v, null, null);
            } else {
                cr.delete(uri, null, null);
            }
        }
        return result;
    }
}
