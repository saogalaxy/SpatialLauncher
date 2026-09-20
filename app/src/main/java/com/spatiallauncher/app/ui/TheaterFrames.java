package com.spatiallauncher.app.ui;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Spike-only frame tap for the WebXR theater prototype (branch
 * webxr-theater-spike, never main): holds the latest stereo output frame so
 * {@link TheaterHttp} can MJPEG-stream it to the Quest Browser.
 */
final class TheaterFrames {
    private static final String TAG = "TheaterFrames";
    private static final Object LOCK = new Object();
    private static final long MIN_INTERVAL_MS = 100;
    private static final int MAX_WIDTH = 960;

    private static Bitmap latest;
    private static long lastOfferMs;

    /** Keeps a downscaled copy at most every 100 ms. Cheap enough to call per frame. */
    static void offer(Bitmap frame) {
        if (frame == null || frame.isRecycled()
                || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - lastOfferMs < MIN_INTERVAL_MS) {
                return;
            }
            lastOfferMs = now;
            try {
                int w = Math.min(MAX_WIDTH, frame.getWidth());
                int h = Math.max(1, Math.round(frame.getHeight() * (w / (float) frame.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(frame, w, h, true);
                if (latest != null && !latest.isRecycled()) {
                    latest.recycle();
                }
                latest = scaled;
            } catch (Throwable t) {
                Log.w(TAG, "offer failed", t);
            }
        }
    }

    /** JPEG-encodes the latest frame. Null when nothing has been offered yet. */
    static byte[] latestJpeg() {
        synchronized (LOCK) {
            if (latest == null || latest.isRecycled()) {
                return null;
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
                latest.compress(Bitmap.CompressFormat.JPEG, 80, out);
                return out.toByteArray();
            } catch (Throwable t) {
                Log.w(TAG, "jpeg failed", t);
                return null;
            }
        }
    }

    static void clear() {
        synchronized (LOCK) {
            if (latest != null && !latest.isRecycled()) {
                latest.recycle();
            }
            latest = null;
            lastOfferMs = 0;
        }
    }

    private TheaterFrames() {
    }
}
