package com.spatiallauncher.vr;

import android.app.NativeActivity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Beta-only immersive container: an empty passthrough room (see vr_room.c).
 * All UI lives in the panel, which this re-fronts as an overlay shortly
 * after the XR session comes up. Exit via the system button.
 */
public class VrActivity extends NativeActivity {
    private static final String PANEL_CLASS = "com.spatiallauncher.app.ui.PanelMainActivity";
    static final String EXTRA_EXIT_VR = "com.spatiallauncher.vr.EXIT";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handleIntent(getIntent());
        // Bring our panel up as an overlay shortly after the XR session comes
        // up (plain startActivity, no finish — the room stays underneath).
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Intent panel = new Intent(Intent.ACTION_MAIN);
                panel.setClassName(getPackageName(), PANEL_CLASS);
                panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(panel);
            } catch (Throwable ignored) {
            }
        }, 1500);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** The panel asks us to quit via an EXIT extra: finish ends the session. */
    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_EXIT_VR, false)) {
            finish();
        }
    }
}
