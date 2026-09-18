package com.spatiallauncher.app.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Browser Read speaks the DOM as-is. Browser Translate uses OPUS-MT (same as OCR /
 * Share) line-by-line, then Piper’s non-blocking queue. The G button runs online
 * Google Translate, then a batch read (the old Qwen grammar-polish step was removed:
 * it echoed prompts and mangled Google’s draft).
 */
final class PageTranslator {
    private static final String TAG = "PageTranslator";
    private static final int MAX_NODES = 400;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService IO =
            Executors.newSingleThreadExecutor();

    private static final String SKIP =
                    "/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT|CODE|SVG|CANVAS|NAV|HEADER|FOOTER)$/";

    private static final String PICK_ROOT_JS =
            "function pickRoot(){"
                    + "var sels=['#novel_honbun','.p-novel__text','.js-novel-text','.p-novel__body',"
                    + "'article','main','[role=main]','.c-novel__body'];"
                    + "var best=null,bestLen=0;"
                    + "for(var i=0;i<sels.length;i++){"
                    + "var n=document.querySelector(sels[i]);if(!n)continue;"
                    + "var len=(n.innerText||'').length;if(len>bestLen){best=n;bestLen=len;}}"
                    + "if(!best||bestLen<80)return document.body;"
                    + "return best;}";

    private static final String COLLECT_JS =
            "(function(){"
                    + PICK_ROOT_JS
                    + "var skip=" + SKIP + ";"
                    + "var out=[];"
                    + "var root=pickRoot();"
                    + "function walk(n){"
                    + "if(!n||out.length>=" + MAX_NODES + ")return;"
                    + "if(n.nodeType===3){var t=n.nodeValue;"
                    + "if(t&&t.replace(/\\s+/g,' ').trim().length>0)out.push(t);}"
                    + "else if(n.nodeType===1&&n.tagName&&!skip.test(n.tagName)){"
                    + "for(var c=n.firstChild;c;c=c.nextSibling)walk(c);}"
                    + "}"
                    + "walk(root);"
                    + "if(out.length<8&&document.body&&root!==document.body)walk(document.body);"
                    + "if(out.length===0&&document.body){"
                    + "var blob=(document.body.innerText||'');"
                    + "blob.split(/\\n+/).forEach(function(line){"
                    + "if(line.replace(/\\s+/g,' ').trim().length>1)out.push(line);});}"
                    + "return JSON.stringify(out);"
                    + "})()";

    private static final String COLLECT_ARTICLE_JS =
            "(function(){"
                    + PICK_ROOT_JS
                    + "var root=pickRoot();"
                    + "var t=(root&&root.innerText)||'';"
                    + "t=t.replace(/^\\s*(?:\\d+|One)\\s*\\/\\s*\\d+\\s*$/gmi,'');"
                    + "t=t.replace(/\\n{3,}/g,'\\n\\n').trim();"
                    + "return JSON.stringify(t);"
                    + "})()";

    interface Listener {
        void onStatus(String message);

        void onTranslatedPage(String english);

        default void onTranslateStarted(int total) {
        }

        default void onTranslateProgress(int done, int total) {
        }

        default void onTranslatePartial(String englishSoFar) {
        }

        /** Current English line being spoken / just translated. */
        default void onTranslateLine(String englishLine) {
        }

        default void onTranslateFinished() {
        }
    }

    private Listener listener;
    private volatile boolean busy;
    private volatile boolean cancelRequested;

