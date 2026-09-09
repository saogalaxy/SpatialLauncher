package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persists the set of package names the user has pinned to the dock ("added a game"),
 * in the order they were added. This is intentionally just package names — the actual
 * label/icon/launch-intent are re-resolved from PackageManager each time (via
 * PanelMainActivity.resolveInstalledApp()), so nothing goes stale if the user
 * updates/reinstalls one of their pinned apps.
 */
public class GameLibraryStore {

    private static final String PREFS_NAME = "spatial_launcher_dock";
    private static final String KEY_PINNED_PACKAGES = "pinned_packages";
    private static final String DELIMITER = "\n";

    private final SharedPreferences prefs;

    public GameLibraryStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Ordered set of pinned package names (insertion order == dock display order). */
    public Set<String> getPinnedPackages() {
        String raw = prefs.getString(KEY_PINNED_PACKAGES, "");
        Set<String> result = new LinkedHashSet<>();
        if (!raw.isEmpty()) {
            for (String packageName : raw.split(DELIMITER)) {
                if (!packageName.isEmpty()) {
                    result.add(packageName);
                }
            }
        }
        return result;
    }

    public void addPinnedPackage(String packageName) {
        Set<String> current = getPinnedPackages();
        current.add(packageName);
        save(current);
    }

    public void removePinnedPackage(String packageName) {
        Set<String> current = getPinnedPackages();
        current.remove(packageName);
        save(current);
    }

    private void save(Set<String> packages) {
        prefs.edit().putString(KEY_PINNED_PACKAGES, String.join(DELIMITER, packages)).apply();
    }
}
