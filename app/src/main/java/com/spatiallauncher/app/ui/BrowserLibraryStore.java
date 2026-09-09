package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Last page, visit history, and bookmarks for the in-panel WebView.
 */
final class BrowserLibraryStore {

    private static final String PREFS = "spatial_launcher_browser";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_LAST_TITLE = "last_title";
    private static final String KEY_HISTORY = "history_json";
    private static final String KEY_BOOKMARKS = "bookmarks_json";
    private static final int MAX_HISTORY = 80;
    static final String HOME_URL = "https://www.google.com";

    static final class PageEntry {
        final String url;
        final String title;

        PageEntry(String url, String title) {
            this.url = url;
            this.title = title == null || title.trim().isEmpty() ? url : title.trim();
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final SharedPreferences prefs;

    BrowserLibraryStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getLastUrlOrHome() {
        String last = prefs.getString(KEY_LAST_URL, "");
        if (last == null || last.isEmpty() || "about:blank".equals(last)) {
            return HOME_URL;
        }
        return last;
    }

    void rememberVisit(String url, String title) {
        if (url == null || url.isEmpty() || "about:blank".equals(url)) {
            return;
        }
        prefs.edit()
                .putString(KEY_LAST_URL, url)
                .putString(KEY_LAST_TITLE, title == null ? "" : title)
                .apply();
        List<PageEntry> history = getHistory();
        history.removeIf(e -> url.equals(e.url));
        history.add(0, new PageEntry(url, title));
        if (history.size() > MAX_HISTORY) {
            history = new ArrayList<>(history.subList(0, MAX_HISTORY));
        }
        saveList(KEY_HISTORY, history);
    }

    List<PageEntry> getHistory() {
        return loadList(KEY_HISTORY);
    }

    List<PageEntry> getBookmarks() {
        return loadList(KEY_BOOKMARKS);
    }

    boolean isBookmarked(String url) {
        if (url == null) {
            return false;
        }
        for (PageEntry e : getBookmarks()) {
            if (url.equals(e.url)) {
                return true;
            }
        }
        return false;
    }

    void toggleBookmark(String url, String title) {
        if (url == null || url.isEmpty() || "about:blank".equals(url)) {
            return;
        }
        List<PageEntry> marks = getBookmarks();
        boolean removed = marks.removeIf(e -> url.equals(e.url));
        if (!removed) {
            marks.add(0, new PageEntry(url, title));
        }
        saveList(KEY_BOOKMARKS, marks);
    }

    private List<PageEntry> loadList(String key) {
        List<PageEntry> out = new ArrayList<>();
        String raw = prefs.getString(key, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url", "");
                if (!url.isEmpty()) {
                    out.add(new PageEntry(url, o.optString("title", url)));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveList(String key, List<PageEntry> entries) {
        JSONArray arr = new JSONArray();
        try {
            for (PageEntry e : entries) {
                JSONObject o = new JSONObject();
                o.put("url", e.url);
                o.put("title", e.title);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(key, arr.toString()).apply();
    }
}
