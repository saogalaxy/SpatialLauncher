package com.spatiallauncher.app.ui;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeMap;

/**
 * Desktop Link PC→Quest audio: Opus UDP on :8767 with a small jitter buffer + PLC.
 * Packet: [OP][ver][flags][seq u32][pts u64][len u16][opus…].
 */
final class DesktopLinkAudioReceiver {
    private static final String TAG = "DesktopLinkAudio";
    static final int PORT = 8767;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAME_SAMPLES = 960; // 20 ms
    private static final int MAGIC = 0x4F50;
    private static final int TARGET_DEPTH = 1; // ~20 ms — start ASAP
    private static final int MAX_DEPTH = 4; // ~80 ms cap
    private static final long PLAYOUT_DELAY_MS = 20L;

    private final Object lock = new Object();
    private volatile boolean running;
    private Thread recvThread;
    private Thread playThread;
    private DatagramSocket socket;
    private AudioTrack track;
    private OpusDecoder decoder;
    private final TreeMap<Long, byte[]> jitter = new TreeMap<>();
    private long nextSeq = -1;
    private long startedAtMs;
    private long firstPtsUs = -1;
    private long packetsRecv;
    private long packetsPlayed;
    private int peakSinceLog;

    boolean isRunning() {
        return running;
    }

    void start() {
        stop();
        running = true;
        startedAtMs = System.currentTimeMillis();
        firstPtsUs = -1;
        nextSeq = -1;
        try {
            decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);
        } catch (OpusException e) {
            Log.e(TAG, "Opus decoder init failed", e);
            running = false;
            return;
        }
        int min = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(min, FRAME_SAMPLES * CHANNELS * 2 * 6);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();

