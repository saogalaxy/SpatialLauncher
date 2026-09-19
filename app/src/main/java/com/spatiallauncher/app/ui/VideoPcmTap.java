package com.spatiallauncher.app.ui;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ExoPlayer {@link TeeAudioProcessor} sink → 16 kHz mono float ring for Listen ASR.
 * Playback continues normally; this only forks PCM.
 */
final class VideoPcmTap implements TeeAudioProcessor.AudioBufferSink, PlaybackListenEngine.PcmSource {
    private static final int OUT_RATE = 16000;
    private static final int RING_SAMPLES = OUT_RATE * 4; // ~4 s

    private final Object lock = new Object();
    private final float[] ring = new float[RING_SAMPLES];
    private int writePos;
    private int available;

    private int inRate = 48000;
    private int inChannels = 2;
    private int encoding = C.ENCODING_PCM_16BIT;
    private double resampleCursor;

    @Override
    public void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding) {
        synchronized (lock) {
            inRate = Math.max(1, sampleRateHz);
            inChannels = Math.max(1, channelCount);
            this.encoding = encoding;
            writePos = 0;
            available = 0;
            resampleCursor = 0;
        }
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.hasRemaining()) {
            return;
        }
        ByteBuffer src = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        synchronized (lock) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                ingestFloat(src);
            } else {
                ingestPcm16(src);
            }
        }
    }

    private void ingestPcm16(ByteBuffer src) {
        int frameBytes = 2 * inChannels;
        while (src.remaining() >= frameBytes) {
            float mono = 0f;
            for (int c = 0; c < inChannels; c++) {
                mono += src.getShort() / 32768f;
            }
            mono /= inChannels;
            emitResampled(mono);
        }
    }

    private void ingestFloat(ByteBuffer src) {
        int frameBytes = 4 * inChannels;
        while (src.remaining() >= frameBytes) {
            float mono = 0f;
            for (int c = 0; c < inChannels; c++) {
                mono += src.getFloat();
            }
            mono /= inChannels;
            emitResampled(mono);
        }
    }

    /** Decimate/interpolate toward {@link #OUT_RATE} using a running phase cursor. */
    private void emitResampled(float sample) {
        resampleCursor += (double) OUT_RATE / (double) inRate;
        while (resampleCursor >= 1.0) {
            resampleCursor -= 1.0;
            ring[writePos] = sample;
            writePos = (writePos + 1) % RING_SAMPLES;
            if (available < RING_SAMPLES) {
                available++;
            }
        }
    }

    @Override
    public int read(float[] out, int offset, int length) {
        if (out == null || length <= 0) {
            return 0;
        }
        synchronized (lock) {
            int n = Math.min(length, available);
            if (n <= 0) {
                return 0;
            }
            int readPos = (writePos - available + RING_SAMPLES) % RING_SAMPLES;
            for (int i = 0; i < n; i++) {
                out[offset + i] = ring[readPos];
                readPos = (readPos + 1) % RING_SAMPLES;
            }
            available -= n;
            return n;
        }
    }

    void clear() {
        synchronized (lock) {
            writePos = 0;
            available = 0;
            resampleCursor = 0;
        }
    }
}
