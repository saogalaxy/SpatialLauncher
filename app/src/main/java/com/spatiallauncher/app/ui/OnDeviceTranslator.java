package com.spatiallauncher.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Client for OPUS-MT in {@link TranslateMtService} (separate process).
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
    private final AtomicInteger seq = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            handleReply(msg);
        }
    });
    private volatile boolean preparing;
    private volatile boolean ready;
    private volatile String lastError = "Translator starting";
    private Messenger service;
    private boolean bound;

    private static OnDeviceTranslator instance;

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        String text;
        String error;
    }

    static synchronized OnDeviceTranslator get(Context context) {
        if (instance == null) {
            instance = new OnDeviceTranslator(context.getApplicationContext());
        }
        return instance;
    }

    private OnDeviceTranslator(Context context) {
        app = context.getApplicationContext();
    }

    String lastError() {
        return lastError;
    }

    void ensureReady(ReadyListener listener) {
        if (ready && service != null) {
            if (listener != null) {
                main.post(() -> listener.onReady(true));
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
        bindEngine();
    }

    private void bindEngine() {
        Intent intent = new Intent(app, TranslateMtService.class);
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            finishReady(false, "Could not start translator process");
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            Message ping = Message.obtain(null, TranslateMtService.MSG_PING);
            ping.replyTo = replies;
            try {
                service.send(ping);
            } catch (RemoteException e) {
                finishReady(false, "Translator process IPC failed");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            ready = false;
            lastError = "Translator process stopped";
        }
    };

    private void handleReply(Message msg) {
        if (msg.what == TranslateMtService.MSG_READY) {
            long ms = msg.getData() != null ? msg.getData().getLong(TranslateMtService.KEY_MS, 0) : 0;
            lastError = null;
            finishReady(true, "Translator ready in " + Math.max(1, ms / 1000) + "s");
            return;
        }
        if (msg.what == TranslateMtService.MSG_FAIL) {
            String err = msg.getData() != null
                    ? msg.getData().getString(TranslateMtService.KEY_ERROR, "load failed")
                    : "load failed";
            finishReady(false, err);
            return;
        }
        if (msg.what == TranslateMtService.MSG_RESULT) {
            Pending p = pending.remove(msg.arg1);
            if (p == null) {
                return;
            }
            Bundle data = msg.getData();
            if (data != null) {
                p.error = data.getString(TranslateMtService.KEY_ERROR);
                p.text = data.getString(TranslateMtService.KEY_TEXT, "");
            }
            p.latch.countDown();
        }
    }

    private void finishReady(boolean ok, String message) {
        ready = ok;
        preparing = false;
        lastError = message;
        Log.i(TAG, message);
        java.util.ArrayList<ReadyListener> waiters;
        synchronized (readyWaiters) {
            waiters = new java.util.ArrayList<>(readyWaiters);
            readyWaiters.clear();
        }
        for (ReadyListener waiter : waiters) {
            main.post(() -> waiter.onReady(ok));
        }
        // Success stays quiet (boot warm-up). Only surface real load failures.
        if (!ok && message != null && !message.isEmpty()) {
            PanelAlerts.show(app, message);
        }
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
        translateList(texts, out, null);
    }

    interface LineListener {
        void onLine(int done, int total, String english);
    }

    void translateList(List<String> texts, Consumer<List<String>> out, LineListener lines) {
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
            int total = texts.size();
            for (int i = 0; i < texts.size(); i++) {
                String key = texts.get(i) == null ? "" : texts.get(i);
                String hit = cache.get(key);
                if (hit == null) {
                    hit = translateNow(key);
                    cache.put(key, hit);
                }
                result.add(hit);
                if (lines != null) {
                    final int done = i + 1;
                    final String eng = hit;
                    main.post(() -> lines.onLine(done, total, eng));
                }
            }
            main.post(() -> out.accept(result));
        }));
    }

    private String translateNow(String text) {
        if (text == null || text.trim().isEmpty() || looksPrimarilyEnglish(text)) {
            return text == null ? "" : text.trim();
        }
        Messenger svc = service;
        if (!ready || svc == null) {
            return text.trim();
        }
        int id = seq.getAndIncrement();
        Pending p = new Pending();
        pending.put(id, p);
        Message msg = Message.obtain(null, TranslateMtService.MSG_TRANSLATE);
        msg.arg1 = id;
        msg.replyTo = replies;
        Bundle data = new Bundle();
        data.putString(TranslateMtService.KEY_TEXT, text.trim());
        msg.setData(data);
        try {
            svc.send(msg);
            if (!p.latch.await(45, TimeUnit.SECONDS)) {
                pending.remove(id);
                return text.trim();
            }
        } catch (Exception e) {
            pending.remove(id);
            Log.w(TAG, "translate IPC failed", e);
            return text.trim();
        }
        if (p.text == null || p.text.trim().isEmpty()) {
            return text.trim();
        }
        return cleanMtEnglish(p.text);
    }

    /**
     * OPUS-MT sometimes emits literal escape junk ({@code \n}, {@code \\n}, {@code nnnn't},
     * {@code ///}) into English. Strip before TTS / UI.
     */
    static String cleanMtEnglish(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String t = raw;
        t = t.replace("\\r\\n", " ");
        t = t.replace("\\n", " ");
        t = t.replace("\\r", " ");
        t = t.replace("\\t", " ");
        t = t.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        t = t.replace('\\', ' ');
        t = t.replaceAll("/{2,}", " ");
        // "nnn't" / "nnnnn't" → "n't" (newline + contraction debris)
        t = t.replaceAll("(?i)n{2,}n't", "n't");
        t = t.replaceAll(" {2,}", " ").trim();
        if (t.isEmpty() || t.matches("[/'\\-.,:;!?\\s]+")) {
            return "";
        }
        return t;
    }

    /** Prefer full sentences. Only hard-wrap very long clauses. */
    static List<String> splitForModel(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            buf.appendCodePoint(cp);
            i += Character.charCount(cp);
            boolean sentenceEnd = cp == '。' || cp == '！' || cp == '？'
                    || cp == '．' || cp == '.' || cp == '!' || cp == '?'
                    || cp == '\n';
            if (sentenceEnd || buf.length() >= 180) {
                String piece = buf.toString().trim();
                if (!piece.isEmpty()) {
                    out.add(piece);
                }
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            String piece = buf.toString().trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
        }
        if (out.isEmpty()) {
            out.add(text);
        }
        return out;
    }

    private void emit(Consumer<String> out, String text) {
        main.post(() -> out.accept(text));
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
