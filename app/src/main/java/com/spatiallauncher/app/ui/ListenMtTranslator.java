package com.spatiallauncher.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Offline ML Kit Translate for Listen (SenseVoice ASR → English).
 * OCR / page Translate keep using OPUS via {@link OnDeviceTranslator}.
 */
final class ListenMtTranslator {
    private static final String TAG = "ListenMt";
    private static final long TRANSLATE_TIMEOUT_MS = 12_000L;
    private static final long DOWNLOAD_TIMEOUT_MS = 120_000L;

    interface ReadyListener {
        void onReady(boolean ok, String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Translator> clients = new HashMap<>();
    private volatile boolean jaReady;
    private volatile String lastError = "Listen translate not ready";

    private static ListenMtTranslator instance;

    static synchronized ListenMtTranslator get(Context context) {
        if (instance == null) {
            instance = new ListenMtTranslator(context.getApplicationContext());
        }
        return instance;
    }

    private ListenMtTranslator(Context context) {
    }

    String lastError() {
        return lastError;
    }

    /**
     * Prefetch JA→EN (and ZH/KO) packs so the first Listen utterance is not blocked.
     */
    void ensureReady(ReadyListener listener) {
        io.execute(() -> {
            boolean ok;
            String msg;
            try {
                ensureClient(TranslateLanguage.JAPANESE);
                jaReady = true;
                try {
                    ensureClient(TranslateLanguage.CHINESE);
                } catch (Throwable ignored) {
                }
                try {
                    ensureClient(TranslateLanguage.KOREAN);
                } catch (Throwable ignored) {
                }
                ok = true;
                msg = "Listen translate ready";
                lastError = msg;
            } catch (Throwable t) {
                lastError = t.getMessage() != null ? t.getMessage() : "Listen translate download failed";
                msg = lastError;
                Log.w(TAG, "ensureReady failed", t);
                ok = false;
            }
            final boolean readyOk = ok;
            final String readyMsg = msg;
            if (listener != null) {
                main.post(() -> listener.onReady(readyOk, readyMsg));
            }
        });
    }

    void toEnglish(String text, Consumer<String> out) {
        if (out == null) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            main.post(() -> out.accept(""));
            return;
        }
        final String line = text.trim();
        if (OnDeviceTranslator.looksPrimarilyEnglish(line)) {
            main.post(() -> out.accept(line));
            return;
        }
        io.execute(() -> {
            String english = line;
            try {
                String source = sourceLanguageFor(line);
                Translator client = ensureClient(source);
                english = Tasks.await(client.translate(line), TRANSLATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (english == null || english.trim().isEmpty()) {
                    english = line;
                } else {
                    english = english.trim();
                }
                Log.i(TAG, "ML Kit " + source + "→en: " + english);
            } catch (Throwable t) {
                lastError = t.getMessage() != null ? t.getMessage() : "translate failed";
                Log.w(TAG, "toEnglish failed, returning ASR text", t);
                english = line;
            }
            final String emit = english;
            main.post(() -> out.accept(emit));
        });
    }

    private static String sourceLanguageFor(String text) {
        String pair = OfflineModelPack.pickCaptionPair(text);
        if ("zhen".equals(pair)) {
            return TranslateLanguage.CHINESE;
        }
        if ("koen".equals(pair)) {
            return TranslateLanguage.KOREAN;
        }
        return TranslateLanguage.JAPANESE;
    }

    private synchronized Translator ensureClient(String sourceLang) throws Exception {
        Translator existing = clients.get(sourceLang);
        if (existing != null) {
            return existing;
        }
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build();
        Translator client = Translation.getClient(options);
        DownloadConditions conditions = new DownloadConditions.Builder().build();
        Log.i(TAG, "Ensuring ML Kit model " + sourceLang + "→en");
        Tasks.await(client.downloadModelIfNeeded(conditions), DOWNLOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clients.put(sourceLang, client);
        Log.i(TAG, "ML Kit model ready " + sourceLang + "→en");
        return client;
    }
}
