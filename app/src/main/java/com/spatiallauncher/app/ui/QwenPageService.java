package com.spatiallauncher.app.ui;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import net.ladenthin.llama.LlamaModel;
import net.ladenthin.llama.parameters.InferenceParameters;
import net.ladenthin.llama.parameters.ModelParameters;
import net.ladenthin.llama.value.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads Qwen for webpage passages. Isolated process so libjllama is not mixed
 * with Piper's sherpa ONNX Runtime.
 */
public class QwenPageService extends Service {
    static final int MSG_PING = 1;
    static final int MSG_READY = 2;
    static final int MSG_FAIL = 3;
    static final int MSG_TRANSLATE = 4;
    static final int MSG_RESULT = 5;
    static final int MSG_POLISH = 6;

    static final String KEY_TEXT = "text";
    static final String KEY_PREV = "prev";
    static final String KEY_ERROR = "error";
    static final String KEY_MS = "ms";

    private static final String TAG = "QwenPageSvc";
    /** Line-by-line prompt: short context keeps 1.5B stable and LMK-safe. */
    private static final String SYSTEM =
            "You translate Japanese light-novel lines into natural, idiomatic English. "
                    + "Output ONLY the English for the current Japanese line — no preface, "
                    + "notes, or thanks. Match the tone of any previous English line. "
                    + "Romanize names carefully (本須麗乃 → Motosu Urano). "
                    + "Never refuse. Never repeat.";
    private static final String SYSTEM_POLISH =
            "You lightly fix English grammar for light-novel narration. "
                    + "Keep nearly the same wording and length. Never repeat a sentence. "
                    + "Output ONLY the corrected passage once — no preface or notes.";
    private static final Pattern THINK = Pattern.compile(
            "(?is)<think>.*?</think>|</?think>");
    private static final Pattern REFUSAL = Pattern.compile(
            "(?i)(will not translate|i cannot translate|i can't translate|i will not|"
                    + "cannot assist|different type of text|as an ai|i'm unable to)");
    private static final Pattern PREFACE = Pattern.compile(
            "(?is)^\\s*(here(?:'s| is)(?: the)?(?: english)? translation[^\\n]*:?\\s*[\"']?)");
    private static final Pattern THANK_FOOTER = Pattern.compile(
            "(?is)\\n\\s*(thank you for reading.*|enjoyed by those who enjoy.*|"
                    + "enjoying this if you do!?|"
                    + "and so i finally began\\.?|"
                    + "let'?s start now\\.?|"
                    + "here goes!?.*)\\s*$");
    private static final Pattern CJK_LINE = Pattern.compile(
            "[\\u3040-\\u30ff\\u3400-\\u9fff]");

    private HandlerThread thread;
    private Messenger messenger;
    private LlamaModel model;
    private volatile boolean ready;
    private volatile String failReason;
    private volatile long loadMs;
    private PowerManager.WakeLock translateWakeLock;
    private Object perfHintSession; // PerformanceHintManager.Session when API 31+

