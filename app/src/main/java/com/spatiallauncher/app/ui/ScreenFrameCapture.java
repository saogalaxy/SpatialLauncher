package com.spatiallauncher.app.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 1 — screen frame capture on Android / Meta Quest.
 * <p>
 * Uses the existing MediaProjection VirtualDisplay + ImageReader (foreground
 * {@link MirrorCaptureService}) or a WebView snapshot when that is the local
 * stream. Snapshots are kept when (a) the periodic interval elapses, (b) the
 * downsampled buffer actually changed, or (c) a tap was detected.
 * Even at ~5 FPS a dialogue box can sit unchanged for seconds — {@link DialogueDeduper}
 * drops those repeats before TTS.
 */
final class ScreenFrameCapture {
    static final long OPTION_A_PERIODIC_MS = 1500L;
    /** Option B OCR is 20–50ms; VR still caps snapshots (see {@link #VR_READER_INTERVAL_MS}). */
    static final long OPTION_B_PERIODIC_MS = 50L;
    /** ~5 FPS ceiling on Quest so reader + stereo + MiDaS stay thermally sane. */
    static final long VR_READER_INTERVAL_MS = 200L;
    static final long TAP_MIN_INTERVAL_MS = 120L;
    static final float DIALOGUE_ROI_TOP_FRACTION = 0.52f;
    static final float DIALOGUE_ROI_BOTTOM_FRACTION = 0.88f;
    static final float DIALOGUE_ROI_SIDE_FRACTION = 0.08f;

    private volatile long periodicIntervalMs = OPTION_A_PERIODIC_MS;

    private volatile Bitmap latestFrame;
    /** Uncropped window capture, updated before 3D compose. */
    private volatile Bitmap latestSource;
    private long lastAcceptElapsedMs;
    private int lastFingerprint = Integer.MIN_VALUE;
    private volatile boolean captureBecauseTap;
    private volatile SnapshotListener snapshotListener;
    private final Object regionLock = new Object();
    private ArrayList<OcrRegion> customRegions = new ArrayList<>();

    interface SnapshotListener {
        /**
         * @param allowHeavyVision false for periodic ticks so a VLM does not run
         *     continuously beside the game (frame drops). True on tap or visual change.
         */
        void onScreenSnapshot(Bitmap frame, boolean allowHeavyVision);
    }

    void setSnapshotListener(SnapshotListener listener) {
        snapshotListener = listener;
    }

    void setPeriodicIntervalMs(long intervalMs) {
        periodicIntervalMs = Math.max(20L, intervalMs);
    }

    /** Empty list → built-in subtitle band. Otherwise crop/stack these normalized rects. */
    void setOcrRegions(List<OcrRegion> regions) {
        synchronized (regionLock) {
            customRegions = new ArrayList<>();
            if (regions != null) {
                for (OcrRegion r : regions) {
                    if (r != null) {
                        customRegions.add(r.copy());
                    }
                }
            }
        }
    }

    private volatile int insetViewW;
    private volatile int insetViewH;
    private volatile int insetLeft;
    private volatile int insetTop;
    private volatile int insetRight;
    private volatile int insetBottom;

    /**
     * System insets & UI cropping: map status/nav (and similar) from the host view
     * onto the snapshot so OCR/VLM does not read chrome around the game.
     */
    void setSystemInsets(int viewWidth, int viewHeight, int left, int top, int right, int bottom) {
        insetViewW = viewWidth;
        insetViewH = viewHeight;
        insetLeft = Math.max(0, left);
        insetTop = Math.max(0, top);
        insetRight = Math.max(0, right);
        insetBottom = Math.max(0, bottom);
    }

    void requestCaptureOnTap() {
        captureBecauseTap = true;
    }

    void offerFromScreenBuffer(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        boolean tap = captureBecauseTap;
        if (tap) {
            captureBecauseTap = false;
            if (now - lastAcceptElapsedMs < TAP_MIN_INTERVAL_MS) {
                return;
            }
            accept(frame, now, true);
            return;
        }
        int fingerprint = fingerprint(frame);
        boolean visualChange = fingerprint != lastFingerprint;
        boolean periodic = now - lastAcceptElapsedMs >= periodicIntervalMs;
        if (!visualChange && !periodic) {
            return;
        }
        lastFingerprint = fingerprint;
        accept(frame, now, visualChange);
    }

