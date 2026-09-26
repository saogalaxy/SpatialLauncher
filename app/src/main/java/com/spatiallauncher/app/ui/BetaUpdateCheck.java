package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.spatiallauncher.app.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Beta-lane only: notice when a newer beta build is published on GitHub.
 *
 * There is no in-app installer. The store release deliberately has no update
 * path (users get builds from the Store), and installing an APK in place would
 * need REQUEST_INSTALL_PACKAGES, so the notice links out to the download
 * instead. The check is a no-op unless this package is the .beta lane, is
 * rate-limited to one probe per CHECK_INTERVAL_MS, and never blocks startup.
 */
final class BetaUpdateCheck {

    private static final String TAG = "BetaUpdate";
    private static final String PREFS = "beta_update";
    private static final String KEY_LAST_CHECK = "last_check_ms";

    /** Rolling release that always carries the newest beta APK. */
    private static final String RELEASE_TAG = "beta";
    private static final String RELEASE_API =
            "https://api.github.com/repos/saogalaxy/SpatialLauncher/releases/tags/" + RELEASE_TAG;
    private static final String RELEASE_PAGE =
            "https://github.com/saogalaxy/SpatialLauncher/releases/tag/" + RELEASE_TAG;

    /** Published asset is named SpatialLauncher-beta-<build>.apk. */
    private static final Pattern ASSET_BUILD =
            Pattern.compile("SpatialLauncher-beta-([0-9A-Za-z._-]+?)\\.apk");
    private static final long CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 8000;
    private static volatile boolean running;

    private BetaUpdateCheck() {
    }

    /** Safe to call on every panel start; returns immediately when not beta. */
    static void maybeCheck(Context context) {
        if (context == null || !isBetaLane(context)) {
            return;
        }
        if (running) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK, 0L);
        if (now - last < CHECK_INTERVAL_MS) {
            return;
        }
        // Record the attempt up front so a failure cannot cause a probe per start.
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply();
        running = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                check(app);
            } catch (Throwable t) {
                Log.w(TAG, "beta update check failed", t);
            } finally {
                running = false;
            }
        }, "BetaUpdateCheck").start();
    }

    private static boolean isBetaLane(Context context) {
        return context.getPackageName().endsWith(".beta");
    }

    private static void check(Context app) {
        String body = fetch(RELEASE_API);
        if (body == null) {
            return;
        }
        String remote = extractRemoteBuild(body);
        String local = BuildConfig.BETA_BUILD;
        if (remote == null || remote.isEmpty() || local == null || local.isEmpty()) {
            return;
        }
        if (isSameOrOlder(remote, local)) {
            Log.i(TAG, "beta is current (" + local + ")");
            return;
        }
        Log.i(TAG, "new beta available: " + local + " -> " + remote);
        PanelAlerts.show(app, app.getString(
                com.spatiallauncher.app.R.string.beta_update_available, local, remote)
                + "  " + RELEASE_PAGE);
    }

    private static String fetch(String endpoint) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endpoint).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "SpatialLauncher-BetaUpdateCheck");
            if (c.getResponseCode() != 200) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "fetch failed", e);
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /**
     * Pull the build marker out of the published asset names. A rolling release
     * can briefly hold more than one asset while a new upload replaces the old,
     * so take the highest marker rather than the first match.
     */
    private static String extractRemoteBuild(String json) {
        Matcher m = ASSET_BUILD.matcher(json);
        String best = null;
        long bestValue = Long.MIN_VALUE;
        while (m.find()) {
            String candidate = m.group(1);
            try {
                long value = Long.parseLong(candidate.trim());
                if (value > bestValue) {
                    bestValue = value;
                    best = candidate;
                }
            } catch (NumberFormatException ignored) {
                if (best == null) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Compare build markers. Plain integers, so ordering is trivial. A local dev
     * build (marker "local") is always treated as older than a published one, so
     * a real build is never suppressed.
     */
    static boolean isSameOrOlder(String remote, String local) {
        if (remote.equals(local)) {
            return true;
        }
        try {
            return Long.parseLong(remote.trim()) <= Long.parseLong(local.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Opens the rolling beta release in the system browser. */
    static void openReleasePage(Context context) {
        try {
            context.startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "cannot open release page", e);
        }
    }
}
