package com.spatiallauncher.app.ui;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Playback audio → SenseVoice STT. Two sources:
 * <ul>
 *   <li>Cast: MediaProjection {@link AudioPlaybackCaptureConfiguration}</li>
 *   <li>Local video: ExoPlayer PCM via {@link PcmSource} (TeeAudioProcessor)</li>
 * </ul>
 * 3D should stay off while this runs.
 */
final class PlaybackListenEngine {
    private static final String TAG = "PlaybackListen";
    private static final String MODEL_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17";
    private static final int SAMPLE_RATE = 16000;
    private static final float SPEECH_RMS = 0.012f;
    private static final long SMOOTH_MAX_UTTERANCE_MS = 8000;
    private static final long SMOOTH_SILENCE_FLUSH_MS = 700;
    private static final long FAST_MAX_UTTERANCE_MS = 2500;
    private static final long FAST_SILENCE_FLUSH_MS = 350;

    /** 16 kHz mono float PCM for local-video Listen. */
    interface PcmSource {
        int read(float[] out, int offset, int length);
    }

    interface Listener {
        void onTranscript(String text);

        void onStatus(String message);
    }

    private final Context app;
    private final File modelsRoot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ListenMtTranslator listenMt;
    private volatile OfflineRecognizer recognizer;
    private volatile AudioRecord recorder;
    private volatile PcmSource pcmSource;
    private volatile Thread captureThread;
    private volatile Listener listener;
    private volatile long maxUtteranceMs = SMOOTH_MAX_UTTERANCE_MS;
    private volatile long silenceFlushMs = SMOOTH_SILENCE_FLUSH_MS;
    private volatile int minUtteranceSamples = SAMPLE_RATE / 4;