    Bitmap peek() {
        Bitmap source = latestSource;
        if (source != null && !source.isRecycled()) {
            return source;
        }
        return latestFrame;
    }

    /**
     * Keep the latest pre-stereo capture so tap-to-speak OCRs the live window,
     * not a frame that waited on the 3D mesh.
     */
    void retainSourceForTap(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return;
        }
        Bitmap copy;
        try {
            copy = frame.copy(
                    frame.getConfig() != null ? frame.getConfig() : Bitmap.Config.ARGB_8888,
                    false);
        } catch (RuntimeException e) {
            return;
        }
        if (copy == null) {
            return;
        }
        Bitmap previous = latestSource;
        latestSource = copy;
        if (previous != null && previous != copy) {
            previous.recycle();
        }
    }

    Bitmap copyLatestSource() {
        Bitmap source = latestSource;
        if (source == null || source.isRecycled()) {
            return null;
        }
        try {
            return source.copy(
                    source.getConfig() != null ? source.getConfig() : Bitmap.Config.ARGB_8888,
                    false);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Crop and keep the latest dialogue frame for tap-to-speak without running OCR.
     */
    void cacheDialogueFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return;
        }
        Bitmap prepared = prepareDialogueFrame(frame);
        if (prepared == null) {
            return;
        }
        Bitmap previous = latestFrame;
        latestFrame = prepared;
        if (previous != null && previous != prepared) {
            previous.recycle();
        }
    }

    /**
     * Crop a raw screen buffer the same way continuous capture does, without
     * updating the periodic pipeline. Caller must recycle the result if it is
     * not the same instance as {@code frame}.
     */
    Bitmap prepareDialogueFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return null;
        }
        Bitmap copy = frame.copy(
                frame.getConfig() != null ? frame.getConfig() : Bitmap.Config.ARGB_8888,
                false);
        if (copy == null) {
            return null;
        }
        Bitmap cropped = cropSystemUi(copy);
        if (cropped != copy) {
            copy.recycle();
            copy = cropped;
        }
        Bitmap dialogue = cropDialogueRegions(copy);
        if (dialogue != copy) {
            copy.recycle();
            copy = dialogue;
        }
        return copy;
    }

    void release() {
        captureBecauseTap = false;
        lastFingerprint = Integer.MIN_VALUE;
        Bitmap previous = latestFrame;
        latestFrame = null;
        if (previous != null) {
            previous.recycle();
        }
        Bitmap source = latestSource;
        latestSource = null;
        if (source != null) {
            source.recycle();
        }
    }

    private void accept(Bitmap frame, long nowElapsedMs, boolean allowHeavyVision) {
        Bitmap copy = frame.copy(
                frame.getConfig() != null ? frame.getConfig() : Bitmap.Config.ARGB_8888,
                false);
        if (copy == null) {
            return;
        }
        Bitmap cropped = cropSystemUi(copy);
        if (cropped != copy) {
            copy.recycle();
            copy = cropped;
        }
        Bitmap dialogue = cropDialogueRegions(copy);
        if (dialogue != copy) {
            copy.recycle();
            copy = dialogue;
        }
        lastAcceptElapsedMs = nowElapsedMs;
        lastFingerprint = fingerprint(copy);
        Bitmap previous = latestFrame;
        latestFrame = copy;
        if (previous != null && previous != copy) {
            previous.recycle();
        }
        SnapshotListener listener = snapshotListener;
        if (listener != null) {
            listener.onScreenSnapshot(copy, allowHeavyVision);
        }
    }

    private Bitmap cropSystemUi(Bitmap src) {
        int vw = insetViewW;
        int vh = insetViewH;
        if (src == null || vw <= 0 || vh <= 0) {
            return src;
        }
        if (insetLeft == 0 && insetTop == 0 && insetRight == 0 && insetBottom == 0) {
            return src;
        }
        float sx = src.getWidth() / (float) vw;
        float sy = src.getHeight() / (float) vh;
        int left = Math.min(src.getWidth() - 1, Math.round(insetLeft * sx));
        int top = Math.min(src.getHeight() - 1, Math.round(insetTop * sy));
        int right = Math.min(src.getWidth() - left, Math.round(insetRight * sx));
        int bottom = Math.min(src.getHeight() - top, Math.round(insetBottom * sy));
        int width = src.getWidth() - left - right;
        int height = src.getHeight() - top - bottom;
        if (width <= 1 || height <= 1) {
            return src;
        }
        try {
            return Bitmap.createBitmap(src, left, top, width, height);
        } catch (IllegalArgumentException e) {
            return src;
        }
    }

    private Bitmap cropDialogueRegions(Bitmap src) {
        ArrayList<OcrRegion> regions;
        synchronized (regionLock) {
            regions = new ArrayList<>(customRegions);
        }
        if (regions.isEmpty()) {
            return cropDefaultSubtitleBand(src);
        }
        ArrayList<Bitmap> crops = new ArrayList<>();
        int maxW = 0;
        int totalH = 0;
        int gap = 8;
        for (OcrRegion region : regions) {
            Bitmap piece = cropNormalized(src, region);
            if (piece == null) {
                continue;
            }
            crops.add(piece);
            maxW = Math.max(maxW, piece.getWidth());
            totalH += piece.getHeight() + gap;
        }
        if (crops.isEmpty()) {
            return cropDefaultSubtitleBand(src);
        }
        if (crops.size() == 1) {
            return crops.get(0);
        }
        totalH -= gap;
        Bitmap out = Bitmap.createBitmap(Math.max(32, maxW), Math.max(32, totalH), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        int y = 0;
        for (Bitmap piece : crops) {
            canvas.drawBitmap(piece, 0, y, null);
            y += piece.getHeight() + gap;
            piece.recycle();
        }
        return out;
    }

    private static Bitmap cropNormalized(Bitmap src, OcrRegion region) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 8 || h < 8 || region == null) {
            return null;
        }
        region.normalize();
        int left = Math.max(0, Math.round(region.left * w));
        int top = Math.max(0, Math.round(region.top * h));
        int right = Math.min(w, Math.round(region.right * w));
        int bottom = Math.min(h, Math.round(region.bottom * h));
        int width = right - left;
        int height = bottom - top;
        if (width < 8 || height < 8) {
            return null;
        }
        try {
            return Bitmap.createBitmap(src, left, top, width, height);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Streaming / anime subs sit in the lower-center of the video, above the
     * progress bar (CC / 1080P / timestamps). Do not use the raw bottom 30% —
     * that band is almost entirely player chrome.
     */
    private static Bitmap cropDefaultSubtitleBand(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 32 || h < 32) {
            return src;
        }
        int left = Math.round(w * DIALOGUE_ROI_SIDE_FRACTION);
        int rightPad = Math.round(w * DIALOGUE_ROI_SIDE_FRACTION);
        int top = Math.round(h * DIALOGUE_ROI_TOP_FRACTION);
        int bottom = Math.round(h * DIALOGUE_ROI_BOTTOM_FRACTION);
        int width = Math.max(32, w - left - rightPad);
        int height = Math.max(32, bottom - top);
        if (left + width > w) {
            width = w - left;
        }
        if (top + height > h) {
            height = h - top;
        }
        try {
            return Bitmap.createBitmap(src, left, top, width, height);
        } catch (IllegalArgumentException e) {
            return src;
        }
    }

    /** Cheap 16×9 luminance hash so we skip identical MediaProjection frames. */
    private static int fingerprint(Bitmap frame) {
        int srcW = frame.getWidth();
        int srcH = frame.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            return 0;
        }
        Bitmap tiny = Bitmap.createScaledBitmap(frame, 16, 9, true);
        int hash = 17;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int c = tiny.getPixel(x, y);
                int lum = (((c >> 16) & 0xff) * 3 + ((c >> 8) & 0xff) * 6 + (c & 0xff)) / 10;
                hash = 31 * hash + (lum >> 3);
            }
        }
        if (tiny != frame) {
            tiny.recycle();
        }
        return hash;
    }
}
