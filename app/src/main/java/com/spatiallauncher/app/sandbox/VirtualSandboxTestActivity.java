package com.spatiallauncher.app.sandbox;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.spatiallauncher.app.R;
import com.spatiallauncher.app.ui.DepthEstimator;

/**
 * Isolated PoC screen: guest view under a 3D overlay, MiDaS on captured frames,
 * touch remapped with xRatio/yRatio. Does not alter production cast.
 */
public class VirtualSandboxTestActivity extends Activity {

    public static final String GIT_BRANCH = "feature/virtual-app-sandbox";

    private ContainerFrameCapture capture;
    private SandboxStereoRenderer stereo;
    private View guestView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virtual_sandbox);

        TextView branchBadge = findViewById(R.id.sandbox_branch_badge);
        branchBadge.setText(getString(R.string.sandbox_branch_badge, GIT_BRANCH));

        FrameLayout container = findViewById(R.id.sandbox_container);
        SurfaceView overlay = findViewById(R.id.sandbox_stereo_overlay);
        overlay.setZOrderOnTop(true);
        overlay.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        String apk = getIntent().getStringExtra(VirtualAppLoader.EXTRA_APK_PATH);
        String cls = getIntent().getStringExtra(VirtualAppLoader.EXTRA_VIEW_CLASS);
        guestView = VirtualAppLoader.load(this, container, apk, cls);
        container.addView(guestView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        stereo = new SandboxStereoRenderer(overlay);
        new Thread(() -> {
            DepthEstimator estimator = new DepthEstimator(getApplicationContext());
            overlay.post(() -> stereo.setEstimator(estimator));
        }, "sandbox-midas").start();

        capture = new ContainerFrameCapture();
        capture.attach(container, frame -> stereo.onContainerFrame(frame));

        View touchOverlay = findViewById(R.id.sandbox_touch_overlay);
        touchOverlay.setOnTouchListener((v, event) -> {
            boolean handled = SandboxTouchForwarder.dispatch(event, v, guestView);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.performClick();
            }
            return handled;
        });

        findViewById(R.id.sandbox_close).setOnClickListener(v -> finish());
        findViewById(R.id.sandbox_capture_once).setOnClickListener(v -> capture.captureNow());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (capture != null) {
            capture.start(350L);
        }
    }

    @Override
    protected void onPause() {
        if (capture != null) {
            capture.stop();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (capture != null) {
            capture.stop();
        }
        if (stereo != null) {
            stereo.close();
        }
        super.onDestroy();
    }
}
