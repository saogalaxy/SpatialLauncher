package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Durable on-headset shelf of EPUB files under {@code filesDir/epub/inbox}.
 */
final class EpubLibraryStore {
    private static final String PREFS = "spatial_launcher_epubs";
    private static final String KEY_BOOKS = "books_json";

    static final class BookEntry {
        final String path;
        String title;
        long lastOpenedMs;
        final long addedMs;

        BookEntry(String path, String title, long lastOpenedMs, long addedMs) {
            this.path = path;
            this.title = title == null || title.trim().isEmpty() ? displayNameFromPath(path) : title.trim();
            this.lastOpenedMs = lastOpenedMs;
            this.addedMs = addedMs;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final SharedPreferences prefs;
    private final File inboxDir;

    EpubLibraryStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        inboxDir = new File(app.getFilesDir(), "epub/inbox");
        //noinspection ResultOfMethodCallIgnored
        inboxDir.mkdirs();
    }

    File getInboxDir() {
        return inboxDir;
    }

    List<BookEntry> list(boolean sortByTitle) {
        List<BookEntry> books = load();
        books.removeIf(b -> b.path == null || !new File(b.path).isFile());
        if (sortByTitle) {
            Collections.sort(books, (a, b) -> a.title.compareToIgnoreCase(b.title));
        } else {
            Collections.sort(books, (a, b) -> Long.compare(b.lastOpenedMs, a.lastOpenedMs));
        }
        return books;
    }

    /** Index any .epub files in inbox that are not in the store yet. */
    void scanInbox() {
        File[] files = inboxDir.listFiles((dir, name) -> name != null && name.toLowerCase(Locale.US).endsWith(".epub"));
        if (files == null) {
            return;
        }
        List<BookEntry> books = load();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String path = file.getAbsolutePath();
            if (findIndex(books, path) >= 0) {
                continue;
            }
            books.add(new BookEntry(path, displayNameFromPath(path), now, now));
            changed = true;
        }
        if (changed) {
            save(books);
        }
    }

    BookEntry upsert(File file, String title) {
        if (file == null) {
            return null;
        }
        String path = file.getAbsolutePath();
        List<BookEntry> books = load();
        long now = System.currentTimeMillis();
        int idx = findIndex(books, path);
        BookEntry entry;
        if (idx >= 0) {
            entry = books.get(idx);
            if (title != null && !title.trim().isEmpty()) {
                entry.title = title.trim();
            }
            entry.lastOpenedMs = now;
            books.remove(idx);
            books.add(0, entry);
        } else {
            entry = new BookEntry(
                    path,
                    title != null && !title.trim().isEmpty() ? title : displayNameFromPath(path),
                    now,
                    now);
            books.add(0, entry);
        }
        save(books);
        return entry;
    }

    void markOpened(String path, String title) {
        if (path == null || path.isEmpty()) {
            return;
        }
        List<BookEntry> books = load();
        int idx = findIndex(books, path);
        long now = System.currentTimeMillis();
        if (idx < 0) {
            books.add(0, new BookEntry(path, title, now, now));
        } else {
            BookEntry entry = books.get(idx);
            if (title != null && !title.trim().isEmpty()) {
                entry.title = title.trim();
            }
            entry.lastOpenedMs = now;
            books.remove(idx);
            books.add(0, entry);
        }
        save(books);
    }

    void updateTitle(String path, String title) {
        if (path == null || title == null || title.trim().isEmpty()) {
            return;
        }
        List<BookEntry> books = load();
        int idx = findIndex(books, path);
        if (idx < 0) {
            return;
        }
        books.get(idx).title = title.trim();
        save(books);
    }

    private static int findIndex(List<BookEntry> books, String path) {
        for (int i = 0; i < books.size(); i++) {
            if (path.equals(books.get(i).path)) {
                return i;
            }
        }
        return -1;
    }

    private List<BookEntry> load() {
        List<BookEntry> out = new ArrayList<>();
        String raw = prefs.getString(KEY_BOOKS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String path = o.optString("path", "");
                if (path.isEmpty()) {
                    continue;
                }
                out.add(new BookEntry(
                        path,
                        o.optString("title", displayNameFromPath(path)),
                        o.optLong("lastOpenedMs", 0L),
                        o.optLong("addedMs", 0L)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void save(List<BookEntry> books) {
        JSONArray arr = new JSONArray();
        try {
            for (BookEntry b : books) {
                JSONObject o = new JSONObject();
                o.put("path", b.path);
                o.put("title", b.title);
                o.put("lastOpenedMs", b.lastOpenedMs);
                o.put("addedMs", b.addedMs);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_BOOKS, arr.toString()).apply();
    }

    static String displayNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "Book";
        }
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.toLowerCase(Locale.US).endsWith(".epub")) {
            name = name.substring(0, name.length() - 5);
        }
        return name.isEmpty() ? "Book" : name;
    }
}
