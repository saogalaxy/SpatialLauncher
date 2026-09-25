package com.spatiallauncher.vr;

import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;

/**
 * Beta-only immersive container: an empty passthrough room (see vr_room.c).
 * All UI lives in the panel, which this re-fronts as an overlay shortly
 * after the XR session comes up. Exit via the system button.
 */
public class VrActivity extends NativeActivity {
    private static final String TAG = "VrActivity";
    private static final String PANEL_CLASS = "com.spatiallauncher.app.ui.PanelMainActivity";
    static final String EXTRA_EXIT_VR = "com.spatiallauncher.vr.EXIT";
    /** Debug/remote handle: am start --ei com.spatiallauncher.vr.ENV <0-3>. */
    static final String EXTRA_ENV = "com.spatiallauncher.vr.ENV";
    // Mirrors UserSettingsStore ("spatial_launcher_settings" / "vr_environment"):
    // this module can't reference the panel's store (beta-only split).
    private static final String PREFS_NAME = "spatial_launcher_settings";
    private static final String KEY_VR_ENVIRONMENT = "vr_environment";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        VrBridge.setTheaterActive(true);
        VrBridge.setDockActive(true); // SCAFFOLD (vr-split)
        try {
            int env = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getInt(KEY_VR_ENVIRONMENT, VrBridge.ENV_PASSTHROUGH);
            VrBridge.setEnvironment(env);
        } catch (Throwable ignored) {
        }
        handleIntent(getIntent());
        // Bring our panel up as an overlay once the XR session is begun
        // (plain startActivity, no finish — the room stays underneath).
        // The overlay steals focus and the runtime withholds READY from a
        // backgrounded activity, so fronting it too early wedges the session
        // in IDLE forever (no session begun, no room). 8s wins the race on
        // cold start; a begun session survives the overlay.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent panel = new Intent(Intent.ACTION_MAIN);
                panel.setClassName(getPackageName(), PANEL_CLASS);
                panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(panel);
            } catch (Throwable t) {
                Log.w(TAG, "panel overlay launch failed", t);
            }
        }, 8000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        VrBridge.setTheaterActive(false);
        VrBridge.setDockActive(false); // SCAFFOLD (vr-split)
        super.onDestroy();
    }

    /** The panel asks us to quit via an EXIT extra: finish ends the session. */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (intent.getBooleanExtra(EXTRA_EXIT_VR, false)) {
            finish();
            return;
        }
        if (intent.hasExtra(EXTRA_ENV)) {
            VrBridge.setEnvironment(intent.getIntExtra(EXTRA_ENV, VrBridge.ENV_PASSTHROUGH));
        }
    }
}
