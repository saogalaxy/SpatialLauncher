package com.spatiallauncher.app.ui;

import android.util.Log;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Chrome-style page translate for the in-app WebView: walk text nodes, MT, write back.
 * Then optional Piper read of the English.
 */
final class PageTranslator {
    private static final String TAG = "PageTranslator";
    private static final int MAX_NODES = 500;

    private static final String COLLECT_JS =
            "(function(){"
                    + "var skip=/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT|CODE|PRE|SVG)$/;"
                    + "var out=[];"
                    + "function walk(n){"
                    + "if(!n||out.length>=" + MAX_NODES + ")return;"
                    + "if(n.nodeType===3){var t=n.nodeValue;if(t&&t.trim().length>1)out.push(t);}"
                    + "else if(n.nodeType===1&&n.tagName&&!skip.test(n.tagName)){"
                    + "for(var c=n.firstChild;c;c=c.nextSibling)walk(c);}"
                    + "}"
                    + "walk(document.body);return JSON.stringify(out);"
                    + "})()";

    interface Listener {
        void onStatus(String message);

        void onTranslatedPage(String english);
    }

    private final OnDeviceTranslator translator;
    private Listener listener;

    PageTranslator(OnDeviceTranslator translator) {
        this.translator = translator;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void translatePage(WebView web, boolean thenRead) {
        if (web == null) {
            return;
        }
        status("Translating page…");
        translator.ensureReady(ok -> web.evaluateJavascript(COLLECT_JS, value -> {
            List<String> nodes = parseJsStringArray(value);
            if (nodes.isEmpty()) {
                status("No page text to translate");
                return;
            }
            translator.translateList(nodes, english -> {
                applyMap(web, nodes, english);
                StringBuilder spoken = new StringBuilder();
                for (String line : english) {
                    if (line == null || line.trim().isEmpty()) {
                        continue;
                    }
                    if (spoken.length() > 0) {
                        spoken.append('\n');
                    }
                    spoken.append(line.trim());
                }
                String page = spoken.toString();
                status("Page translated");
                Listener sink = listener;
                if (sink != null && thenRead) {
                    sink.onTranslatedPage(page);
                }
            });
        }));
    }

    private void applyMap(WebView web, List<String> original, List<String> english) {
        try {
            JSONObject map = new JSONObject();
            int n = Math.min(original.size(), english.size());
            for (int i = 0; i < n; i++) {
                String src = original.get(i);
                String dst = english.get(i);
                if (src != null && dst != null && !src.equals(dst)) {
                    map.put(src, dst);
                }
            }
            if (map.length() == 0) {
                return;
            }
            String js = "(function(){var m=JSON.parse(" + JSONObject.quote(map.toString()) + ");"
                    + "var skip=/^(SCRIPT|STYLE|NOSCRIPT|TEXTAREA|INPUT|CODE|PRE|SVG)$/;"
                    + "function walk(n){"
                    + "if(!n)return;"
                    + "if(n.nodeType===3){if(Object.prototype.hasOwnProperty.call(m,n.nodeValue))"
                    + "n.nodeValue=m[n.nodeValue];}"
                    + "else if(n.nodeType===1&&n.tagName&&!skip.test(n.tagName)){"
                    + "for(var c=n.firstChild;c;c=c.nextSibling)walk(c);}"
                    + "}"
                    + "walk(document.body);})()";
            web.evaluateJavascript(js, null);
        } catch (Exception e) {
            Log.w(TAG, "apply map failed", e);
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
}