    @Override
    public void onCreate() {
        super.onCreate();
        // Default (not BACKGROUND) so load + idle ping aren't deprioritized vs panel UI.
        thread = new HandlerThread("qwen-page", Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        Handler handler = new Incoming(thread.getLooper());
        messenger = new Messenger(handler);
        handler.post(this::loadEngine);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        endTranslateBoost();
        if (model != null) {
            try {
                model.close();
            } catch (Throwable ignored) {
            }
            model = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    /** Raise CPU scheduling + keep CPU awake while a passage runs; drop after. */
    private void beginTranslateBoost() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
        try {
            if (translateWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    translateWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpatialLauncher:QwenTranslate");
                    translateWakeLock.setReferenceCounted(false);
                }
            }
            if (translateWakeLock != null && !translateWakeLock.isHeld()) {
                translateWakeLock.acquire(12 * 60 * 1000L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake lock failed", t);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && perfHintSession == null) {
            try {
                android.os.PerformanceHintManager phm =
                        getSystemService(android.os.PerformanceHintManager.class);
                if (phm != null) {
                    // Prefer high throughput for multi-second decode bursts.
                    perfHintSession = phm.createHintSession(
                            new int[]{Process.myTid()},
                            50_000_000L);
            Log.w(TAG, "perf hint session on tid=" + Process.myTid());
                }
            } catch (Throwable t) {
                Log.w(TAG, "perf hint unavailable", t);
            }
        }
        Log.w(TAG, "translate boost ON prio=" + Process.getThreadPriority(Process.myTid()));
    }

    private void endTranslateBoost() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && perfHintSession != null) {
            try {
                ((android.os.PerformanceHintManager.Session) perfHintSession).close();
            } catch (Throwable ignored) {
            }
            perfHintSession = null;
        }
        try {
            if (translateWakeLock != null && translateWakeLock.isHeld()) {
                translateWakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
        Log.w(TAG, "translate boost OFF prio=" + Process.getThreadPriority(Process.myTid()));
    }

    private void reportHintDuration(long elapsedMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || perfHintSession == null || elapsedMs <= 0) {
            return;
        }
        try {
            ((android.os.PerformanceHintManager.Session) perfHintSession)
                    .reportActualWorkDuration(elapsedMs * 1_000_000L);
        } catch (Throwable ignored) {
        }
    }

    private void loadEngine() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            File gguf = OfflineModelPack.unpackQwenGguf(this);
            // Line-stream 1.5B: modest ctx is enough for prev EN + current JP.
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            ModelParameters params = new ModelParameters()
                    .setModel(gguf.getAbsolutePath())
                    .setGpuLayers(99)
                    .setCtxSize(1024)
                    .setBatchSize(128)
                    .setUbatchSize(64)
                    .setThreads(threads)
                    .setThreadsBatch(threads)
                    .enableJinja();
            Map<String, String> kwargs = new HashMap<>();
            kwargs.put("enable_thinking", "false");
            params.setChatTemplateKwargs(kwargs);
            try {
                System.loadLibrary("jllama");
            } catch (UnsatisfiedLinkError e) {
                Log.w(TAG, "System.loadLibrary(jllama) failed", e);
            }
            Log.w(TAG, "loading OpenCL layers=99 ctx=1024 model=" + gguf.getName()
                    + " threads=" + threads);
            try {
                model = new LlamaModel(params);
            } catch (Throwable gpuFail) {
                Log.w(TAG, "OpenCL model load failed, retrying CPU", gpuFail);
                params.setGpuLayers(0);
                model = new LlamaModel(params);
            }
            ready = true;
            loadMs = SystemClock.elapsedRealtime() - t0;
            Log.i(TAG, "Qwen ready in " + loadMs + "ms");
        } catch (Throwable t) {
            ready = false;
            failReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            loadMs = SystemClock.elapsedRealtime() - t0;
            Log.w(TAG, "Qwen load failed after " + loadMs + "ms", t);
        }
    }

    String translatePassage(String source) {
        return translateLine(source, null);
    }

