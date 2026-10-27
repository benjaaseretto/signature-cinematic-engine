package com.signature.cinematic.record;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** MediaMuxer compartido por video y audio; guarda muestras hasta que ambas pistas existen. */
final class Muxer {
    private static final class Pending {
        final boolean video;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        Pending(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.video = video;
            this.data = data;
            this.info = info;
        }
    }

    private final MediaMuxer muxer;
    private boolean expectAudio;
    private final List<Pending> pending = new ArrayList<>();
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean started;
    private long videoSamples;

    Muxer(FileDescriptor fd, int orientationHint, boolean expectAudio) throws IOException {
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxer.setOrientationHint(orientationHint);
        this.expectAudio = expectAudio;
    }

    synchronized void audioUnavailable() {
        expectAudio = false;
        maybeStart();
    }

    synchronized void addTrack(boolean video, MediaFormat format) {
        if (video) {
            videoTrack = muxer.addTrack(format);
        } else {
            audioTrack = muxer.addTrack(format);
        }
        maybeStart();
    }

    private void maybeStart() {
        if (!started && videoTrack >= 0 && (!expectAudio || audioTrack >= 0)) {
            muxer.start();
            started = true;
            for (Pending p : pending) {
                write(p.video, p.data, p.info);
            }
            pending.clear();
        }
    }

    synchronized void writeSample(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
        if (!started) {
            ByteBuffer copy = ByteBuffer.allocateDirect(info.size);
            data.position(info.offset).limit(info.offset + info.size);
            copy.put(data).flip();
            MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
            ci.set(0, info.size, info.presentationTimeUs, info.flags);
            pending.add(new Pending(video, copy, ci));
            return;
        }
        write(video, data, info);
    }

    private void write(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
        muxer.writeSampleData(video ? videoTrack : audioTrack, data, info);
        if (video) {
            videoSamples++;
        }
    }

    synchronized long videoSamples() {
        return videoSamples;
    }

    synchronized void release() {
        try {
            if (started) {
                muxer.stop();
            }
        } finally {
            muxer.release();
        }
    }
}
