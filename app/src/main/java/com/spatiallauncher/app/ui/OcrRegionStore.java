package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persists user-defined OCR regions (normalized 0–1 rects).
 * Empty list means “use the built-in subtitle band.”
 */
final class OcrRegionStore {
    private static final String PREFS = "spatial_launcher_ocr_regions";
    private static final String KEY_REGIONS = "regions_json";
    private static final String KEY_CUSTOM = "has_custom";
    private static final String KEY_ASSIGN = "package_slots";
    private static final String KEY_APP_LAYOUTS = "app_layouts_json";
    static final int MAX_REGIONS = 6;
    static final int PRESET_COUNT = 4;
    static final String BROWSER_KEY = "browser";

    private final SharedPreferences prefs;

    OcrRegionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean hasCustomRegions() {
        return prefs.getBoolean(KEY_CUSTOM, false);
    }

    List<OcrRegion> load() {
        if (!hasCustomRegions()) {
            return Collections.emptyList();
        }
        String raw = prefs.getString(KEY_REGIONS, "[]");
        return parse(raw);
    }

    void save(List<OcrRegion> regions) {
        prefs.edit()
                .putBoolean(KEY_CUSTOM, true)
                .putString(KEY_REGIONS, toJson(regions))
                .apply();
    }

    void clearCustom() {
        prefs.edit()
                .putBoolean(KEY_CUSTOM, false)
                .putString(KEY_REGIONS, "[]")
                .apply();
    }

    boolean hasPreset(int slot) {
        return prefs.contains(presetKey(slot));
    }

    List<OcrRegion> loadPreset(int slot) {
        String raw = prefs.getString(presetKey(slot), null);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        return parse(raw);
    }

    void savePreset(int slot, List<OcrRegion> regions) {
        prefs.edit().putString(presetKey(slot), toJson(regions)).apply();
    }

    void assignToKey(String key, int slot) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_ASSIGN, "{}"));
            o.put(key, slot);
            prefs.edit().putString(KEY_ASSIGN, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** @return 0 = default band, 1–4 = preset, -1 = none */
    int assignedSlot(String key) {
        if (key == null) {
            return -1;
        }
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_ASSIGN, "{}"));
            if (!o.has(key)) {
                return -1;
            }
            return o.getInt(key);
        } catch (Exception e) {
            return -1;
        }
    }

    void applySlot(int slot, ScreenFrameCapture capture) {
        if (slot <= 0) {
            clearCustom();
            capture.setOcrRegions(Collections.emptyList());
            return;
        }
        List<OcrRegion> regions = loadPreset(slot);
        if (regions.isEmpty()) {
            return;
        }
        save(regions);
        capture.setOcrRegions(regions);
    }

    void saveForApp(String key, String label, List<OcrRegion> regions) {
        if (key == null || key.isEmpty()) {
            return;
        }
        JSONObject root = layoutsRoot();
        try {
            JSONObject entry = new JSONObject();
            entry.put("n", label != null ? label : key);
            entry.put("r", new JSONArray(toJson(regions)));
            root.put(key, entry);
            prefs.edit().putString(KEY_APP_LAYOUTS, root.toString()).apply();
            save(regions);
        } catch (Exception ignored) {
        }
    }

    List<OcrRegion> loadForApp(String key) {
        if (key == null) {
            return Collections.emptyList();
        }
        try {
            JSONObject root = layoutsRoot();
            if (!root.has(key)) {
                return Collections.emptyList();
            }
            JSONObject entry = root.getJSONObject(key);
            Object rawRegions = entry.get("r");
            return parse(rawRegions.toString());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    boolean hasAppLayout(String key) {
        return key != null && layoutsRoot().has(key);
    }

    String labelForApp(String key) {
        try {
            JSONObject root = layoutsRoot();
            if (!root.has(key)) {
                return key;
            }
            return root.getJSONObject(key).optString("n", key);
        } catch (Exception e) {
            return key;
        }
    }

    List<String> savedAppKeys() {
        ArrayList<String> keys = new ArrayList<>();
        JSONObject root = layoutsRoot();
        JSONArray names = root.names();
        if (names == null) {
            return keys;
        }
        for (int i = 0; i < names.length(); i++) {
            try {
                keys.add(names.getString(i));
            } catch (Exception ignored) {
            }
        }
        return keys;
    }

    private JSONObject layoutsRoot() {
        try {
            return new JSONObject(prefs.getString(KEY_APP_LAYOUTS, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String presetKey(int slot) {
        return "preset_" + Math.max(1, Math.min(PRESET_COUNT, slot));
    }

    private static String toJson(List<OcrRegion> regions) {
        JSONArray arr = new JSONArray();
        if (regions == null) {
            return arr.toString();
        }
        for (int i = 0; i < regions.size() && i < MAX_REGIONS; i++) {
            OcrRegion r = regions.get(i);
            if (r == null) {
                continue;
            }
            r.normalize();
            try {
                JSONObject o = new JSONObject();
                o.put("l", r.left);
                o.put("t", r.top);
                o.put("r", r.right);
                o.put("b", r.bottom);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    private static List<OcrRegion> parse(String raw) {
        ArrayList<OcrRegion> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length() && out.size() < MAX_REGIONS; i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new OcrRegion(
                        (float) o.getDouble("l"),
                        (float) o.getDouble("t"),
                        (float) o.getDouble("r"),
                        (float) o.getDouble("b")));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return out;
    }
}