    String translateLine(String source, String previousEnglish) {
        if (source == null || source.trim().isEmpty()) {
            return "";
        }
        String jp = source.trim();
        StringBuilder user = new StringBuilder();
        user.append("Translate the current Japanese line into natural light-novel English. ");
        user.append("Output ONLY that English line.\n\n");
        if (previousEnglish != null && !previousEnglish.trim().isEmpty()) {
            user.append("Previous English line:\n")
                    .append(previousEnglish.trim())
                    .append("\n\n");
        }
        user.append("Current Japanese line:\n").append(jp).append("\n\nEnglish:");
        List<Pair<String, String>> messages = new ArrayList<>();
        messages.add(new Pair<>("user", user.toString()));
        int nPredict = Math.min(256, Math.max(48, jp.length() * 3));
        InferenceParameters infer = InferenceParameters.empty()
                .withMessages(SYSTEM, messages)
                .withNPredict(nPredict)
                .withTemperature(0.25f)
                .withRepeatPenalty(1.2f)
                .withCachePrompt(false);
        long t0 = SystemClock.elapsedRealtime();
        String raw = model.chatCompleteText(infer);
        String out = QwenPageEngine.stripChatWrapper(stripThink(raw));
        out = stripPreface(out);
        out = stripJunkLines(out);
        out = collapseRepeatingBlocks(out);
        // One line should stay one line — take first non-empty paragraph if it rambled.
        if (out.contains("\n")) {
            for (String part : out.split("\\n")) {
                if (part.trim().length() > 0) {
                    out = part.trim();
                    break;
                }
            }
        }
        if (looksLikeRefusal(out) || out.trim().isEmpty()) {
            Log.w(TAG, "weak line, retrying; out=" + out);
            messages = new ArrayList<>();
            messages.add(new Pair<>("user", "Japanese:\n" + jp + "\n\nEnglish:"));
            infer = InferenceParameters.empty()
                    .withMessages("Translate to English. One line only.", messages)
                    .withNPredict(nPredict)
                    .withTemperature(0.1f)
                    .withRepeatPenalty(1.25f)
                    .withCachePrompt(false);
            raw = model.chatCompleteText(infer);
            out = collapseRepeatingBlocks(stripJunkLines(stripPreface(
                    QwenPageEngine.stripChatWrapper(stripThink(raw)))));
        }
        if (looksLikeRefusal(out) || out.trim().isEmpty()) {
            return jp;
        }
        Log.w(TAG, "line " + jp.length() + " -> " + out.length()
                + " in " + (SystemClock.elapsedRealtime() - t0) + "ms");
        return out.trim();
    }

    /** Grammar / fluency polish for already-English passages (Google MT output). */
    String polishEnglish(String source, String previousEnglish) {
        if (source == null || source.trim().isEmpty()) {
            return "";
        }
        String en = source.trim();
        StringBuilder user = new StringBuilder();
        user.append("Rewrite this English with correct grammar. Keep the SAME length and all details. ");
        user.append("Do not repeat sentences. Output ONLY the corrected English once.\n\n");
        if (previousEnglish != null && !previousEnglish.trim().isEmpty()) {
            String prev = previousEnglish.trim();
            if (prev.length() > 180) {
                prev = prev.substring(prev.length() - 180);
                int sp = prev.indexOf(' ');
                if (sp > 0 && sp < 30) {
                    prev = prev.substring(sp + 1);
                }
            }
            user.append("Previous polished English (for tone only):\n").append(prev).append("\n\n");
        }
        user.append("Current English:\n").append(en).append("\n\n");
        // Avoid a "Corrected:" label — the 1.5B model often echoes it into the page.
        List<Pair<String, String>> messages = new ArrayList<>();
        messages.add(new Pair<>("user", user.toString()));
        // Cap generation near input size so the model can't ramble/loop a chapter.
        int nPredict = Math.min(360, Math.max(96, (en.length() * 12) / 10 + 24));
        InferenceParameters infer = InferenceParameters.empty()
                .withMessages(SYSTEM_POLISH, messages)
                .withNPredict(nPredict)
                .withTemperature(0.15f)
                .withRepeatPenalty(1.35f)
                .withCachePrompt(false);
        long t0 = SystemClock.elapsedRealtime();
        String raw = model.chatCompleteText(infer);
        String out = QwenPageEngine.stripChatWrapper(stripThink(raw));
        out = stripPreface(out);
        out = stripJunkLines(out);
        out = collapseRepeatingBlocks(out);
        out = collapseInlineRepeats(out);
        // Only collapse to one line when the source was a single line.
        if (out.contains("\n") && en.indexOf('\n') < 0) {
            for (String part : out.split("\\n")) {
                if (part.trim().length() > 0) {
                    out = part.trim();
                    break;
                }
            }
        }
        if (looksLikeRefusal(out) || out.trim().isEmpty() || polishLooksBad(en, out)) {
            Log.w(TAG, "polish rejected; keeping Google text ("
                    + en.length() + " -> " + (out == null ? 0 : out.length()) + ")");
            return en;
        }
        Log.w(TAG, "polish " + en.length() + " -> " + out.length()
                + " in " + (SystemClock.elapsedRealtime() - t0) + "ms");
        return out.trim();
    }

