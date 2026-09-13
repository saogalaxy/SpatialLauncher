package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import com.spatiallauncher.app.R;
import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * On-device Piper (VITS) via sherpa-onnx. Horizon OS has no working system TTS engine.
 * Lines are queued FIFO so fast captions finish speaking before the next starts.
 */
final class PiperTtsEngine {
    private static final String TAG = "PiperTts";
    private static final String FEMALE_DIR = "vits-piper-en_US-amy-low";
    private static final String FEMALE_ONNX = "en_US-amy-low.onnx";
    private static final String MALE_DIR = "vits-piper-en_US-ryan-low";
    private static final String MALE_ONNX = "en_US-ryan-low.onnx";
    /** Cap backlog so live captions can catch up; page Translate needs more headroom. */
    private static final int MAX_QUEUE = 256;

    private final Context app;
    private final File modelsRoot;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Synthesize next line while current audio plays (OfflineTts is locked). */
    private final ExecutorService prefetch = Executors.newSingleThreadExecutor();
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean preparing = new AtomicBoolean(false);
    private final Object gate = new Object();
    private final Object synthLock = new Object();
    private final ArrayDeque<String> lineQueue = new ArrayDeque<>();
    private volatile OfflineTts tts;
    private volatile String lastSpokenNormalized = "";
    private volatile String activeNormalized = "";
    private volatile AudioTrack currentTrack;
    private volatile boolean pumping;
    private volatile boolean cancelPlayback;
    private volatile boolean paused;
    private volatile boolean replayQueued;
    private volatile PlaybackListener playbackListener;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    interface PlaybackListener {
        void onSpeakingChanged(boolean speaking);

        void onUtteranceProgress(String text, int highlightStart, int highlightEnd);

        default void onPaused(boolean paused) {
        }
    }

    void setPlaybackListener(PlaybackListener listener) {
        playbackListener = listener;
    }

    boolean isSpeaking() {
        return pumping || currentTrack != null;
    }

    int queuedCount() {
        synchronized (gate) {
            return lineQueue.size();
        }
    }

    /**
     * Speak one line and block until it finishes (and the queue is idle).
     * Used by page Translate-and-read so each JP line is heard before the next.
     */
    boolean speakAndWait(String text, long timeoutMs) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        ensureReadyAsync();
        long deadline = SystemClock.elapsedRealtime() + Math.max(1_000L, timeoutMs);
        // Wait until Piper finished loading so the pump actually runs.
        while (!isReady() && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!isReady()) {
            Log.w(TAG, "speakAndWait: Piper not ready");
            return false;
        }
        if (!waitUntilIdle(deadline)) {
            return false;
        }
        if (!speak(text.trim(), true)) {
            return false;
        }
        try {
            Thread.sleep(40);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return waitUntilIdle(deadline);
    }

    private boolean waitUntilIdle(long deadlineMs) {
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            synchronized (gate) {
                if (!pumping && currentTrack == null && lineQueue.isEmpty()) {
                    return true;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    boolean isPaused() {
        return paused;
    }
    /** 1.0 = normal Piper pace; higher = faster. */
    private volatile float speechRate = 1.0f;
    /** 0.4 calm … 0.95 brighter (Piper noiseScale). */
    private volatile float toneNoise = 0.667f;
    private volatile boolean maleVoice = false;
    private volatile String loadedVoiceKey = "";
    private static PiperTtsEngine instance;

    static synchronized PiperTtsEngine get(Context context) {
        if (instance == null) {
            instance = new PiperTtsEngine(context.getApplicationContext());
        }
        return instance;
    }

    private PiperTtsEngine(Context context) {
        app = context.getApplicationContext();
        modelsRoot = new File(app.getFilesDir(), "piper");
        SharedPreferences prefs = app.getSharedPreferences("spatial_launcher_settings", Context.MODE_PRIVATE);
        maleVoice = prefs.getBoolean("tts_male_voice", false);
        int tone = Math.max(0, Math.min(100, prefs.getInt("tts_tone_percent", 50)));
        toneNoise = 0.40f + (tone / 100f) * 0.55f;
        loadSherpaNativeLibs();
    }

    void setSpeechRate(float rate) {
        speechRate = Math.max(0.5f, Math.min(2.0f, rate));
    }

    void setMaleVoice(boolean male) {
        if (maleVoice == male) {
            return;
        }
        maleVoice = male;
        if (ready.get()) {
            reloadVoice();
        }
    }

    /** 0–100, 50 = default Amy/Ryan tone. */
    void setTonePercent(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        float next = 0.40f + (p / 100f) * 0.55f;
        if (Math.abs(next - toneNoise) < 0.02f) {
            toneNoise = next;
            return;
        }
        toneNoise = next;
        if (ready.get()) {
            reloadVoice();
        }
    }

    private static void applyTone(OfflineTtsConfig config, float noise) {
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("setNoiseScale", float.class);
            m.invoke(config, noise);
        } catch (Throwable ignored) {
        }
    }

    private void reloadVoice() {
        ready.set(false);
        preparing.set(false);
        OfflineTts engine = tts;
        tts = null;
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
        }
        loadedVoiceKey = "";
        ensureReadyAsync();
    }

    private static void loadSherpaNativeLibs() {
        String[] libs = {
                "onnxruntime",
                "sherpa-onnx-c-api",
                "sherpa-onnx-cxx-api",
                "sherpa-onnx-jni"
        };
        for (String lib : libs) {
            try {
                System.loadLibrary(lib);
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "loadLibrary " + lib + " failed", e);
            }
        }
    }

