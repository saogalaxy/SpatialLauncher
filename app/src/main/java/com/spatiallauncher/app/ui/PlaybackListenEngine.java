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
 * Capture another app's playback mix (MediaProjection) → SenseVoice STT.
 * 3D should stay off while this runs.
 */
final class PlaybackListenEngine {
    private static final String TAG = "PlaybackListen";
    private static final String MODEL_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17";
    private static final String MODEL_FILE = "model.int8.onnx";
    private static final int SAMPLE_RATE = 16000;
    private static final float SPEECH_RMS = 0.012f;
    /** Original smooth defaults (slider = 100). */
    private static final long SMOOTH_MAX_UTTERANCE_MS = 8000;
    private static final long SMOOTH_SILENCE_FLUSH_MS = 700;
    /** Fastest slider end (0): smaller chunks, less lag, choppier. */
    private static final long FAST_MAX_UTTERANCE_MS = 2500;
    private static final long FAST_SILENCE_FLUSH_MS = 350;

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

    /**
     * @param smoothnessPercent 0 = fast/small chunks, 100 = smooth (original). Live while Listen runs.
     */
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
                captureLoop();
            } catch (Throwable t) {
                Log.w(TAG, "Listen failed", t);
                status("Listen failed: " + t.getMessage());
            } finally {
                stopInternal();
            }
        }, "ListenAsr");
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

    private void captureLoop() {
        AudioRecord rec = recorder;
        int min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int hop = Math.max(min, SAMPLE_RATE / 10);
        short[] buf = new short[hop];
        ArrayList<Float> utterance = new ArrayList<>();
        long speechStart = 0;
        long lastSpeech = 0;
        boolean inSpeech = false;
        long lastRmsLog = 0;
        while (running.get()) {
            int n = rec.read(buf, 0, buf.length);
            if (n <= 0) {
                continue;
            }
            float rms = 0f;
            for (int i = 0; i < n; i++) {
                float s = buf[i] / 32768f;
                rms += s * s;
                if (inSpeech) {
                    utterance.add(s);
                }
            }
            rms = (float) Math.sqrt(rms / Math.max(1, n));
            long now = System.currentTimeMillis();
            if (now - lastRmsLog >= 2000L) {
                lastRmsLog = now;
                Log.i(TAG, "audio rms=" + String.format(java.util.Locale.US, "%.4f", rms)
                        + " thresh=" + SPEECH_RMS + (rms >= SPEECH_RMS ? " SPEECH" : " quiet"));
            }
            if (rms >= SPEECH_RMS) {
                if (!inSpeech) {
                    inSpeech = true;
                    speechStart = now;
                    utterance.clear();
                    for (int i = 0; i < n; i++) {
                        utterance.add(buf[i] / 32768f);
                    }
                }
                lastSpeech = now;
            }
            boolean tooLong = inSpeech && now - speechStart >= maxUtteranceMs;
            boolean silence = inSpeech && now - lastSpeech >= silenceFlushMs;
            if (inSpeech && (tooLong || silence) && utterance.size() > minUtteranceSamples) {
                float[] samples = new float[utterance.size()];
                for (int i = 0; i < utterance.size(); i++) {
                    samples[i] = utterance.get(i);
                }
                utterance.clear();
                inSpeech = false;
                decodeUtterance(samples);
            }
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
        File dir = new File(modelsRoot, MODEL_DIR);
        String model = new File(dir, MODEL_FILE).getAbsolutePath();
        String tokens = new File(dir, "tokens.txt").getAbsolutePath();
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
            // Capture MEDIA/GAME from the projected session — do NOT match our own UID
            // (that only hears SpatialLauncher / Piper silence). Exclude ourselves so
            // spoken translations don't get re-captured into the STT loop.
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
        File onnx = new File(dir, MODEL_FILE);
        File tokens = new File(dir, "tokens.txt");
        if (onnx.isFile() && tokens.isFile()) {
            return;
        }
        BundledArchive.extractTarBz2(
                app, "models/asr/" + MODEL_DIR + ".tar.bz2", modelsRoot);
        if (!onnx.isFile() || !tokens.isFile()) {
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