    PlaybackListenEngine(Context context) {
        app = context.getApplicationContext();
        modelsRoot = new File(app.getFilesDir(), "asr");
        listenMt = ListenMtTranslator.get(app);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setSmoothnessPercent(int smoothnessPercent) {
        float t = Math.max(0, Math.min(100, smoothnessPercent)) / 100f;
        maxUtteranceMs = Math.round(FAST_MAX_UTTERANCE_MS
                + t * (SMOOTH_MAX_UTTERANCE_MS - FAST_MAX_UTTERANCE_MS));
        silenceFlushMs = Math.round(FAST_SILENCE_FLUSH_MS
                + t * (SMOOTH_SILENCE_FLUSH_MS - FAST_SILENCE_FLUSH_MS));
        minUtteranceSamples = t >= 0.5f ? SAMPLE_RATE / 4 : SAMPLE_RATE / 5;
    }

    boolean isRunning() {
        return running.get();
    }

    void start(MediaProjection projection) {
        if (projection == null) {
            status("Listen needs a cast so it can hear the other app");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        pcmSource = null;
        status("Starting listen…");
        captureThread = new Thread(() -> {
            try {
                ensureModel();
                recognizer = buildRecognizer();
                recorder = buildRecorder(projection);
                if (recorder == null) {
                    status("Could not capture app audio");
                    running.set(false);
                    return;
                }
                recorder.startRecording();
                status("Listening…");
                captureLoopMic();
            } catch (Throwable t) {
                Log.w(TAG, "Listen failed", t);
                status("Listen failed: " + t.getMessage());
            } finally {
                stopInternal();
            }
        }, "ListenAsr");
        captureThread.start();
    }

    void startFromPcm(PcmSource source) {
        if (source == null) {
            status("Listen needs a playing video");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        pcmSource = source;
        recorder = null;
        status("Starting listen…");
        captureThread = new Thread(() -> {
            try {
                ensureModel();
                recognizer = buildRecognizer();
                status("Listening to video…");
                captureLoopPcm();
            } catch (Throwable t) {
                Log.w(TAG, "Listen PCM failed", t);
                status("Listen failed: " + t.getMessage());
            } finally {
                stopInternal();
            }
        }, "ListenAsrPcm");
        captureThread.start();
    }

    void stop() {
        running.set(false);
        AudioRecord rec = recorder;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Throwable ignored) {
            }
        }
        Thread t = captureThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void stopInternal() {
        running.set(false);
        pcmSource = null;
        AudioRecord rec = recorder;
        recorder = null;
        if (rec != null) {
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        OfflineRecognizer asr = recognizer;
        recognizer = null;
        if (asr != null) {
            try {
                asr.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void captureLoopMic() {
        AudioRecord rec = recorder;
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int hop = Math.max(min, SAMPLE_RATE / 10);
        short[] buf = new short[hop];
        UtteranceState state = new UtteranceState();
        while (running.get()) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) {
                continue;
            }
            float[] chunk = new float[n];
            for (int i = 0; i < n; i++) {
                chunk[i] = buf[i] / 32768f;
            }
            feedUtterance(chunk, n, state);
        }
    }

    private void captureLoopPcm() {
        PcmSource source = pcmSource;
        float[] buf = new float[SAMPLE_RATE / 10];
        UtteranceState state = new UtteranceState();
        while (running.get()) {
            if (source == null) {
                break;
            }
            int n = source.read(buf, 0, buf.length);
            if (n <= 0) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            feedUtterance(buf, n, state);
        }
    }

    private static final class UtteranceState {
        final ArrayList<Float> utterance = new ArrayList<>();
        long speechStart;
        long lastSpeech;
        boolean inSpeech;
        long lastRmsLog;
    }

    private void feedUtterance(float[] chunk, int n, UtteranceState state) {
        float rms = 0f;
        for (int i = 0; i < n; i++) {
            float s = chunk[i];
            rms += s * s;
            if (state.inSpeech) {
                state.utterance.add(s);
            }
        }
        rms = (float) Math.sqrt(rms / Math.max(1, n));
        long now = System.currentTimeMillis();
        if (now - state.lastRmsLog >= 2000L) {
            state.lastRmsLog = now;
            Log.i(TAG, "audio rms=" + String.format(java.util.Locale.US, "%.4f", rms)
                    + " thresh=" + SPEECH_RMS + (rms >= SPEECH_RMS ? " SPEECH" : " quiet"));
        }
        if (rms >= SPEECH_RMS) {
            if (!state.inSpeech) {
                state.inSpeech = true;
                state.speechStart = now;
                state.utterance.clear();
                for (int i = 0; i < n; i++) {
                    state.utterance.add(chunk[i]);
                }
            }
            state.lastSpeech = now;
        }
        boolean tooLong = state.inSpeech && now - state.speechStart >= maxUtteranceMs;
        boolean silence = state.inSpeech && now - state.lastSpeech >= silenceFlushMs;
        if (state.inSpeech && (tooLong || silence)
                && state.utterance.size() > minUtteranceSamples) {
            float[] samples = new float[state.utterance.size()];
            for (int i = 0; i < state.utterance.size(); i++) {
                samples[i] = state.utterance.get(i);
            }
            state.utterance.clear();
            state.inSpeech = false;
            decodeUtterance(samples);
        }
    }

    private void decodeUtterance(float[] samples) {
        OfflineRecognizer asr = recognizer;
        if (asr == null) {
            return;
        }
        OfflineStream stream = null;
        try {
            stream = asr.createStream();
            stream.acceptWaveform(samples, SAMPLE_RATE);
            asr.decode(stream);
            String text = asr.getResult(stream).getText();
            if (text == null) {
                return;
            }
            text = text.trim();
            if (text.isEmpty()) {
                return;
            }
            Log.i(TAG, "ASR: " + text);
            final String raw = text;
            listenMt.toEnglish(raw, new UserSettingsStore(app).getUseOpusTranslate(), en -> {
                Listener sink = listener;
                if (sink != null) {
                    sink.onTranscript(en);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "decode failed", t);
        } finally {
            if (stream != null) {
                try {
                    stream.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private OfflineRecognizer buildRecognizer() {
        File modelFile = OfflineModelPack.senseVoiceModelFile(app);
        if (modelFile == null) {
            throw new IllegalStateException("speech model file missing");
        }
        String model = modelFile.getAbsolutePath();
        String tokens = new File(modelFile.getParentFile(), "tokens.txt").getAbsolutePath();
        OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
        senseVoice.setModel(model);
        senseVoice.setUseInverseTextNormalization(true);
        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setSenseVoice(senseVoice);
        modelConfig.setTokens(tokens);
        modelConfig.setNumThreads(2);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(modelConfig);
        config.setDecodingMethod("greedy_search");
        return new OfflineRecognizer(null, config);
    }

    private AudioRecord buildRecorder(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; skipping playback capture");
            return null;
        }
        try {
            AudioPlaybackCaptureConfiguration.Builder capBuilder =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .excludeUid(Process.myUid());
            AudioPlaybackCaptureConfiguration cap = capBuilder.build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min, SAMPLE_RATE))
                    .setAudioPlaybackCaptureConfig(cap)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord not initialized");
                record.release();
                return null;
            }
            Log.i(TAG, "Playback capture ready (exclude self uid=" + Process.myUid() + ")");
            return record;
        } catch (Throwable t) {
            Log.w(TAG, "AudioRecord playback capture failed", t);
            return null;
        }
    }

    private void ensureModel() throws Exception {
        File dir = new File(modelsRoot, MODEL_DIR);
        File tokens = new File(dir, "tokens.txt");
        if (OfflineModelPack.senseVoiceModelFile(app) != null && tokens.isFile()) {
            return;
        }
        BundledArchive.extractTarBz2(
                app, "models/asr/" + MODEL_DIR + ".tar.bz2", modelsRoot);
        if (OfflineModelPack.senseVoiceModelFile(app) == null || !tokens.isFile()) {
            int state = OfflineModelPack.asrState(app);
            if (state == OfflineModelPack.ASR_FETCHING) {
                throw new IllegalStateException(
                        "speech models still downloading — try again shortly");
            }
            if (state == OfflineModelPack.ASR_NEED_WIFI) {
                throw new IllegalStateException(
                        "connect to Wi-Fi to download speech models (~1 GB)");
            }
            throw new IllegalStateException(
                    "Listen needs a one-time speech download (~1 GB)");
        }
    }

    private void status(String message) {
        Listener sink = listener;
        if (sink != null) {
            main.post(() -> sink.onStatus(message));
        }
    }
}