    /** True when the model shrunk the passage or looped the same lines. */
    static boolean polishLooksBad(String source, String out) {
        if (source == null || out == null) {
            return true;
        }
        String s = source.trim();
        String o = out.trim();
        if (o.isEmpty()) {
            return true;
        }
        // Lost most of the content.
        if (s.length() > 80 && o.length() * 100 < s.length() * 55) {
            return true;
        }
        // Same line repeated 3+ times.
        String[] lines = o.split("\\n+");
        if (lines.length >= 3) {
            String first = lines[0].trim();
            if (first.length() > 12) {
                int same = 0;
                for (String line : lines) {
                    if (line.trim().equalsIgnoreCase(first)) {
                        same++;
                    }
                }
                if (same >= 3) {
                    return true;
                }
            }
        }
        // Sentence-level loop: first sentence appears 3+ times in the blob.
        String[] sents = o.split("(?<=[.!?])\\s+");
        if (sents.length >= 3) {
            String head = sents[0].trim();
            if (head.length() > 20) {
                int hits = 0;
                for (String sent : sents) {
                    if (sent.trim().equalsIgnoreCase(head)) {
                        hits++;
                    }
                }
                if (hits >= 3) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Collapse immediate repeated phrases/sentences inside one polish result. */
    static String collapseInlineRepeats(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] parts = text.split("(?<=[.!?])\\s+");
        if (parts.length < 2) {
            return text.trim();
        }
        StringBuilder sb = new StringBuilder();
        String last = null;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (last != null && p.equalsIgnoreCase(last)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p);
            last = p;
        }
        return sb.toString().trim();
    }

    /** Split novel text into JP sentence/line units for streaming translate. */
    static List<String> splitStreamUnits(String text) {
        ArrayList<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (int i = 0; i < text.length(); ) {
            int nextBreak = -1;
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                if (c == '。' || c == '！' || c == '？' || c == '…') {
                    nextBreak = j + 1;
                    while (nextBreak < text.length() && text.charAt(nextBreak) == '\n') {
                        nextBreak++;
                    }
                    break;
                }
                if (c == '\n') {
                    nextBreak = j + 1;
                    break;
                }
            }
            if (nextBreak < 0) {
                out.add(text.substring(i));
                break;
            }
            out.add(text.substring(i, nextBreak));
            i = nextBreak;
        }
        return out;
    }

    static String stripThink(String raw) {
        if (raw == null) {
            return "";
        }
        String t = THINK.matcher(raw).replaceAll("").trim();
        int open = t.toLowerCase().indexOf("<think");
        if (open >= 0) {
            t = t.substring(0, open).trim();
        }
        return t;
    }

    static String stripPreface(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text.trim();
        java.util.regex.Matcher m = PREFACE.matcher(t);
        if (m.find()) {
            t = t.substring(m.end()).trim();
        }
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        // Drop Syosetu pager leftovers the model copied (e.g. "One/677", "1/677").
        String[] lines = t.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String L = line.trim();
            if (L.matches("(?i)(one|[0-9]+)\\s*/\\s*[0-9]+")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        t = sb.toString().trim();
        return THANK_FOOTER.matcher(t).replaceFirst("").trim();
    }

    static String stripJunkLines(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String L = line.trim();
            if (L.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                continue;
            }
            // Drop accidental CJK title lines (e.g. Chinese 第一部…).
            if (CJK_LINE.matcher(L).find() && L.length() < 40) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return THANK_FOOTER.matcher(sb.toString().trim()).replaceFirst("").trim();
    }

    static boolean looksLikeRefusal(String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        return REFUSAL.matcher(text).find() && text.length() < 400;
    }

    /** Stop llama looping the same paragraphs down the page. */
    static String collapseRepeatingBlocks(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] paras = text.split("\\n{2,}");
        ArrayList<String> kept = new ArrayList<>();
        String last = null;
        int streak = 0;
        for (String para : paras) {
            String p = para.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (last != null && p.equalsIgnoreCase(last)) {
                streak++;
                if (streak >= 1) {
                    continue;
                }
            } else {
                streak = 0;
                last = p;
            }
            kept.add(p);
        }
        if (kept.isEmpty()) {
            return text.trim();
        }
        String joined = String.join("\n\n", kept);
        return stripTrailingLineLoop(joined);
    }

