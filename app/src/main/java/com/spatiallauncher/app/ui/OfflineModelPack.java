package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.ConnectivityManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Copies bundled models to app files once. Inference engines stay off until a mode
 * actually needs them (Translate = OPUS-MT, TTS = Piper, Listen = SenseVoice).
 * SenseVoice is too large for a single APK (AGP packageDebug integer overflow
 * past ~2GB), so it downloads on first use when not present in assets.
 */
final class OfflineModelPack {
    private static final String TAG = "OfflineModelPack";
    private static final Object LOCK = new Object();
    private static volatile boolean unpacked;

    private static final String ASR_ARCHIVE =
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2";
    private static final String ASR_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" + ASR_ARCHIVE;
    private static final long ASR_MIN_BYTES = 100_000_000L;

    /** One-time consent for the ~1 GB SenseVoice fetch (Listen). */
    private static final String CONSENT_PREFS = "model_pack";
    private static final String KEY_DL_CONSENT = "dl_consent";
    static final int CONSENT_UNKNOWN = 0;
    static final int CONSENT_ALLOWED = 1;
    static final int CONSENT_LATER = 2;

    /** SenseVoice readiness for Listen. */
    static final int ASR_READY = 0;
    static final int ASR_FETCHING = 1;
    static final int ASR_NEED_CONSENT = 2;
    static final int ASR_NEED_WIFI = 3;
    static final int ASR_READY_TO_FETCH = 4;

    private static volatile boolean asrFetching;

    /** Progress callback for large first-use downloads (worker thread). */
    interface FetchProgress {
        void onProgress(long downloadedBytes);
    }