    PageTranslator() {
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean isBusy() {
        return busy;
    }

    /** Stop an in-flight page Translate / Google job and clear the speak queue. */
    void cancel(Context context) {
        cancelRequested = true;
        busy = false;
        if (context != null) {
            PiperTtsEngine.get(context).stopSpeaking();
        }
        listenerDone();
    }

    void readPage(WebView web) {
        if (web == null) {
            return;
        }
        status("Reading page…");
        PiperTtsEngine.get(web.getContext()).ensureReadyAsync();
        runOnUi(web, () -> web.evaluateJavascript(COLLECT_JS, value -> {
            List<String> nodes = parseJsStringArray(value);
            if (nodes.isEmpty()) {
                status("No page text found");
                return;
            }
            speakJoined(nodes);
        }));
    }

    void translatePage(WebView web) {
        translatePage(web, true);
    }

    void translatePage(WebView web, boolean speakAlong) {
        if (web == null) {
            return;
        }
        if (busy) {
            status("Translation already running");
            return;
        }
        cancelRequested = false;
        busy = true;
        listenerStart(1);
        runOnUi(web, () -> web.evaluateJavascript(COLLECT_ARTICLE_JS, value -> {
            if (cancelRequested) {
                busy = false;
                listenerDone();
                return;
            }
            String article = parseJsString(value);
            List<String> units = nonEmptyUnits(article);
            if (units.isEmpty()) {
                busy = false;
                status("No page text found");
                listenerDone();
                return;
            }
            listenerStart(units.size());
            Log.i(TAG, "OPUS page lines " + units.size() + " speak=" + speakAlong);
            OnDeviceTranslator mt = OnDeviceTranslator.get(web.getContext());
            PiperTtsEngine piper = PiperTtsEngine.get(web.getContext());
            if (speakAlong) {
                piper.ensureReadyAsync();
                piper.stopSpeaking();
            }
            StringBuilder soFar = new StringBuilder();
            mt.translateList(units, englishLines -> {
                busy = false;
                if (cancelRequested) {
                    listenerDone();
                    return;
                }
                String joined = joinLines(englishLines);
                applyRootText(web, joined);
                listenerDone();
                if (joined.isEmpty()) {
                    status(mt.lastError() != null ? mt.lastError() : "Translation failed");
                }
            }, (done, total, eng) -> {
                if (cancelRequested) {
                    return;
                }
                listenerProgress(done, total);
                String clean = OnDeviceTranslator.cleanMtEnglish(eng);
                if (!clean.isEmpty()) {
                    if (soFar.length() > 0) {
                        soFar.append('\n');
                    }
                    soFar.append(clean);
                    if (listener != null) {
                        listener.onTranslateLine(clean);
                        listener.onTranslatePartial(soFar.toString());
                    }
                    if (speakAlong && !cancelRequested) {
                        piper.speak(clean);
                    }
                }
            });
        }));
    }

    /**
     * Online Google Translate → clean → apply EN + batch Piper read.
     * (The old Qwen 1.5B grammar-polish step was removed: it echoed prompts
     * and mangled Google's draft. QwenPageEngine/QwenPageService stripped.)
     */
    void googleTranslatePage(WebView web, boolean speakAfter) {
        if (web == null) {
            return;
        }
        if (busy) {
            status("Translation already running");
            return;
        }
        cancelRequested = false;
        busy = true;
        listenerStart(1);
        if (listener != null) {
            listener.onStatus(web.getContext().getString(
                    com.spatiallauncher.app.R.string.browser_google_waking));
        }
        runOnUi(web, () -> web.evaluateJavascript(COLLECT_ARTICLE_JS, value -> {
            String article = parseJsString(value);
            if (article == null || article.trim().isEmpty()) {
                MAIN.postDelayed(() -> web.evaluateJavascript(COLLECT_ARTICLE_JS, value2 -> {
                    String again = parseJsString(value2);
                    if (again == null || again.trim().isEmpty()) {
                        busy = false;
                        status("No page text found");
                        listenerDone();
                        return;
                    }
                    runGooglePipeline(web, again, speakAfter);
                }), 600L);
                return;
            }
            runGooglePipeline(web, article, speakAfter);
        }));
    }

    private void runGooglePipeline(WebView web, String article, boolean speakAfter) {
        List<String> preview = GoogleWebTranslate.chunkSource(article);
        listenerStart(Math.max(1, preview.size()));
        Log.i(TAG, "Google page units " + preview.size() + " speak=" + speakAfter);
        if (listener != null) {
            MAIN.post(() -> listener.onStatus(web.getContext().getString(
                    com.spatiallauncher.app.R.string.browser_google_translating)));
        }
        IO.execute(() -> {
            List<String> googleUnits = null;
            Exception lastFail = null;
            for (int pass = 1; pass <= 2; pass++) {
                try {
                    if (pass == 2) {
                        MAIN.post(() -> {
                            if (listener != null) {
                                listener.onStatus(web.getContext().getString(
                                        com.spatiallauncher.app.R.string.browser_google_retrying));
                            }
                            status(web.getContext().getString(
                                    com.spatiallauncher.app.R.string.browser_google_retrying));
                        });
                        try {
                            Thread.sleep(700L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    googleUnits = GoogleWebTranslate.translateUnits(article, (done, total) -> {
                        if (cancelRequested) {
                            return;
                        }
                        listenerProgress(done, total);
                        if (listener != null) {
                            MAIN.post(() -> listener.onTranslateLine(
                                    web.getContext().getString(
                                            com.spatiallauncher.app.R.string.browser_google_translating)
                                            + " " + done + "/" + total));
                        }
                    });
                    if (cancelRequested) {
                        busy = false;
                        MAIN.post(this::listenerDone);
                        return;
                    }
                    lastFail = null;
                    break;
                } catch (Exception e) {
                    lastFail = e;
                    Log.w(TAG, "Google Translate pass " + pass + " failed", e);
                }
            }
            if (lastFail != null || googleUnits == null) {
                busy = false;
                MAIN.post(() -> {
                    status(web.getContext().getString(
                            com.spatiallauncher.app.R.string.browser_google_failed));
                    listenerDone();
                });
                return;
            }
            ArrayList<String> units = new ArrayList<>();
            for (String u : googleUnits) {
                String clean = GoogleWebTranslate.cleanOutput(u);
                if (clean != null && !clean.isEmpty()
                        && !GoogleWebTranslate.looksLikePromptLeak(clean)) {
                    units.add(clean);
                } else if (clean != null && !clean.isEmpty()) {
                    // Strip leak lines; keep remainder if any.
                    String scrubbed = GoogleWebTranslate.stripPromptLeak(clean);
                    if (!scrubbed.isEmpty()) {
                        units.add(scrubbed);
                    }
                }
            }
            if (units.isEmpty()) {
                busy = false;
                MAIN.post(() -> {
                    status(web.getContext().getString(
                            com.spatiallauncher.app.R.string.browser_google_failed));
                    listenerDone();
                });
                return;
            }
            final String googleJoined = GoogleWebTranslate.joinUnits(units);
            Log.i(TAG, "Google-only units " + units.size() + " (polish skipped)");
            MAIN.post(() -> {
                PiperTtsEngine piper = PiperTtsEngine.get(web.getContext());
                if (speakAfter) {
                    piper.ensureReadyAsync();
                    piper.stopSpeaking();
                }
                finishGooglePage(web, googleJoined, speakAfter, piper);
            });
        });
    }

    private void finishGooglePage(WebView web, String english, boolean speakAfter, PiperTtsEngine piper) {
        busy = false;
        if (cancelRequested) {
            listenerDone();
            return;
        }
        String body = GoogleWebTranslate.cleanOutput(english);
        applyRootText(web, body);
        listenerDone();
        if (speakAfter && body != null && !body.isEmpty()) {
            List<String> lines = nonEmptyUnits(body);
            if (lines.isEmpty()) {
                piper.speak(body);
            } else {
                piper.speakAll(lines);
            }
        }
    }

    private static List<String> nonEmptyUnits(String article) {
        ArrayList<String> out = new ArrayList<>();
        for (String u : OnDeviceTranslator.splitForModel(article)) {
            if (u != null && !u.trim().isEmpty()) {
                out.add(u.trim());
            }
        }
        return out;
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private void applyRootText(WebView web, String english) {
        if (web == null) {
            return;
        }
        String body = english == null ? "" : english;
        String js = "(function(){" + PICK_ROOT_JS
                + "var root=pickRoot();if(!root)return;"
                + "root.innerText=" + JSONObject.quote(body) + ";})()";
        runOnUi(web, () -> web.evaluateJavascript(js, null));
    }

    static String parseJsString(String jsValue) {
        if (jsValue == null || "null".equals(jsValue)) {
            return "";
        }
        try {
            Object decoded = new org.json.JSONTokener(jsValue).nextValue();
            if (decoded instanceof String) {
                return (String) decoded;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private void speakJoined(List<String> lines) {
        StringBuilder spoken = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            if (spoken.length() > 0) {
                spoken.append('\n');
            }
            spoken.append(line.trim());
        }
        String page = spoken.toString();
        if (page.isEmpty()) {
            status("Nothing to read");
            return;
        }
        Listener sink = listener;
        if (sink != null) {
            sink.onTranslatedPage(page);
        }
    }

    static List<String> parseJsStringArray(String jsValue) {
        ArrayList<String> out = new ArrayList<>();
        if (jsValue == null || "null".equals(jsValue)) {
            return out;
        }
        try {
            Object decoded = new org.json.JSONTokener(jsValue).nextValue();
            JSONArray arr;
            if (decoded instanceof JSONArray) {
                arr = (JSONArray) decoded;
            } else if (decoded instanceof String) {
                arr = new JSONArray((String) decoded);
            } else {
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.optString(i, ""));
            }
        } catch (Exception e) {
            Log.w(TAG, "parse collect failed", e);
        }
        return out;
    }

    private void status(String message) {
        Listener sink = listener;
        if (sink != null) {
            sink.onStatus(message);
        }
    }

    private void listenerStart(int total) {
        Listener sink = listener;
        if (sink != null) {
            sink.onTranslateStarted(total);
        }
    }

    private void listenerProgress(int done, int total) {
        Listener sink = listener;
        if (sink != null) {
            sink.onTranslateProgress(done, total);
        }
    }

    private void listenerDone() {
        Listener sink = listener;
        if (sink != null) {
            sink.onTranslateFinished();
        }
    }

    private static void runOnUi(WebView web, Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        MAIN.post(action);
    }
}
