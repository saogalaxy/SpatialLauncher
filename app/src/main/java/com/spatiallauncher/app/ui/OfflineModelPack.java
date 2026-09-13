package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Copies bundled models to app files once. Inference engines stay off until a mode
 * actually needs them (Translate = OPUS-MT, TTS = Piper, Listen = SenseVoice).
 */
final class OfflineModelPack {
    private static final String TAG = "OfflineModelPack";
    private static final Object LOCK = new Object();
    private static volatile boolean unpacked;

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
                unpacked = true;
                Log.i(TAG, "ja-en OPUS on disk");
            } catch (Throwable t) {
                Log.w(TAG, "unpack failed", t);
            }
        }
    }

    static void unpackSpeech(Context context) {
        Context app = context.getApplicationContext();
        try {
            PiperTtsEngine.get(app).unpackVoiceArchives();
            unpackAsr(app);
        } catch (Throwable t) {
            Log.w(TAG, "speech unpack failed", t);
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
     * Bundled ja-en from APK, or HTTPS pull for optional zh-en / ko-en the first
     * time captions need that script.
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
        if ("jaen".equals(pairId)) {
            throw new IllegalStateException("bundled ja-en OPUS-MT is missing from the APK");
        }
        String repo = opusRepo(pairId);
        if (repo == null) {
            throw new IllegalStateException("unknown translate pair " + pairId);
        }
        String label = "zhen".equals(pairId) ? "Chinese" : "Korean";
        PanelAlerts.show(context, "Downloading " + label + " captions…");
        fetchOpusPair(context, pairId, repo);
        if (!translatePairReady(context, pairId)) {
            throw new IllegalStateException(label + " captions download failed");
        }
        PanelAlerts.show(context, label + " captions ready");
        return translateDir(context, pairId);
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

    private static String opusRepo(String pairId) {
        if ("zhen".equals(pairId)) {
            return "Xenova/opus-mt-zh-en";
        }
        if ("koen".equals(pairId)) {
            return "Xenova/opus-mt-ko-en";
        }
        return null;
    }

    private static void fetchOpusPair(Context context, String pairId, String repo) throws Exception {
        File dest = translateDir(context, pairId);
        dest.mkdirs();
        String hf = "https://huggingface.co/" + repo + "/resolve/main";
        OptionalHttp.download(hf + "/onnx/encoder_model_quantized.onnx",
                new File(dest, "encoder.onnx"), 1_000_000L);
        OptionalHttp.download(hf + "/onnx/decoder_model_quantized.onnx",
                new File(dest, "decoder.onnx"), 1_000_000L);
        OptionalHttp.download(hf + "/config.json", new File(dest, "config.json"), 64L);
        File tok = new File(dest, "tokenizer.json");
        File pieces = new File(dest, "pieces.tsv");
        if (!pieces.isFile() || pieces.length() < 64) {
            OptionalHttp.download(hf + "/tokenizer.json", tok, 1024L);
            writePiecesTsv(tok, pieces);
            if (tok.exists()) {
                tok.delete();
            }
        }
    }

    private static void writePiecesTsv(File tokenizerJson, File pieces) throws Exception {
        byte[] data = new byte[(int) tokenizerJson.length()];
        try (FileInputStream in = new FileInputStream(tokenizerJson)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        String raw = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject root = new org.json.JSONObject(raw);
        Object vocab = root.getJSONObject("model").get("vocab");
        try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new FileOutputStream(pieces), java.nio.charset.StandardCharsets.UTF_8)) {
            if (vocab instanceof org.json.JSONObject) {
                org.json.JSONObject map = (org.json.JSONObject) vocab;
                org.json.JSONArray names = map.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String piece = names.getString(i);
                        w.write(escapePiece(piece) + "\t" + map.get(piece) + "\n");
                    }
                }
            } else if (vocab instanceof org.json.JSONArray) {
                org.json.JSONArray rows = (org.json.JSONArray) vocab;
                for (int i = 0; i < rows.length(); i++) {
                    Object row = rows.get(i);
                    if (row instanceof org.json.JSONArray) {
                        org.json.JSONArray pair = (org.json.JSONArray) row;
                        w.write(escapePiece(pair.optString(0, "")) + "\t" + pair.opt(1) + "\n");
                    } else {
                        w.write(escapePiece(String.valueOf(row)) + "\t0\n");
                    }
                }
            }
        }
    }

    private static String escapePiece(String piece) {
        return piece.replace("\\", "\\\\").replace("\t", "\\t");
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

    static File unpackQwenGguf(Context context) throws Exception {
        File dir = new File(context.getFilesDir(), "qwen");
        File fast = new File(dir, "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf");
        // Drop USB A/B leftovers so LMK isn't fighting multi-GB unused weights on disk+mmap.
        deleteIfPresent(new File(dir, "Qwen2.5-3B-Instruct-Q4_K_M.gguf"));
        deleteIfPresent(new File(dir, "Qwen3.5-4B-Q4_K_M.gguf"));
        deleteIfPresent(new File(dir, "gemma-2-2b-jpn-it-translate.Q4_K_M.gguf"));
        File ext = context.getExternalFilesDir(null);
        if (ext != null) {
            File extQwen = new File(ext, "qwen");
            deleteIfPresent(new File(extQwen, "Qwen2.5-3B-Instruct-Q4_K_M.gguf"));
            deleteIfPresent(new File(extQwen, "Qwen3.5-4B-Q4_K_M.gguf"));
            deleteIfPresent(new File(extQwen, "gemma-2-2b-jpn-it-translate.Q4_K_M.gguf"));
        }
        if (fast.isFile() && fast.canRead() && fast.length() > 400_000_000L) {
            Log.i(TAG, "Qwen 1.5B on disk " + fast.length());
            return fast;
        }
        dir.mkdirs();
        copyAssetGguf(context, "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf", fast, 400_000_000L);
        if (fast.isFile() && fast.length() > 400_000_000L) {
            Log.i(TAG, "Qwen 1.5B from APK " + fast.length());
            return fast;
        }
        throw new IllegalStateException(
                "Qwen 2.5 1.5B missing from APK assets (models/qwen/).");
    }

    private static void deleteIfPresent(File file) {
        if (file != null && file.isFile() && file.delete()) {
            Log.i(TAG, "removed unused " + file.getName());
        }
    }

    private static void copyAssetGguf(Context context, String name, File dest, long minBytes)
            throws Exception {
        if (dest.isFile() && dest.length() > minBytes) {
            return;
        }
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        try (InputStream in = context.getAssets().open("models/qwen/" + name);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                fos.write(buf, 0, n);
            }
        }
        if (dest.exists()) {
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("could not move " + name);
        }
        Log.i(TAG, "copied " + name + " " + dest.length() + " bytes");
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
        File root = new File(context.getFilesDir(), "asr");
        File dir = new File(root, "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17");
        File onnx = new File(dir, "model.int8.onnx");
        File tokens = new File(dir, "tokens.txt");
        if (onnx.isFile() && tokens.isFile()) {
            return;
        }
        BundledArchive.extractTarBz2(
                context,
                "models/asr/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
                root);
    }

    private OfflineModelPack() {
    }
}
