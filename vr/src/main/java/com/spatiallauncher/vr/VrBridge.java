package com.spatiallauncher.vr;

import java.nio.ByteBuffer;

/**
 * Beta-only bridge: panel stereo output -> native theater renderer.
 * The panel reaches this ONLY via reflection (see PanelMainActivity), so
 * release/debug builds compile and run with no trace of it.
 */
public final class VrBridge {
    static {
        // The panel reaches pushFrame() via reflection and may run before any
        // VrActivity ever started in this process (so the NativeActivity load
        // hasn't happened). Load explicitly; absent in release/debug builds,
        // where the reflection probe already gates everything off.
        try {
            System.loadLibrary("vr");
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

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
     * Room lighting mood: 0 = passthrough (neutral), 1 = Dusk warm-dim,
     * 2 = Night dark, 3 = Day bright. Reached ONLY via reflection from the
     * panel's Room picker (see PanelMainActivity): without the native lib
     * the call below no-ops inside its try/catch.
     */
    public static final int ENV_PASSTHROUGH = 0;
    public static final int ENV_DUSK = 1;
    public static final int ENV_NIGHT = 2;
    public static final int ENV_DAY = 3;

    private static volatile int environment = ENV_PASSTHROUGH;

    public static void setEnvironment(int index) {
        int clamped = Math.max(ENV_PASSTHROUGH, Math.min(ENV_DAY, index));
        environment = clamped;
        try {
            nativeSetEnvironment(clamped);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    public static int getEnvironment() {
        return environment;
    }

    private static native void nativeSetEnvironment(int index);

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