        recvThread = new Thread(this::recvLoop, "DesktopLinkAudioRx");
        playThread = new Thread(this::playLoop, "DesktopLinkAudioPlay");
        recvThread.start();
        playThread.start();
        Log.i(TAG, "Audio receive started on UDP " + PORT);
    }

    /** Drop queued Opus and resync playout — use on video reconnect without tearing UDP down. */
    void flush() {
        synchronized (lock) {
            jitter.clear();
            nextSeq = -1;
            firstPtsUs = -1;
            startedAtMs = System.currentTimeMillis();
            lock.notifyAll();
        }
        Log.i(TAG, "audio jitter flushed");
    }

    void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
        socket = null;
        try {
            if (recvThread != null) {
                recvThread.join(400);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            if (playThread != null) {
                playThread.join(400);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        recvThread = null;
        playThread = null;
        synchronized (lock) {
            jitter.clear();
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            try {
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
        decoder = null;
    }

    private void recvLoop() {
        byte[] buf = new byte[2048];
        try (DatagramSocket sock = new DatagramSocket(null)) {
            sock.setReuseAddress(true);
            sock.setSoTimeout(250);
            sock.bind(new InetSocketAddress(PORT));
            socket = sock;
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    sock.receive(packet);
                } catch (java.net.SocketTimeoutException te) {
                    continue;
                }
                int len = packet.getLength();
                if (len < 18) {
                    continue;
                }
                int off = packet.getOffset();
                int magic = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
                if (magic != MAGIC || (buf[off + 2] & 0xFF) != 1) {
                    continue;
                }
                long seq = ((long) (buf[off + 4] & 0xFF) << 24)
                        | ((long) (buf[off + 5] & 0xFF) << 16)
                        | ((long) (buf[off + 6] & 0xFF) << 8)
                        | (buf[off + 7] & 0xFF);
                long pts = 0;
                for (int i = 0; i < 8; i++) {
                    pts = (pts << 8) | (buf[off + 8 + i] & 0xFF);
                }
                int opusLen = ((buf[off + 16] & 0xFF) << 8) | (buf[off + 17] & 0xFF);
                if (opusLen <= 0 || 18 + opusLen > len) {
                    continue;
                }
                byte[] opus = new byte[opusLen];
                System.arraycopy(buf, off + 18, opus, 0, opusLen);
                long n = ++packetsRecv;
                if (n == 1 || n % 50 == 0) {
                    Log.i(TAG, "recv packet #" + n + " seq=" + seq + " opus=" + opusLen
                            + " from " + packet.getAddress());
                }
                if (firstPtsUs < 0) {
                    firstPtsUs = pts;
                    startedAtMs = System.currentTimeMillis();
                }
                synchronized (lock) {
                    if (nextSeq >= 0 && seq < nextSeq) {
                        continue; // late
                    }
                    jitter.put(seq, opus);
                    while (jitter.size() > MAX_DEPTH) {
                        jitter.pollFirstEntry();
                    }
                    lock.notifyAll();
                }
            }
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "audio recv ended", e);
            }
        }
    }

    private void playLoop() {
        short[] pcm = new short[FRAME_SAMPLES * CHANNELS];
        byte[] pcmBytes = new byte[pcm.length * 2];
        // Wait for initial depth before starting playout.
        long waitStart = System.currentTimeMillis();
        while (running) {
            synchronized (lock) {
                if (jitter.size() >= TARGET_DEPTH) {
                    if (nextSeq < 0) {
                        nextSeq = jitter.firstKey();
                    }
                    Log.i(TAG, "playout start · depth=" + jitter.size() + " nextSeq=" + nextSeq);
                    break;
                }
                if (System.currentTimeMillis() - waitStart > 3000) {
                    Log.w(TAG, "still waiting for Opus packets · recv=" + packetsRecv
                            + " depth=" + jitter.size());
                    waitStart = System.currentTimeMillis();
                }
                try {
                    lock.wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        while (running && !Thread.currentThread().isInterrupted()) {
            // Fixed delay vs first packet PTS (~align to video glass-to-glass).
            if (firstPtsUs >= 0) {
                long elapsedUs = (System.currentTimeMillis() - startedAtMs) * 1000L;
                // Soft pacing — don't busy-spin before playout delay.
                if (elapsedUs < PLAYOUT_DELAY_MS * 1000L) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }

            byte[] opus = null;
            boolean gap = false;
            synchronized (lock) {
                while (running && jitter.isEmpty()) {
                    try {
                        lock.wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    break;
                }
                if (nextSeq < 0 && !jitter.isEmpty()) {
                    nextSeq = jitter.firstKey();
                }
                if (jitter.containsKey(nextSeq)) {
                    opus = jitter.remove(nextSeq);
                } else if (!jitter.isEmpty() && jitter.firstKey() > nextSeq) {
                    // Missing packet — PLC, then catch up if backlog grows.
                    gap = true;
                    if (jitter.size() >= MAX_DEPTH) {
                        nextSeq = jitter.firstKey();
                        opus = jitter.remove(nextSeq);
                        gap = false;
                    }
                } else if (!jitter.isEmpty() && jitter.firstKey() < nextSeq) {
                    // Drop ancient packets.
                    while (!jitter.isEmpty() && jitter.firstKey() < nextSeq) {
                        jitter.pollFirstEntry();
                    }
                    continue;
                }
            }

            int samples;
            try {
                if (gap || opus == null) {
                    samples = decoder.decode(null, 0, 0, pcm, 0, FRAME_SAMPLES, false);
                } else {
                    samples = decoder.decode(opus, 0, opus.length, pcm, 0, FRAME_SAMPLES, false);
                }
            } catch (OpusException e) {
                Log.w(TAG, "decode failed", e);
                nextSeq++;
                continue;
            }
            if (samples > 0 && track != null) {
                int shorts = samples * CHANNELS;
                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, shorts);
                int peak = 0;
                for (int i = 0; i < shorts; i++) {
                    int v = Math.abs((int) pcm[i]);
                    if (v > peak) {
                        peak = v;
                    }
                }
                if (peak > peakSinceLog) {
                    peakSinceLog = peak;
                }
                try {
                    track.write(pcmBytes, 0, shorts * 2);
                } catch (Exception e) {
                    Log.w(TAG, "AudioTrack write failed", e);
                }
                if (++packetsPlayed % 300 == 0) {
                    String state;
                    try {
                        int ps = track.getPlayState();
                        state = ps == AudioTrack.PLAYSTATE_PLAYING ? "playing"
                                : ps == AudioTrack.PLAYSTATE_PAUSED ? "paused" : "stopped";
                    } catch (Exception e) {
                        state = "unknown";
                    }
                    Log.i(TAG, "playout played=" + packetsPlayed + " peak=" + peakSinceLog
                            + " track=" + state);
                    peakSinceLog = 0;
                }
            }
            nextSeq++;
        }
    }
}
