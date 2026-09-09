package com.spatiallauncher.app.sandbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * Tiny TextureView so the harness can attach a real SurfaceTexture listener,
 * not only software Canvas captures of the container.
 */
public class SandboxTextureHost extends TextureView implements TextureView.SurfaceTextureListener {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pulse;

    public SandboxTextureHost(Context context) {
        this(context, null);
    }

    public SandboxTextureHost(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOpaque(false);
        setSurfaceTextureListener(this);
        paint.setColor(Color.WHITE);
        paint.setTextSize(28f);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        drawPulse();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        drawPulse();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    void drawPulse() {
        Canvas canvas = lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            pulse = (pulse + 8) % 256;
            canvas.drawColor(0xFF111827);
            paint.setColor(Color.rgb(56, 189, pulse));
            float cx = canvas.getWidth() / 2f;
            float cy = canvas.getHeight() / 2f;
            canvas.drawCircle(cx, cy, Math.min(cx, cy) * 0.55f, paint);
            paint.setColor(Color.WHITE);
            canvas.drawText("ST", cx, cy + 10f, paint);
        } finally {
            unlockCanvasAndPost(canvas);
        }
    }
}
