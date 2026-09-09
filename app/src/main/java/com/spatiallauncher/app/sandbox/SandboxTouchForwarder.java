package com.spatiallauncher.app.sandbox;

import android.view.MotionEvent;
import android.view.View;

/**
 * Maps overlay touches onto the isolated container using xRatio / yRatio
 * (event coordinate divided by overlay size), matching production cast forwarding.
 */
public final class SandboxTouchForwarder {

    private SandboxTouchForwarder() {}

    public static boolean dispatch(MotionEvent event, View overlay, View container) {
        if (event == null || overlay == null || container == null) {
            return false;
        }
        float overlayW = Math.max(1, overlay.getWidth());
        float overlayH = Math.max(1, overlay.getHeight());
        float xRatio = event.getX() / overlayW;
        float yRatio = event.getY() / overlayH;

        float[] mapped = ratiosToContainer(overlay, container, xRatio, yRatio, event.getX(), event.getY());
        float mappedX = mapped[0] * Math.max(1, container.getWidth());
        float mappedY = mapped[1] * Math.max(1, container.getHeight());

        MotionEvent copy = MotionEvent.obtain(event);
        copy.offsetLocation(mappedX - event.getX(), mappedY - event.getY());
        try {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                overlay.getParent().requestDisallowInterceptTouchEvent(true);
                container.requestFocus();
            }
            return container.dispatchTouchEvent(copy);
        } finally {
            copy.recycle();
        }
    }

    /**
     * xRatio/yRatio in overlay space → normalized 0..1 in the container.
     * Letterbox-aware when overlay aspect does not match the container.
     */
    static float[] ratiosToContainer(
            View overlay, View container, float xRatio, float yRatio, float overlayX, float overlayY) {
        float overlayW = Math.max(1, overlay.getWidth());
        float overlayH = Math.max(1, overlay.getHeight());
        float castW = Math.max(1, container.getWidth());
        float castH = Math.max(1, container.getHeight());

        float nx = clamp01(xRatio);
        float ny = clamp01(yRatio);

        float boxAspect = overlayW / overlayH;
        float contentAspect = castW / castH;
        if (Math.abs(boxAspect - contentAspect) >= 0.002f) {
            float left;
            float top;
            float width;
            float height;
            if (boxAspect > contentAspect) {
                width = overlayH * contentAspect;
                height = overlayH;
                left = (overlayW - width) / 2f;
                top = 0f;
            } else {
                width = overlayW;
                height = overlayW / contentAspect;
                left = 0f;
                top = (overlayH - height) / 2f;
            }
            nx = clamp01((overlayX - left) / Math.max(1f, width));
            ny = clamp01((overlayY - top) / Math.max(1f, height));
        }
        return new float[] { nx, ny };
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