    float speechRate() {
        return speechRate;
    }

    /** Disk only — does not construct OfflineTts. Female + male both ship in the APK. */
    void unpackVoiceArchives() {
        try {
            extractIfMissing(FEMALE_DIR, FEMALE_ONNX);
            extractIfMissing(MALE_DIR, MALE_ONNX);
        } catch (Throwable t) {
            Log.w(TAG, "Piper archive unpack failed", t);
        }
    }

    private void extractIfMissing(String dirName, String onnxName) throws Exception {
        File dir = new File(modelsRoot, dirName);
        File onnx = new File(dir, onnxName);
        if (onnx.isFile()) {
            return;
        }
        BundledArchive.extractTarBz2(app, "models/piper/" + dirName + ".tar.bz2", modelsRoot);
    }

    void ensureReadyAsync() {
        if (ready.get() || !preparing.compareAndSet(false, true)) {
            return;
        }
        io.execute(() -> {
            try {
                boolean male = maleVoice;
                String dirName = male ? MALE_DIR : FEMALE_DIR;
                String onnxName = male ? MALE_ONNX : FEMALE_ONNX;
                String voiceKey = dirName + "|" + toneNoise;
                File dir = new File(modelsRoot, dirName);
                File onnx = new File(dir, onnxName);
                if (!onnx.isFile()) {
                    BundledArchive.extractTarBz2(
                            app, "models/piper/" + dirName + ".tar.bz2", modelsRoot);
                }
                File tokens = new File(dir, "tokens.txt");
                File dataDir = new File(dir, "espeak-ng-data");
                if (!onnx.isFile() || !tokens.isFile() || !dataDir.isDirectory()) {
                    Log.w(TAG, "Piper model files missing after extract");
                    return;
                }
                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(onnx.getAbsolutePath());
                vits.setTokens(tokens.getAbsolutePath());
                vits.setDataDir(dataDir.getAbsolutePath());
                OfflineTtsModelConfig model = new OfflineTtsModelConfig();
                model.setVits(vits);
                model.setNumThreads(2);
                model.setDebug(false);
                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(model);
                applyTone(config, toneNoise);
                loadSherpaNativeLibs();
                tts = new OfflineTts(null, config);
                loadedVoiceKey = voiceKey;
                ready.set(true);
                Log.i(TAG, "Piper ready voice=" + dirName + " tone=" + toneNoise);
                synchronized (gate) {
                    if (!lineQueue.isEmpty() && !pumping) {
                        startPumpLocked();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Piper init failed", t);
                PanelAlerts.show(app, "TTS engine failed to start");
            } finally {
                preparing.set(false);
            }
        });
    }

    boolean isReady() {
        return ready.get() && tts != null;
    }

    /**
     * Enqueue a caption. Does not interrupt the line currently playing —
     * fast on-screen dialogue stacks and is read in order.
     */
    boolean speak(String text) {
        return speak(text, false);
    }

    boolean speak(String text, boolean force) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String line = text.trim();
        String incoming = DialogueDeduper.normalize(line);
        if (incoming.isEmpty()) {
            return true;
        }
        synchronized (gate) {
            if (!force && isDuplicateLocked(incoming)) {
                return true;
            }
            if (!force && replaceExtensionInQueueLocked(line, incoming)) {
                paused = false;
                startPumpLocked();
                ensureReadyAsync();
                return true;
            }
            if (force) {
                lastSpokenNormalized = "";
            }
            while (lineQueue.size() >= MAX_QUEUE) {
                String dropped = lineQueue.pollFirst();
                Log.i(TAG, "Queue full; dropped oldest: " + dropped);
            }
            lineQueue.addLast(line);
            Log.i(TAG, "Queued (" + lineQueue.size() + "): " + line);
            paused = false;
            startPumpLocked();
        }
        ensureReadyAsync();
        if (!isReady()) {
            Log.i(TAG, "Piper not ready yet; line held in queue");
        }
        return true;
    }

    /** Stop whatever is playing and speak this line next. */
    void interruptAndSpeak(String text) {
        java.util.ArrayList<String> one = new java.util.ArrayList<>();
        if (text != null && !text.trim().isEmpty()) {
            one.add(text.trim());
        }
        speakAll(one);
    }

    /** Replace the queue with every line (full OCR regions) and start reading. */
    void speakAll(List<String> lines) {
        synchronized (gate) {
            lineQueue.clear();
            lastSpokenNormalized = "";
            activeNormalized = "";
            paused = false;
            replayQueued = true;
            if (lines != null) {
                for (String line : lines) {
                    if (line != null && !line.trim().isEmpty()) {
                        lineQueue.addLast(line.trim());
                    }
                }
            }
            if (pumping) {
                cancelPlayback = true;
                stopCurrentTrack();
            } else {
                cancelPlayback = false;
                if (!lineQueue.isEmpty()) {
                    startPumpLocked();
                }
            }
        }
        notifyPaused(false);
        ensureReadyAsync();
    }

    boolean hasPausedTrack() {
        return paused && currentTrack != null;
    }

    /** Drop current audio but stay paused so skip/resume can pick a new line. */
    void haltPlaybackKeepPaused() {
        synchronized (gate) {
            lineQueue.clear();
            lastSpokenNormalized = "";
            activeNormalized = "";
            cancelPlayback = true;
            paused = true;
            stopCurrentTrack();
            currentTrack = null;
        }
        notifyPaused(true);
        notifySpeaking(false);
    }

    void pausePlayback() {
        if (!isSpeaking()) {
            return;
        }
        paused = true;
        AudioTrack track = currentTrack;
        if (track != null) {
            try {
                track.pause();
            } catch (Throwable ignored) {
            }
        }
        notifyPaused(true);
    }

    void resumePlayback() {
        paused = false;
        AudioTrack track = currentTrack;
        if (track != null) {
            try {
                track.play();
            } catch (Throwable ignored) {
            }
        }
        notifyPaused(false);
        synchronized (gate) {
            if (!pumping && !lineQueue.isEmpty()) {
                startPumpLocked();
            }
        }
    }

    void stopSpeaking() {
        synchronized (gate) {
            lineQueue.clear();
            lastSpokenNormalized = "";
            activeNormalized = "";
            paused = false;
            cancelPlayback = true;
            stopCurrentTrack();
        }
        notifyPaused(false);
        notifySpeaking(false);
        notifyProgress("", -1, -1);
    }

    /** Stop the current line; remaining queued lines continue. */
    void skipCurrent() {
        synchronized (gate) {
            cancelPlayback = true;
            stopCurrentTrack();
        }
    }

    private void startPumpLocked() {
        if (!pumping) {
            pumping = true;
            notifySpeaking(true);
            io.execute(this::pumpQueue);
        }
    }

    private boolean isDuplicateLocked(String incoming) {
        if (!DialogueDeduper.differsSignificantly(lastSpokenNormalized, incoming)) {
            return true;
        }
        if (!DialogueDeduper.differsSignificantly(activeNormalized, incoming)) {
            return true;
        }
        for (String queued : lineQueue) {
            if (!DialogueDeduper.differsSignificantly(DialogueDeduper.normalize(queued), incoming)) {
                return true;
            }
        }
        return false;
    }

    /**
     * If the new caption is a longer version of the last queued line (OCR grew),
     * replace that queue entry instead of speaking partial then full.
     */
    private boolean replaceExtensionInQueueLocked(String line, String incoming) {
        if (lineQueue.isEmpty()) {
            // Already speaking a shorter prefix of this line — don't stack a second copy.
            if (!activeNormalized.isEmpty()
                    && incoming.length() > activeNormalized.length() + 2
                    && incoming.startsWith(activeNormalized)) {
                Log.i(TAG, "Skip extension while speaking prefix: " + line);
                return true;
            }
            return false;
        }
        String last = lineQueue.peekLast();
        String lastNorm = DialogueDeduper.normalize(last);
        if (lastNorm.isEmpty()) {
            return false;
        }
        if (incoming.length() > lastNorm.length() + 2 && incoming.contains(lastNorm)) {
            lineQueue.removeLast();
            lineQueue.addLast(line);
            Log.i(TAG, "Replaced queued OCR growth: " + line);
            return true;
        }
        if (lastNorm.length() > incoming.length() + 2 && lastNorm.contains(incoming)) {
            // Incoming is a shorter flicker of what's already queued.
            return true;
        }
        return false;
    }

    private void pumpQueue() {
        String primedLine = null;
        String primedNorm = null;
        GeneratedAudio primedAudio = null;
        while (true) {
            String line;
            String norm;
            GeneratedAudio audio;
            if (primedAudio != null) {
                line = primedLine;
                norm = primedNorm;
                audio = primedAudio;
                primedLine = null;
                primedNorm = null;
                primedAudio = null;
            } else {
                synchronized (gate) {
                    line = lineQueue.pollFirst();
                    if (line == null || line.isEmpty()) {
                        pumping = false;
                        cancelPlayback = false;
                        replayQueued = false;
                        activeNormalized = "";
                        notifySpeaking(false);
                        notifyProgress("", -1, -1);
                        return;
                    }
                }
                norm = DialogueDeduper.normalize(line);
                if (!replayQueued
                        && !DialogueDeduper.differsSignificantly(lastSpokenNormalized, norm)) {
                    continue;
                }
                if (!isReady()) {
                    synchronized (gate) {
                        lineQueue.addFirst(line);
                        pumping = false;
                        activeNormalized = "";
                        notifySpeaking(false);
                    }
                    return;
                }
                audio = synthesize(line);
                if (audio == null) {
                    continue;
                }
            }
            if (cancelPlayback) {
                break;
            }
            if (!replayQueued
                    && !DialogueDeduper.differsSignificantly(lastSpokenNormalized, norm)) {
                continue;
            }
            synchronized (gate) {
                if (cancelPlayback) {
                    break;
                }
                activeNormalized = norm;
            }

            // Prefetch next caption while this one plays — removes the synth gap.
            final String nextLine;
            synchronized (gate) {
                nextLine = lineQueue.peekFirst();
            }
            java.util.concurrent.Future<GeneratedAudio> pending = null;
            if (nextLine != null && isReady()) {
                pending = prefetch.submit(() -> synthesize(nextLine));
            }

            try {
                Log.i(TAG, "Speaking @" + speechRate + "x: " + line);
                playPcm(line, audio.getSamples(), audio.getSampleRate());
                if (!cancelPlayback) {
                    lastSpokenNormalized = norm;
                } else {
                    if (pending != null) {
                        pending.cancel(false);
                    }
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Piper speak failed", t);
                if (pending != null) {
                    pending.cancel(false);
                }
            } finally {
                activeNormalized = "";
            }

            if (pending != null) {
                try {
                    GeneratedAudio nextAudio = pending.get();
                    synchronized (gate) {
                        String head = lineQueue.peekFirst();
                        if (nextAudio != null
                                && head != null
                                && head.equals(nextLine)
                                && !cancelPlayback) {
                            lineQueue.pollFirst();
                            primedLine = nextLine;
                            primedNorm = DialogueDeduper.normalize(nextLine);
                            primedAudio = nextAudio;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Prefetch failed", e);
                }
            }
        }
        synchronized (gate) {
            pumping = false;
            activeNormalized = "";
            cancelPlayback = false;
            if (!lineQueue.isEmpty() && !paused) {
                startPumpLocked();
            } else {
                notifySpeaking(false);
                if (!paused) {
                    notifyProgress("", -1, -1);
                }
            }
        }
    }

    private GeneratedAudio synthesize(String line) {
        OfflineTts engine = tts;
        if (engine == null || line == null) {
            return null;
        }
        try {
            synchronized (synthLock) {
                GeneratedAudio audio = engine.generate(line, 0, speechRate);
                if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                    Log.w(TAG, "Piper generated empty audio");
                    return null;
                }
                return audio;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Piper synthesize failed", t);
            return null;
        }
    }

    void shutdown() {
        stopSpeaking();
        ready.set(false);
        OfflineTts engine = tts;
        tts = null;
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Stream PCM and wait only for the hardware buffer to drain.
     * {@link AudioTrack#write} in MODE_STREAM already blocks through most of
     * playback — waiting the full clip duration again caused ~1–2s silence
     * between queued lines.
     */
    private void playPcm(String spokenLine, float[] samples, int sampleRate) {
        int min = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1f, Math.min(1f, samples[i]));
            pcm[i] = (short) (s * 32767f);
        }
        int bytes = pcm.length * 2;
        int bufferBytes = Math.max(min, Math.min(bytes, min * 8));
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.setVolume(AudioTrack.getMaxVolume());
        currentTrack = track;
        track.play();
        int[][] words = wordRanges(spokenLine);
        int lastWord = -1;
        notifyProgress(spokenLine, words.length == 0 ? 0 : words[0][0],
                words.length == 0 ? 0 : words[0][1]);
        int written = 0;
        while (written < pcm.length && !cancelPlayback) {
            while (paused && !cancelPlayback) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (cancelPlayback) {
                break;
            }
            int chunk = Math.min(pcm.length - written, Math.max(256, sampleRate / 20));
            int n = track.write(pcm, written, chunk);
            if (n <= 0) {
                break;
            }
            written += n;
            if (words.length > 0) {
                int wi = Math.min(words.length - 1,
                        (int) ((written / (float) pcm.length) * words.length));
                if (wi != lastWord) {
                    lastWord = wi;
                    notifyProgress(spokenLine, words[wi][0], words[wi][1]);
                }
            }
        }
        long drainMs = Math.max(30L, (bufferBytes / 2L) * 1000L / Math.max(1, sampleRate) + 20L);
        long start = SystemClock.elapsedRealtime();
        while (!cancelPlayback) {
            while (paused && !cancelPlayback) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (cancelPlayback || SystemClock.elapsedRealtime() - start >= drainMs) {
                break;
            }
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING
                    && track.getPlayState() != AudioTrack.PLAYSTATE_PAUSED) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (currentTrack == track) {
            currentTrack = null;
        }
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        track.release();
    }

    private void notifyPaused(boolean isPaused) {
        PlaybackListener l = playbackListener;
        if (l == null) {
            return;
        }
        mainHandler.post(() -> l.onPaused(isPaused));
    }

    private void notifySpeaking(boolean speaking) {
        PlaybackListener l = playbackListener;
        if (l == null) {
            return;
        }
        mainHandler.post(() -> l.onSpeakingChanged(speaking));
    }

    private void notifyProgress(String text, int start, int end) {
        PlaybackListener l = playbackListener;
        if (l == null) {
            return;
        }
        mainHandler.post(() -> l.onUtteranceProgress(text, start, end));
    }

    static int[][] wordRanges(String text) {
        if (text == null || text.isEmpty()) {
            return new int[0][];
        }
        java.util.ArrayList<int[]> ranges = new java.util.ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= text.length()) {
                break;
            }
            int start = i;
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            ranges.add(new int[] {start, i});
        }
        return ranges.toArray(new int[0][]);
    }

    private void stopCurrentTrack() {
        AudioTrack track = currentTrack;
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
            track.stop();
        } catch (Throwable ignored) {
        }
    }
}
