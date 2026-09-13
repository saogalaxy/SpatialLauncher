package com.spatiallauncher.app.ui;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTPS fetch for optional models / online helpers the APK does not ship. */
final class OptionalHttp {
    private static final String TAG = "OptionalHttp";

    static void download(String url, File dest, long minBytes) throws Exception {
        if (dest.isFile() && dest.length() >= minBytes) {
            return;
        }
        dest.getParentFile().mkdirs();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        Log.i(TAG, "GET " + url);
        byte[] body = getBytes(url, 300_000);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        }
        if (!tmp.isFile() || tmp.length() < minBytes) {
            tmp.delete();
            throw new IllegalStateException("download too small: " + url);
        }
        if (dest.exists()) {
            dest.delete();
        }
        if (!tmp.renameTo(dest)) {
            throw new IllegalStateException("could not move " + dest.getName());
        }
    }

    static String getUtf8(String url, int readTimeoutMs) throws Exception {
        return new String(getBytes(url, readTimeoutMs), StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String url, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(Math.max(5_000, readTimeoutMs));
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) SpatialLauncher");
        conn.setRequestProperty("Accept", "*/*");
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                throw new IllegalStateException("HTTP " + code + " empty body");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
            }
            in.close();
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code + ": " + out.toString("UTF-8"));
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private OptionalHttp() {
    }
}
