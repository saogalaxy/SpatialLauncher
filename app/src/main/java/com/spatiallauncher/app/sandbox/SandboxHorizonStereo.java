package com.spatiallauncher.app.sandbox;

import android.util.Log;
import android.view.SurfaceView;

import java.lang.reflect.Method;

/**
 * Same Horizon OS per-eye composition the main panel uses. Software SBS on a
 * 2D panel looks like two pages; this API shows one image, one per eye.
 */
final class SandboxHorizonStereo {
    private static final String TAG = "SandboxHorizon";
    private static final String VIEW_EXT = "horizonos.view.SurfaceViewExt";
    private static final String CONTROL_EXT = "horizonos.view.SurfaceControlExt";

    private SandboxHorizonStereo() {}

    static boolean apply(SurfaceView surface, boolean stereo) {
        try {
            Class<?> extClass = Class.forName(VIEW_EXT);
            Class<?> controlExtClass = Class.forName(CONTROL_EXT);
            int mode = controlExtClass
                    .getField(stereo ? "STEREO_COMPOSITION_SIDE_BY_SIDE" : "STEREO_COMPOSITION_MONO")
                    .getInt(null);
            Method method = extClass.getMethod("setStereoComposition", SurfaceView.class, int.class);
            method.invoke(null, surface, mode);
            Log.i(TAG, stereo ? "Horizon SBS composition on" : "Horizon mono composition");
            return stereo;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "Horizon stereo composition unavailable", e);
            return false;
        }
    }
}
