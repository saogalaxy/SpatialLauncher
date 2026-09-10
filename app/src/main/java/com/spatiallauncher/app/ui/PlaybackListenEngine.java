package com.spatiallauncher.app.ui;

import android.content.Context;
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
    private static final long MAX_UTTERANCE_MS = 8000;
    private static final long SILENCE_FLUSH_MS = 700;

    interface Listener {
        void onTranscript(String text);

        void onStatus(String message);
    }

    private final Context app;
    private final File modelsRoot;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final OnDeviceTranslator translator;
    private volatile OfflineRecognizer recognizer;
    private volatile AudioRecord recorder;
    private volatile Thread captureThread;
    private volatile Listener listener;

    PlaybackListenEngine(Context context) {
        app = context.getApplicationContext();
        modelsRoot = new File(app.getFilesDir(), "asr");
        translator = OnDeviceTranslator.get(app);
    }

    void setListener(Listener listener) {
        this.listener = listener;
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
        translator.ensureReady(ok -> { });
        status("Unpacking listen model…");
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
            boolean tooLong = inSpeech && now - speechStart >= MAX_UTTERANCE_MS;
            boolean silence = inSpeech && now - lastSpeech >= SILENCE_FLUSH_MS;
            if (inSpeech && (tooLong || silence) && utterance.size() > SAMPLE_RATE / 4) {
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
            translator.toEnglish(raw, en -> {
                Listener sink = listener;
                if (sink != null) {
                    main.post(() -> sink.onTranscript(en));
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
        try {
            AudioPlaybackCaptureConfiguration cap =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .addMatchingUid(Process.myUid())
                            .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            return new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min, SAMPLE_RATE))
                    .setAudioPlaybackCaptureConfig(cap)
                    .build();
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
            throw new IllegalStateException("SenseVoice files missing from APK");
        }
    }

    private void status(String message) {
        Listener sink = listener;
        if (sink != null) {
            main.post(() -> sink.onStatus(message));
        }
    }
}
