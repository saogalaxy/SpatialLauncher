package com.spatiallauncher.app.ui;

/**
 * Normalized OCR rectangle in 0–1 fractions of the capture frame
 * (after system-inset crop).
 */
final class OcrRegion {
    float left;
    float top;
    float right;
    float bottom;

    OcrRegion(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        normalize();
    }

    OcrRegion copy() {
        return new OcrRegion(left, top, right, bottom);
    }

    void normalize() {
        left = clamp01(left);
        top = clamp01(top);
        right = clamp01(right);
        bottom = clamp01(bottom);
        if (right < left) {
            float t = left;
            left = right;
            right = t;
        }
        if (bottom < top) {
            float t = top;
            top = bottom;
            bottom = t;
        }
        if (right - left < MIN_SIZE) {
            right = Math.min(1f, left + MIN_SIZE);
            if (right - left < MIN_SIZE) {
                left = Math.max(0f, right - MIN_SIZE);
            }
        }
        if (bottom - top < MIN_SIZE) {
            bottom = Math.min(1f, top + MIN_SIZE);
            if (bottom - top < MIN_SIZE) {
                top = Math.max(0f, bottom - MIN_SIZE);
            }
        }
    }

    float width() {
        return right - left;
    }

    float height() {
        return bottom - top;
    }

    boolean contains(float x, float y) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    static final float MIN_SIZE = 0.04f;

    static OcrRegion defaultSubtitleBand() {
        return new OcrRegion(
                ScreenFrameCapture.DIALOGUE_ROI_SIDE_FRACTION,
                ScreenFrameCapture.DIALOGUE_ROI_TOP_FRACTION,
                1f - ScreenFrameCapture.DIALOGUE_ROI_SIDE_FRACTION,
                ScreenFrameCapture.DIALOGUE_ROI_BOTTOM_FRACTION);
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }
}