    static int getModelConsent(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE);
            return prefs.getInt(KEY_DL_CONSENT, CONSENT_UNKNOWN);
        } catch (Throwable t) {
            return CONSENT_UNKNOWN;
        }
    }

    static void setModelConsent(Context context, int consent) {
        try {
            context.getApplicationContext()
                    .getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_DL_CONSENT, consent).apply();
        } catch (Throwable ignored) {
        }
    }

    /** True when the active network is unmetered (Wi-Fi); false also covers offline. */
    static boolean isUnmetered(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            return !cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return true;
        }
    }

    static final String ASR_MODEL_DIR =
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17";

    static File senseVoiceDir(Context context) {
        return new File(
                new File(context.getApplicationContext().getFilesDir(), "asr"), ASR_MODEL_DIR);
    }

    /**
     * Upstream archives renamed model.int8.onnx → model.onnx; accept either, but
     * require plausible completeness — a truncated download must never count as
     * ready (ORT aborts the whole process on a corrupt model). Current upstream
     * sizes: int8 ≈ 239 MB, fp32 ≈ 938 MB.
     */
    static final long ASR_INT8_MIN_BYTES = 200_000_000L;
    static final long ASR_FP32_MIN_BYTES = 800_000_000L;

    static File senseVoiceModelFile(Context context) {
        File dir = senseVoiceDir(context);
        File int8 = new File(dir, "model.int8.onnx");
        if (int8.isFile() && int8.length() >= ASR_INT8_MIN_BYTES) {
            return int8;
        }
        File plain = new File(dir, "model.onnx");
        if (plain.isFile() && plain.length() >= ASR_FP32_MIN_BYTES) {
            return plain;
        }
        return null;
    }

    static boolean isAsrReady(Context context) {
        return senseVoiceModelFile(context) != null
                && new File(senseVoiceDir(context), "tokens.txt").isFile();
    }

    /** Only the int8 weights + tokens are needed; skip the ~900 MB fp32 + test wavs. */
    private static boolean isAsrWantedEntry(String entryName) {
        return entryName.endsWith("/model.int8.onnx")
                || entryName.endsWith("/tokens.txt");
    }

    private static void deleteAsrFiles(Context context) {
        try {
            File dir = senseVoiceDir(context);
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static boolean isAsrFetching() {
        return asrFetching;
    }

    static int asrState(Context context) {
        if (isAsrReady(context)) {
            return ASR_READY;
        }
        if (asrFetching) {
            return ASR_FETCHING;
        }
        if (getModelConsent(context) != CONSENT_ALLOWED) {
            return ASR_NEED_CONSENT;
        }
        if (!isUnmetered(context)) {
            return ASR_NEED_WIFI;
        }
        return ASR_READY_TO_FETCH;
    }

    /** Kick off the SenseVoice fetch on a worker thread (no-op if ready/fetching). */
    static void startAsrFetch(Context context, FetchProgress progress) {
        synchronized (LOCK) {
            if (asrFetching || isAsrReady(context)) {
                return;
            }
            asrFetching = true;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                unpackAsr(app, progress);
            } catch (Throwable t) {
                Log.w(TAG, "asr fetch failed", t);
            } finally {
                synchronized (LOCK) {
                    asrFetching = false;
                }
            }
        }, "AsrFetch").start();
    }

    static void unpackAll(Context context) {
        if (unpacked) {
            return;
        }
        synchronized (LOCK) {
            if (unpacked) {
                return;
            }
            Context app = context.getApplicationContext();
            try {
                copyTranslatePair(app, "jaen");
                copyTranslatePair(app, "zhen");
                copyTranslatePair(app, "koen");
                unpacked = true;
                Log.i(TAG, "ja/zh/ko OPUS on disk");
            } catch (Throwable t) {
                Log.w(TAG, "unpack failed", t);
            }
        }
    }

    static void unpackSpeech(Context context) {
        unpackSpeech(context, null);
    }

    static void unpackSpeech(Context context, FetchProgress progress) {
        Context app = context.getApplicationContext();
        try {
            PiperTtsEngine.get(app).unpackVoiceArchives();
            unpackAsr(app, progress);
        } catch (Throwable t) {
            Log.w(TAG, "speech unpack failed", t);
        }
    }

    /**
     * Startup-safe speech unpack: Piper voices only. SenseVoice (~1 GB) is
     * strictly on-demand — it prompts and fetches only when the user taps the
     * Listen icon (see PanelMainActivity.ensureAsrForListen), never at startup.
     */
    static void unpackPiperOnly(Context context) {
        Context app = context.getApplicationContext();
        try {
            PiperTtsEngine.get(app).unpackVoiceArchives();
        } catch (Throwable t) {
            Log.w(TAG, "piper unpack failed", t);
        }
    }

    static File translateDir(Context context, String pairId) {
        return new File(context.getFilesDir(), "translate/" + pairId);
    }

    static boolean translatePairReady(Context context, String pairId) {
        File dir = translateDir(context, pairId);
        File enc = new File(dir, "encoder.onnx");
        File dec = new File(dir, "decoder.onnx");
        File pieces = new File(dir, "pieces.tsv");
        File config = new File(dir, "config.json");
        return enc.isFile() && enc.length() > 1_000_000
                && dec.isFile() && dec.length() > 1_000_000
                && pieces.isFile() && pieces.length() > 64
                && config.isFile() && config.length() > 64;
    }

    /**
     * Unpack ja/zh/ko→en from APK assets (same pack for store + sideload installer).
     * No network — models are fetched at Gradle build time into assets.
     */
    static File ensureTranslatePair(Context context, String pairId) throws Exception {
        if (translatePairReady(context, pairId)) {
            return translateDir(context, pairId);
        }
        try {
            copyTranslatePair(context, pairId);
        } catch (Throwable ignored) {
        }
        if (translatePairReady(context, pairId)) {
            return translateDir(context, pairId);
        }
        throw new IllegalStateException(
                "bundled OPUS-MT pair missing from APK: " + pairId
                        + " (rebuild so downloadOfflineModels packs jaen/zhen/koen)");
    }

    static String pickCaptionPair(String text) {
        if (OnDeviceTranslator.containsHangul(text)) {
            return "koen";
        }
        if (OnDeviceTranslator.containsKana(text)) {
            return "jaen";
        }
        if (OnDeviceTranslator.containsCjkIdeograph(text)) {
            return "zhen";
        }
        return "jaen";
    }

    private static void copyTranslatePair(Context context, String pairId) throws Exception {
        if (translatePairReady(context, pairId)) {
            return;
        }
        File dest = translateDir(context, pairId);
        dest.mkdirs();
        AssetManager assets = context.getAssets();
        String prefix = "translate/" + pairId + "/";
        String[] files = {"encoder.onnx", "decoder.onnx", "pieces.tsv", "config.json"};
        for (String name : files) {
            File out = new File(dest, name);
            long min = name.endsWith(".onnx") ? 1_000_000L : 64L;
            if (out.isFile() && out.length() > min) {
                continue;
            }
            Log.i(TAG, "copy " + prefix + name);
            try (InputStream in = assets.open(prefix + name);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    fos.write(buf, 0, n);
                }
            }
        }
    }

    static File extractMicrosoftOrt(Context context) throws Exception {
        File out = new File(context.getFilesDir(), "native/ms_onnxruntime.so");
        if (out.isFile() && out.length() > 1_000_000) {
            return out;
        }
        out.getParentFile().mkdirs();
        try (InputStream in = context.getAssets().open("native/ms_onnxruntime.so");
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
            }
        }
        return out;
    }

    private static void unpackAsr(Context context) throws Exception {
        unpackAsr(context, null);
    }

    private static void unpackAsr(Context context, FetchProgress progress) throws Exception {
        File root = new File(context.getFilesDir(), "asr");
        File dir = new File(root, ASR_MODEL_DIR);
        File tokens = new File(dir, "tokens.txt");
        if (senseVoiceModelFile(context) != null && tokens.isFile()) {
            return;
        }
        try {
            BundledArchive.extractTarBz2Selective(
                    context,
                    "models/asr/" + ASR_ARCHIVE,
                    root,
                    OfflineModelPack::isAsrWantedEntry);
        } catch (Throwable t) {
            Log.i(TAG, "SenseVoice not in APK — downloading (" + t.getMessage() + ")");
            File archive = new File(context.getFilesDir(), "asr-download/" + ASR_ARCHIVE);
            downloadUrl(ASR_URL, archive, ASR_MIN_BYTES, progress);
            BundledArchive.extractTarBz2FileSelective(
                    archive, root, OfflineModelPack::isAsrWantedEntry);
        }
        if (senseVoiceModelFile(context) == null || !tokens.isFile()) {
            // Never leave partial weights behind: a truncated model passes
            // existence checks and then aborts the process inside ORT.
            deleteAsrFiles(context);
            throw new IllegalStateException(
                    "SenseVoice ASR missing after unpack/download. Need Wi‑Fi for first Listen.");
        }
    }

    private static void downloadUrl(String url, File dest, long minBytes) throws Exception {
        downloadUrl(url, dest, minBytes, null);
    }

    private static void downloadUrl(String url, File dest, long minBytes,
            FetchProgress progress) throws Exception {
        if (dest.isFile() && dest.length() >= minBytes) {
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        File tmp = new File(parent, dest.getName() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(60_000);
        conn.setReadTimeout(600_000);
        conn.setRequestProperty("User-Agent", "SpatialLauncher-headset");
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            long got = 0;
            long lastReportMb = -1;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                got += n;
                long mb = got / (1024 * 1024);
                if (got % (256L * 1024 * 1024) < n) {
                    Log.i(TAG, "download " + dest.getName() + ": " + mb + " MB");
                }
                if (progress != null && mb != lastReportMb && mb % 64 == 0) {
                    lastReportMb = mb;
                    progress.onProgress(got);
                }
            }
            if (progress != null) {
                progress.onProgress(got);
            }
        } finally {
            conn.disconnect();
        }
        if (dest.exists()) {
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("could not finalize download: " + dest.getName());
        }
        if (dest.length() < minBytes) {
            dest.delete();
            throw new IllegalStateException("download too small: " + dest.getName());
        }
    }

    private OfflineModelPack() {
    }
}
