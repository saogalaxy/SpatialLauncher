package com.spatiallauncher.app.sandbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private FrameLayout container;
    private View guestView;
    private SurfaceView overlay;
    private View touchOverlay;
    private Button toggle3d;
    private boolean horizonStereoApplied;
    private SandboxAppMirror appMirror;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_virtual_sandbox);

        TextView branchBadge = findViewById(R.id.sandbox_branch_badge);
        branchBadge.setText(getString(R.string.sandbox_branch_badge, GIT_BRANCH));

        container = findViewById(R.id.sandbox_container);
        overlay = findViewById(R.id.sandbox_stereo_overlay);
        overlay.getHolder().setFormat(PixelFormat.OPAQUE);
        touchOverlay = findViewById(R.id.sandbox_touch_overlay);

        String apk = getIntent().getStringExtra(VirtualAppLoader.EXTRA_APK_PATH);
        String cls = getIntent().getStringExtra(VirtualAppLoader.EXTRA_VIEW_CLASS);
        setGuest(VirtualAppLoader.load(this, container, apk, cls));

        stereo = new SandboxStereoRenderer(overlay);
        new Thread(() -> {
            DepthEstimator estimator = new DepthEstimator(getApplicationContext());
            overlay.post(() -> stereo.setEstimator(estimator));
        }, "sandbox-midas").start();

        capture = new ContainerFrameCapture();
        capture.attach(container, frame -> stereo.onContainerFrame(frame));
        appMirror = new SandboxAppMirror(this);

        View.OnTouchListener forwardTouches = (v, event) -> {
            boolean softwareSbs = stereo != null && stereo.isStereoEnabled() && !horizonStereoApplied;
            boolean handled = SandboxTouchForwarder.dispatch(event, v, guestView, softwareSbs);
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.performClick();
            }
            return handled;
        };
        overlay.setOnTouchListener(forwardTouches);
        touchOverlay.setOnTouchListener(forwardTouches);

        toggle3d = findViewById(R.id.sandbox_toggle_3d);
        toggle3d.setOnClickListener(v -> setSandbox3dEnabled(!stereo.isStereoEnabled()));
        setSandbox3dEnabled(false);

        findViewById(R.id.sandbox_close).setOnClickListener(v -> finish());
        findViewById(R.id.sandbox_capture_once).setOnClickListener(v -> capture.captureNow());
        findViewById(R.id.sandbox_guest_demo).setOnClickListener(v ->
                setGuest(VirtualAppLoader.createDemoHost(this)));
        findViewById(R.id.sandbox_show_app).setOnClickListener(v -> startAppPreview());
    }

    private void startAppPreview() {
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF0B1220);
        setGuest(preview);
        if (appMirror == null) {
            appMirror = new SandboxAppMirror(this);
        }
        appMirror.setPreview(preview);
        appMirror.pickAndShowApp();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SandboxAppMirror.REQUEST_CAPTURE && appMirror != null) {
            appMirror.onCaptureResult(resultCode, data);
        }
    }

    private void setGuest(View next) {
        container.removeAllViews();
        guestView = next;
        container.addView(guestView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (capture != null) {
            capture.attach(container, frame -> stereo.onContainerFrame(frame));
        }
    }

    private void setSandbox3dEnabled(boolean on) {
        stereo.setStereoEnabled(on);
        toggle3d.setText(on ? R.string.sandbox_3d_on : R.string.sandbox_3d_off);
        if (on) {
            overlay.setVisibility(View.VISIBLE);
            touchOverlay.setVisibility(View.VISIBLE);
            horizonStereoApplied = SandboxHorizonStereo.apply(overlay, true);
            if (capture != null) {
                capture.stop();
                capture.start(180L);
            }
        } else {
            horizonStereoApplied = false;
            SandboxHorizonStereo.apply(overlay, false);
            overlay.setVisibility(View.GONE);
            touchOverlay.setVisibility(View.GONE);
            if (capture != null) {
                capture.stop();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (capture != null && stereo != null && stereo.isStereoEnabled()) {
            capture.start(180L);
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
        if (appMirror != null) {
            appMirror.release();
            appMirror = null;
        }
        if (stereo != null) {
            stereo.close();
        }
        super.onDestroy();
    }
}