    static String stripTrailingLineLoop(String text) {
        String[] lines = text.split("\\n");
        if (lines.length < 8) {
            return text;
        }
        int window = Math.min(7, lines.length / 2);
        for (; window >= 3; window--) {
            boolean loop = true;
            int start = lines.length - window;
            while (start - window >= 0 && loop) {
                for (int i = 0; i < window; i++) {
                    if (!lines[start - window + i].trim()
                            .equalsIgnoreCase(lines[start + i].trim())) {
                        loop = false;
                        break;
                    }
                }
                if (loop) {
                    start -= window;
                }
            }
            if (start < lines.length - window) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < start + window; i++) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(lines[i]);
                }
                return sb.toString().trim();
            }
        }
        return text;
    }

    private final class Incoming extends Handler {
        Incoming(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_PING) {
                if (!ready) {
                    loadEngine();
                }
                replyStatus(msg.replyTo);
                return;
            }
            if (msg.what == MSG_TRANSLATE) {
                handleTranslate(msg);
                return;
            }
            if (msg.what == MSG_POLISH) {
                handlePolish(msg);
            }
        }

        private void handleTranslate(Message msg) {
            Messenger replyTo = msg.replyTo;
            Bundle in = msg.getData();
            String text = in == null ? "" : in.getString(KEY_TEXT, "");
            String prev = in == null ? null : in.getString(KEY_PREV, null);
            Message out = Message.obtain(null, MSG_RESULT);
            out.arg1 = msg.arg1;
            Bundle data = new Bundle();
            beginTranslateBoost();
            long t0 = SystemClock.elapsedRealtime();
            try {
                if (!ready || model == null) {
                    throw new IllegalStateException(failReason != null ? failReason : "Qwen not ready");
                }
                data.putString(KEY_TEXT, translateLine(text, prev));
            } catch (Throwable t) {
                data.putString(KEY_ERROR, String.valueOf(t.getMessage()));
                data.putString(KEY_TEXT, "");
            } finally {
                long ms = SystemClock.elapsedRealtime() - t0;
                data.putLong(KEY_MS, ms);
                reportHintDuration(ms);
                endTranslateBoost();
            }
            out.setData(data);
            sendQuiet(replyTo, out);
        }

        private void handlePolish(Message msg) {
            Messenger replyTo = msg.replyTo;
            Bundle in = msg.getData();
            String text = in == null ? "" : in.getString(KEY_TEXT, "");
            String prev = in == null ? null : in.getString(KEY_PREV, null);
            Message out = Message.obtain(null, MSG_RESULT);
            out.arg1 = msg.arg1;
            Bundle data = new Bundle();
            beginTranslateBoost();
            long t0 = SystemClock.elapsedRealtime();
            try {
                if (!ready || model == null) {
                    throw new IllegalStateException(failReason != null ? failReason : "Qwen not ready");
                }
                data.putString(KEY_TEXT, polishEnglish(text, prev));
            } catch (Throwable t) {
                data.putString(KEY_ERROR, String.valueOf(t.getMessage()));
                data.putString(KEY_TEXT, text);
            } finally {
                long ms = SystemClock.elapsedRealtime() - t0;
                data.putLong(KEY_MS, ms);
                reportHintDuration(ms);
                endTranslateBoost();
            }
            out.setData(data);
            sendQuiet(replyTo, out);
        }

        private void replyStatus(Messenger replyTo) {
            Message out = Message.obtain(null, ready ? MSG_READY : MSG_FAIL);
            Bundle data = new Bundle();
            data.putLong(KEY_MS, loadMs);
            if (failReason != null) {
                data.putString(KEY_ERROR, failReason);
            }
            out.setData(data);
            sendQuiet(replyTo, out);
        }

        private void sendQuiet(Messenger replyTo, Message out) {
            if (replyTo == null) {
                return;
            }
            try {
                replyTo.send(out);
            } catch (RemoteException ignored) {
            }
        }
    }
}
