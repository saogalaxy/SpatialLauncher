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
 * Durable on-headset shelf of video files under {@code filesDir/video/inbox}.
 * Mirrors {@link EpubLibraryStore}: picker imports are copied in; My Videos lists
 * them; tapping one opens ExoPlayer in the main panel.
 */
final class VideoLibraryStore {
    private static final String PREFS = "spatial_launcher_videos";
    private static final String KEY_VIDEOS = "videos_json";

    private static final String[] VIDEO_EXT = {
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".3gp", ".ts",
    };

    static final class VideoEntry {
        final String path;
        String title;
        long lastOpenedMs;
        final long addedMs;

        VideoEntry(String path, String title, long lastOpenedMs, long addedMs) {
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

    VideoLibraryStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        inboxDir = new File(app.getFilesDir(), "video/inbox");
        //noinspection ResultOfMethodCallIgnored
        inboxDir.mkdirs();
    }

    File getInboxDir() {
        return inboxDir;
    }

    static boolean isVideoName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        for (String ext : VIDEO_EXT) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    List<VideoEntry> list(boolean sortByTitle) {
        List<VideoEntry> videos = load();
        videos.removeIf(v -> {
            if (v.path == null) {
                return true;
            }
            File f = new File(v.path);
            return !f.isFile() || f.length() <= 0;
        });
        if (sortByTitle) {
            Collections.sort(videos, (a, b) -> a.title.compareToIgnoreCase(b.title));
        } else {
            Collections.sort(videos, (a, b) -> Long.compare(b.lastOpenedMs, a.lastOpenedMs));
        }
        return videos;
    }

    /** Index any video files in inbox that are not in the store yet. */
    void scanInbox() {
        File[] files = inboxDir.listFiles((dir, name) -> isVideoName(name));
        if (files == null) {
            return;
        }
        List<VideoEntry> videos = load();
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (File file : files) {
            String path = file.getAbsolutePath();
            if (findIndex(videos, path) >= 0) {
                continue;
            }
            videos.add(new VideoEntry(path, displayNameFromPath(path), now, now));
            changed = true;
        }
        if (changed) {
            save(videos);
        }
    }

    VideoEntry upsert(File file, String title) {
        if (file == null) {
            return null;
        }
        String path = file.getAbsolutePath();
        List<VideoEntry> videos = load();
        long now = System.currentTimeMillis();
        int idx = findIndex(videos, path);
        VideoEntry entry;
        if (idx >= 0) {
            entry = videos.get(idx);
            if (title != null && !title.trim().isEmpty()) {
                entry.title = title.trim();
            }
            entry.lastOpenedMs = now;
            videos.remove(idx);
            videos.add(0, entry);
        } else {
            entry = new VideoEntry(
                    path,
                    title != null && !title.trim().isEmpty() ? title : displayNameFromPath(path),
                    now,
                    now);
            videos.add(0, entry);
        }
        save(videos);
        return entry;
    }

    /** Delete the file and drop its entry. Returns true when gone. */
    boolean delete(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        List<VideoEntry> videos = load();
        int idx = findIndex(videos, path);
        if (idx >= 0) {
            videos.remove(idx);
            save(videos);
        }
        boolean gone = true;
        try {
            File f = new File(path);
            if (f.isFile()) {
                gone = f.delete();
            }
        } catch (Throwable ignored) {
            gone = false;
        }
        return gone;
    }

    void markOpened(String path, String title) {
        if (path == null || path.isEmpty()) {
            return;
        }
        List<VideoEntry> videos = load();
        int idx = findIndex(videos, path);
        long now = System.currentTimeMillis();
        if (idx < 0) {
            videos.add(0, new VideoEntry(path, title, now, now));
        } else {
            VideoEntry entry = videos.get(idx);
            if (title != null && !title.trim().isEmpty()) {
                entry.title = title.trim();
            }
            entry.lastOpenedMs = now;
            videos.remove(idx);
            videos.add(0, entry);
        }
        save(videos);
    }

    private static int findIndex(List<VideoEntry> videos, String path) {
        for (int i = 0; i < videos.size(); i++) {
            if (path.equals(videos.get(i).path)) {
                return i;
            }
        }
        return -1;
    }

    private List<VideoEntry> load() {
        List<VideoEntry> out = new ArrayList<>();
        String raw = prefs.getString(KEY_VIDEOS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String path = o.optString("path", "");
                if (path.isEmpty()) {
                    continue;
                }
                out.add(new VideoEntry(
                        path,
                        o.optString("title", displayNameFromPath(path)),
                        o.optLong("lastOpenedMs", 0L),
                        o.optLong("addedMs", 0L)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void save(List<VideoEntry> videos) {
        JSONArray arr = new JSONArray();
        try {
            for (VideoEntry v : videos) {
                JSONObject o = new JSONObject();
                o.put("path", v.path);
                o.put("title", v.title);
                o.put("lastOpenedMs", v.lastOpenedMs);
                o.put("addedMs", v.addedMs);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_VIDEOS, arr.toString()).apply();
    }

    static String displayNameFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return "Video";
        }
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && isVideoName(name)) {
            name = name.substring(0, dot);
        }
        return name.isEmpty() ? "Video" : name;
    }
}
