package com.spatiallauncher.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import ai.onnxruntime.OrtEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Bundled OPUS-MT (ja→en, zh→en) in APK assets. No Play Services download.
 */
final class OnDeviceTranslator {
    private static final String TAG = "OnDeviceTranslator";

    interface ReadyListener {
        void onReady(boolean ok);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final java.util.ArrayList<ReadyListener> readyWaiters = new java.util.ArrayList<>();
    private volatile boolean preparing;
    private volatile boolean attempted;
    private volatile boolean ready;
    private volatile MarianOnnxPair jaEn;
    private volatile MarianOnnxPair zhEn;
    private OrtEnvironment env;

    private static OnDeviceTranslator instance;

    static synchronized OnDeviceTranslator get(Context context) {
        if (instance == null) {
            instance = new OnDeviceTranslator(context.getApplicationContext());
        }
        return instance;
    }

    private OnDeviceTranslator(Context context) {
        app = context.getApplicationContext();
    }

    void ensureReady(ReadyListener listener) {
        if (ready || attempted) {
            if (listener != null) {
                main.post(() -> listener.onReady(ready));
            }
            return;
        }
        synchronized (readyWaiters) {
            if (listener != null) {
                readyWaiters.add(listener);
            }
            if (preparing) {
                return;
            }
            preparing = true;
        }
        io.execute(() -> {
            boolean ok = false;
            try {
                env = OrtEnvironment.getEnvironment();
                jaEn = MarianOnnxPair.load(app, env, "jaen");
                ok = jaEn != null;
            } catch (Throwable t) {
                Log.w(TAG, "bundled translator failed to load", t);
            }
            ready = ok;
            attempted = true;
            preparing = false;
            java.util.ArrayList<ReadyListener> waiters;
            synchronized (readyWaiters) {
                waiters = new java.util.ArrayList<>(readyWaiters);
                readyWaiters.clear();
            }
            final boolean result = ok;
            for (ReadyListener waiter : waiters) {
                main.post(() -> waiter.onReady(result));
            }
        });
    }

    void toEnglish(String text, Consumer<String> out) {
        if (out == null) {
            return;
        }
        if (text == null || text.trim().isEmpty() || looksPrimarilyEnglish(text)) {
            out.accept(text == null ? "" : text.trim());
            return;
        }
        final String line = text.trim();
        if (ready) {
            io.execute(() -> emit(out, translateNow(line)));
            return;
        }
        ensureReady(ok -> io.execute(() -> emit(out, translateNow(line))));
    }

    void translateList(List<String> texts, Consumer<List<String>> out) {
        if (out == null) {
            return;
        }
        if (texts == null || texts.isEmpty()) {
            main.post(() -> out.accept(new java.util.ArrayList<String>()));
            return;
        }
        ensureReady(ok -> io.execute(() -> {
            ArrayList<String> result = new ArrayList<>(texts.size());
            LinkedHashMap<String, String> cache = new LinkedHashMap<>();
            for (String raw : texts) {
                String key = raw == null ? "" : raw;
                String hit = cache.get(key);
                if (hit == null) {
                    hit = translateNow(key);
                    cache.put(key, hit);
                }
                result.add(hit);
            }
            main.post(() -> out.accept(result));
        }));
    }

    private String translateNow(String text) {
        if (text == null || text.trim().isEmpty() || looksPrimarilyEnglish(text)) {
            return text == null ? "" : text.trim();
        }
        List<String> parts = splitForModel(text.trim());
        if (parts.size() == 1) {
            return runPair(parts.get(0));
        }
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            String piece = runPair(part);
            if (piece.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(piece);
        }
        return joined.toString();
    }

    private String runPair(String text) {
        MarianOnnxPair pair = pickEngine(text);
        if (pair == null) {
            return text;
        }
        String translated = pair.translate(text);
        if (translated == null || translated.trim().isEmpty()) {
            return text;
        }
        return translated.trim();
    }

    /** OPUS-MT encoder cap is ~96 tokens; keep chunks short. */
    static List<String> splitForModel(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text.length() <= 72) {
            out.add(text);
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            buf.appendCodePoint(cp);
            i += Character.charCount(cp);
            boolean punct = cp == '。' || cp == '！' || cp == '？' || cp == '\n'
                    || cp == '.' || cp == '!' || cp == '?';
            if ((punct && buf.length() >= 24) || buf.length() >= 72) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    private void emit(Consumer<String> out, String text) {
        main.post(() -> out.accept(text));
    }

    private MarianOnnxPair pickEngine(String text) {
        if (containsKana(text) && jaEn != null) {
            return jaEn;
        }
        if (containsCjkIdeograph(text) && !containsKana(text)) {
            MarianOnnxPair zh = zhPair();
            if (zh != null) {
                return zh;
            }
        }
        if (jaEn != null) {
            return jaEn;
        }
        return zhPair();
    }

    private MarianOnnxPair zhPair() {
        if (zhEn != null) {
            return zhEn;
        }
        if (env == null || app == null) {
            return null;
        }
        try {
            zhEn = MarianOnnxPair.load(app, env, "zhen");
        } catch (Throwable t) {
            Log.w(TAG, "zh-en model missing", t);
        }
        return zhEn;
    }

    static boolean looksPrimarilyEnglish(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        int letters = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.BASIC_LATIN
                    || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_A
                    || block == Character.UnicodeBlock.LATIN_EXTENDED_B) {
                latin++;
            }
        }
        if (letters == 0) {
            return true;
        }
        return latin * 10 >= letters * 7 && !containsKanaOrCjk(text) && !containsHangul(text);
    }

    static boolean containsKanaOrCjk(String text) {
        return containsKana(text) || containsCjkIdeograph(text);
    }

    static boolean containsCjk(String text) {
        return containsKanaOrCjk(text);
    }

    static boolean containsKana(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS) {
                return true;
            }
        }
        return false;
    }

    static boolean containsCjkIdeograph(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                return true;
            }
        }
        return false;
    }

    static boolean containsHangul(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.HANGUL_SYLLABLES
                    || block == Character.UnicodeBlock.HANGUL_JAMO
                    || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO) {
                return true;
            }
        }
        return false;
    }
}
