package com.spatiallauncher.app.ui;

import android.util.Log;
import android.view.SurfaceView;

import java.lang.reflect.Method;

/**
 * Horizon OS stereo surface composition (SIDE_BY_SIDE / MONO) via reflection —
 * same API as cast 3D in {@link PanelMainActivity}.
 */
final class HorizonStereoComposition {
    private static final String TAG = "HorizonStereo";
    private static final String SURFACE_VIEW_EXT = "horizonos.view.SurfaceViewExt";
    private static final String SURFACE_CONTROL_EXT = "horizonos.view.SurfaceControlExt";

    private HorizonStereoComposition() {}

    static boolean set(SurfaceView surface, boolean sideBySide) {
        if (surface == null) {
            return false;
        }
        try {
            Class<?> extClass = Class.forName(SURFACE_VIEW_EXT);
            Class<?> controlExtClass = Class.forName(SURFACE_CONTROL_EXT);
            int mode = controlExtClass
                    .getField(sideBySide ? "STEREO_COMPOSITION_SIDE_BY_SIDE" : "STEREO_COMPOSITION_MONO")
                    .getInt(null);
            Method method = extClass.getMethod("setStereoComposition", SurfaceView.class, int.class);
            method.invoke(null, surface, mode);
            Log.i(TAG, "stereo composition → " + (sideBySide ? "SIDE_BY_SIDE" : "MONO"));
            return true;
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "SurfaceViewExt unavailable — panel stays mono", e);
            return false;
        }
    }
}
