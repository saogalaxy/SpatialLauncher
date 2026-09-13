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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Webpage Translate client. Qwen 2.5 1.5B (APK) via {@link QwenPageService}.
 */
final class QwenPageEngine {
    private static final String TAG = "QwenPage";
    private static final String MODEL_FILE = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf";
    static final int PASSAGE_CHARS = 1200;
    /** Google-polish windows: short enough that 1.5B won't loop/collapse. */
    static final int POLISH_PASSAGE_CHARS = 300;

    interface ReadyListener {
        void onReady(boolean ok);
    }

    interface ProgressListener {
        void onProgress(int done, int total);
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<ReadyListener> readyWaiters = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(1);
    private final java.util.concurrent.ConcurrentHashMap<Integer, Pending> pending =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            handleReply(msg);
        }
    });
    private volatile String lastError = "Page translator is not loaded";
    private volatile boolean preparing;
    private volatile boolean ready;
    private Messenger service;
    private boolean bound;

    private static QwenPageEngine instance;

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        String text;
        String error;
    }

    static synchronized QwenPageEngine get(Context context) {
        if (instance == null) {
            instance = new QwenPageEngine(context.getApplicationContext());
        }
        return instance;
    }

    private QwenPageEngine(Context context) {
        app = context.getApplicationContext();
    }

    String lastError() {
        return lastError;
    }

    File modelFile() {
        File dir = new File(app.getFilesDir(), "qwen");
        return new File(dir, MODEL_FILE);
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
        io.execute(() -> {
            try {
                OfflineModelPack.unpackQwenGguf(app);
            } catch (Throwable t) {
                finishReady(false, "Could not find page model: " + t.getMessage());
                return;
            }
            main.post(this::bindEngine);
        });
    }

    private void bindEngine() {
        Intent intent = new Intent(app, QwenPageService.class);
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            finishReady(false, "Could not start Qwen process");
        }
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            Message ping = Message.obtain(null, QwenPageService.MSG_PING);
            ping.replyTo = replies;
            try {
                service.send(ping);
            } catch (RemoteException e) {
                finishReady(false, "Qwen process IPC failed");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            ready = false;
            lastError = "Qwen process stopped";
        }
    };

    private void handleReply(Message msg) {
        if (msg.what == QwenPageService.MSG_READY) {
            long ms = msg.getData() != null ? msg.getData().getLong(QwenPageService.KEY_MS, 0) : 0;
            finishReady(true, "Translator ready in " + Math.max(1, ms / 1000) + "s");
            return;
        }
        if (msg.what == QwenPageService.MSG_FAIL) {
            String err = msg.getData() != null
                    ? msg.getData().getString(QwenPageService.KEY_ERROR, "Qwen load failed")
                    : "Qwen load failed";
            finishReady(false, err);
            return;
        }
        if (msg.what == QwenPageService.MSG_RESULT) {
            Pending p = pending.remove(msg.arg1);
            if (p == null) {
                return;
            }
            Bundle data = msg.getData();
            if (data != null) {
                p.error = data.getString(QwenPageService.KEY_ERROR);
                p.text = data.getString(QwenPageService.KEY_TEXT, "");
                long ms = data.getLong(QwenPageService.KEY_MS, 0);
                Log.w(TAG, "passage done in " + ms + "ms outChars="
                        + (p.text == null ? 0 : p.text.length())
                        + (p.error != null ? " err=" + p.error : ""));
            }
            p.latch.countDown();
        }
    }

    private void finishReady(boolean ok, String message) {
        ready = ok;
        preparing = false;
        lastError = message;
        Log.i(TAG, message);
        ArrayList<ReadyListener> waiters;
        synchronized (readyWaiters) {
            waiters = new ArrayList<>(readyWaiters);
            readyWaiters.clear();
        }
        for (ReadyListener waiter : waiters) {
            main.post(() -> waiter.onReady(ok));
        }
        if (!ok && message != null && !message.isEmpty()) {
            PanelAlerts.show(app, message);
        }
    }

    interface LineListener {
        /** {@code latestLine} is the English just produced; {@code englishSoFar} is the full page so far. */
        void onLine(int done, int total, String latestLine, String englishSoFar);
    }

    interface LineSpeaker {
        /** Block until this line has been spoken (or skipped). */
        void speakAndWait(String englishLine);
    }

    void translatePassages(List<String> passages, Consumer<List<String>> out, ProgressListener progress) {
        // Legacy chunk path — unused by PageTranslator stream mode.
        io.execute(() -> {
            ArrayList<String> english = new ArrayList<>();
            int total = passages == null ? 0 : passages.size();
            if (total == 0) {
                main.post(() -> out.accept(english));
                return;
            }
            if (!ready || service == null) {
                lastError = "Qwen is not ready";
                main.post(() -> out.accept(null));
                return;
            }
            String prev = "";
            for (int i = 0; i < total; i++) {
                final int working = i;
                if (progress != null) {
                    main.post(() -> progress.onProgress(working, total));
                }
                String done = translateNow(passages.get(i), prev);
                if (done == null) {
                    main.post(() -> out.accept(null));
                    return;
                }
                english.add(done);
                prev = done;
                final int n = i + 1;
                if (progress != null) {
                    main.post(() -> progress.onProgress(n, total));
                }
            }
            lastError = "Qwen ready";
            main.post(() -> out.accept(english));
        });
    }

    /**
     * Line-by-line translate with previous-English sliding window.
     * Optional {@code speaker} blocks after each line (translate → speak → next).
     */
    void translateLines(List<String> units, Consumer<String> out,
            LineListener lineListener, LineSpeaker speaker) {
        io.execute(() -> {
            int total = units == null ? 0 : units.size();
            if (total == 0) {
                main.post(() -> out.accept(""));
                return;
            }
            if (!ready || service == null) {
                lastError = "Qwen is not ready";
                main.post(() -> out.accept(null));
                return;
            }
            StringBuilder full = new StringBuilder();
            String prev = "";
            int workUnits = 0;
            for (String unit : units) {
                if (unit != null && !unit.trim().isEmpty()) {
                    workUnits++;
                }
            }
            if (workUnits <= 0) {
                workUnits = 1;
            }
            int doneWork = 0;
            for (int i = 0; i < total; i++) {
                String unit = units.get(i);
                if (unit == null) {
                    continue;
                }
                if (unit.trim().isEmpty()) {
                    if (full.length() > 0 && full.charAt(full.length() - 1) != '\n') {
                        full.append('\n');
                    }
                    full.append('\n');
                    continue;
                }
                String eng = translateNow(unit.trim(), prev);
                if (eng == null) {
                    main.post(() -> out.accept(null));
                    return;
                }
                if (full.length() > 0) {
                    if (full.charAt(full.length() - 1) != '\n') {
                        full.append('\n');
                    }
                }
                full.append(eng);
                if (unit.indexOf('\n') >= 0) {
                    full.append('\n');
                }
                prev = eng;
                doneWork++;
                final String snap = full.toString();
                final String line = eng;
                final int d = doneWork;
                final int t = workUnits;
                if (lineListener != null) {
                    main.post(() -> lineListener.onLine(d, t, line, snap));
                }
                if (speaker != null) {
                    try {
                        speaker.speakAndWait(eng);
                    } catch (Throwable speakFail) {
                        Log.w(TAG, "speak failed", speakFail);
                    }
                }
            }
            lastError = "Qwen ready";
            final String result = QwenPageService.collapseRepeatingBlocks(full.toString().trim());
            main.post(() -> out.accept(result));
        });
    }

    private String translateNow(String source, String previousEnglish) {
        Pending p = new Pending();
        int id = seq.getAndIncrement();
        pending.put(id, p);
        Message msg = Message.obtain(null, QwenPageService.MSG_TRANSLATE);
        msg.arg1 = id;
        msg.replyTo = replies;
        Bundle data = new Bundle();
        data.putString(QwenPageService.KEY_TEXT, source);
        if (previousEnglish != null && !previousEnglish.isEmpty()) {
            data.putString(QwenPageService.KEY_PREV, previousEnglish);
        }
        msg.setData(data);
        try {
            service.send(msg);
        } catch (RemoteException e) {
            pending.remove(id);
            lastError = "Qwen process IPC failed";
            return null;
        }
        try {
            if (!p.latch.await(3, TimeUnit.MINUTES)) {
                pending.remove(id);
                lastError = "Qwen timed out on a line";
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "Qwen interrupted";
            return null;
        }
        if (p.error != null && !p.error.isEmpty()) {
            lastError = p.error;
            return null;
        }
        return p.text == null ? "" : p.text;
    }

    /**
     * Grammar-polish already-English passages (e.g. Google MT) with previous-passage
     * sliding context. Falls back to the input unit if a polish call fails.
     */
    void polishLines(List<String> units, Consumer<String> out, LineListener lineListener) {
        io.execute(() -> {
            int total = units == null ? 0 : units.size();
            if (total == 0) {
                main.post(() -> out.accept(""));
                return;
            }
            if (!ready || service == null) {
                lastError = "Qwen is not ready";
                main.post(() -> out.accept(null));
                return;
            }
            StringBuilder full = new StringBuilder();
            String prev = "";
            int workUnits = 0;
            for (String unit : units) {
                if (unit != null && !unit.trim().isEmpty()) {
                    workUnits++;
                }
            }
            if (workUnits <= 0) {
                workUnits = 1;
            }
            int doneWork = 0;
            for (int i = 0; i < total; i++) {
                String unit = units.get(i);
                if (unit == null || unit.trim().isEmpty()) {
                    if (full.length() > 0 && full.charAt(full.length() - 1) != '\n') {
                        full.append('\n');
                    }
                    full.append('\n');
                    continue;
                }
                String polished = polishNow(unit.trim(), prev);
                if (polished == null || polished.isEmpty()
                        || QwenPageService.polishLooksBad(unit.trim(), polished)) {
                    polished = unit.trim();
                }
                polished = GoogleWebTranslate.cleanOutput(polished);
                if (polished.isEmpty()) {
                    polished = GoogleWebTranslate.cleanOutput(unit);
                }
                if (full.length() > 0) {
                    full.append("\n\n");
                }
                full.append(polished);
                prev = polished;
                doneWork++;
                final String snap = full.toString();
                final String line = polished;
                final int d = doneWork;
                final int t = workUnits;
                if (lineListener != null) {
                    main.post(() -> lineListener.onLine(d, t, line, snap));
                }
            }
            lastError = "Qwen ready";
            final String result = QwenPageService.collapseRepeatingBlocks(full.toString().trim());
            main.post(() -> out.accept(result));
        });
    }

    private String polishNow(String source, String previousEnglish) {
        Pending p = new Pending();
        int id = seq.getAndIncrement();
        pending.put(id, p);
        Message msg = Message.obtain(null, QwenPageService.MSG_POLISH);
        msg.arg1 = id;
        msg.replyTo = replies;
        Bundle data = new Bundle();
        data.putString(QwenPageService.KEY_TEXT, source);
        if (previousEnglish != null && !previousEnglish.isEmpty()) {
            data.putString(QwenPageService.KEY_PREV, previousEnglish);
        }
        msg.setData(data);
        try {
            service.send(msg);
        } catch (RemoteException e) {
            pending.remove(id);
            lastError = "Qwen process IPC failed";
            return null;
        }
        try {
            if (!p.latch.await(3, TimeUnit.MINUTES)) {
                pending.remove(id);
                lastError = "Qwen polish timed out";
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "Qwen interrupted";
            return null;
        }
        if (p.error != null && !p.error.isEmpty()) {
            lastError = p.error;
            return source;
        }
        return p.text == null || p.text.isEmpty() ? source : p.text;
    }

    static List<String> splitPassages(String article) {
        return splitPassages(article, PASSAGE_CHARS);
    }

    /** Pack paragraphs into ~{@code maxChars} windows for Google polish. */
    static List<String> splitPolishPassages(String article) {
        return splitPassages(article, POLISH_PASSAGE_CHARS);
    }

    static List<String> splitPassages(String article, int maxChars) {
        ArrayList<String> out = new ArrayList<>();
        if (article == null || article.trim().isEmpty()) {
            return out;
        }
        int limit = Math.max(200, maxChars);
        String[] paras = article.trim().split("\\n+");
        StringBuilder buf = new StringBuilder();
        for (String para : paras) {
            String line = para.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (buf.length() > 0 && buf.length() + line.length() + 1 > limit) {
                out.add(buf.toString().trim());
                buf.setLength(0);
            }
            if (line.length() > limit) {
                if (buf.length() > 0) {
                    out.add(buf.toString().trim());
                    buf.setLength(0);
                }
                for (int i = 0; i < line.length(); i += limit) {
                    out.add(line.substring(i, Math.min(line.length(), i + limit)));
                }
                continue;
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(line);
        }
        if (buf.length() > 0) {
            out.add(buf.toString().trim());
        }
        return out;
    }

    static String stripChatWrapper(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        // Drop markdown chrome some instruct models add.
        if (t.startsWith("###") || t.startsWith("**")) {
            int nl = t.indexOf('\n');
            if (nl > 0 && nl < 80) {
                t = t.substring(nl).trim();
            }
        }
        String[] starts = {
                "Here's the English translation",
                "Here is the English translation",
                "Here's the translation",
                "Here is the translation",
                "Here is your translation",
                "Here's your translation",
                "Here is the polished",
                "Here's the polished",
                "Here is a polished",
                "Corrected:",
                "Corrected",
                "English Translation",
                "Sure,",
                "Of course"
        };
        for (String s : starts) {
            if (t.regionMatches(true, 0, s, 0, s.length())) {
                int dash = t.indexOf("\n\n");
                if (dash > 0) {
                    t = t.substring(dash).trim();
                } else {
                    int nl = t.indexOf('\n');
                    if (nl > 0) {
                        t = t.substring(nl).trim();
                    }
                }
                if (t.startsWith("—") || t.startsWith("-") || t.startsWith("*")) {
                    t = t.substring(1).trim();
                }
                break;
            }
        }
        int footer = t.toLowerCase().indexOf("let me know if");
        if (footer > 80) {
            t = t.substring(0, footer).trim();
        }
        int note = t.toLowerCase().indexOf("[note:");
        if (note < 0) {
            note = t.toLowerCase().indexOf("***\n\n[note");
        }
        if (note > 120) {
            t = t.substring(0, note).trim();
        }
        return t;
    }
}
