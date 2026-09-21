package com.spatiallauncher.vr;

import java.nio.ByteBuffer;

/**
 * Beta-only bridge: panel stereo output -> native theater renderer.
 * The panel reaches this ONLY via reflection (see PanelMainActivity), so
 * release/debug builds compile and run with no trace of it.
 */
public final class VrBridge {
    private static volatile boolean theaterActive = false;

    public static void setTheaterActive(boolean active) {
        theaterActive = active;
    }

    public static boolean isTheaterActive() {
        return theaterActive;
    }

    /** Latest pushed frame dimensions (written with the pixel copy). */
    public static volatile int frameW;
    public static volatile int frameH;

    /**
     * Copies ARGB_8888 pixels out of {@code src} into the native staging
     * buffer. Called on the panel draw path, throttled by the caller.
     *
     * @param sbs true when src holds a side-by-side stereo pair (split per
     *            eye in theater); false shows the full frame to both eyes.
     */
    public static native void pushFrame(ByteBuffer src, int w, int h, boolean sbs);

    private VrBridge() {
    }
}
