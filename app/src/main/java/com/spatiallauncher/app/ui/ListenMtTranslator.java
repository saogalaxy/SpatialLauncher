package com.spatiallauncher.app.ui;

import android.content.Context;
import android.util.Log;

import java.util.function.Consumer;

/**
 * Listen ASR → English using the same bundled OPUS-MT path as OCR
 * ({@link OnDeviceTranslator} / {@link TranslateMtService}). No Play Store / ML Kit
 * Translate downloads.
 */
final class ListenMtTranslator {
    private static final String TAG = "ListenMt";

    interface ReadyListener {
        void onReady(boolean ok, String message);
    }

    private final OnDeviceTranslator opus;
    private volatile String lastError = "Listen translate not ready";

    private static ListenMtTranslator instance;

    static synchronized ListenMtTranslator get(Context context) {
        if (instance == null) {
            instance = new ListenMtTranslator(context.getApplicationContext());
        }
        return instance;
    }

    private ListenMtTranslator(Context context) {
        opus = OnDeviceTranslator.get(context);
    }

    String lastError() {
        return lastError;
    }

    /**
     * Warm the on-device OPUS process (ja→en bundled; zh/ko unpack on demand).
     */
    void ensureReady(ReadyListener listener) {
        opus.ensureReady(ok -> {
            if (ok) {
                lastError = "Listen translate ready";
                Log.i(TAG, lastError);
            } else {
                lastError = opus.lastError() != null ? opus.lastError() : "Listen translate failed";
                Log.w(TAG, lastError);
            }
            if (listener != null) {
                listener.onReady(ok, lastError);
            }
        });
    }

    void toEnglish(String text, Consumer<String> out) {
        if (out == null) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            out.accept("");
            return;
        }
        final String line = text.trim();
        if (OnDeviceTranslator.looksPrimarilyEnglish(line)) {
            out.accept(line);
            return;
        }
        opus.toEnglish(line, english -> {
            String emit = english != null && !english.trim().isEmpty() ? english.trim() : line;
            Log.i(TAG, "OPUS → en: " + emit);
            out.accept(emit);
        });
    }
}
