package com.spatiallauncher.app.sandbox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

/**
 * Intercepts the container's SurfaceTexture / Canvas into a Bitmap for MiDaS.
 */
public final class ContainerFrameCapture {
    private static final String TAG = "SandboxFrameCapture";

    public interface FrameListener {
        void onContainerFrame(Bitmap frame);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameListener listener;
    private View container;
    private TextureView hookedTexture;
    private boolean running;

    public void attach(View container, FrameListener listener) {
        this.container = container;
        this.listener = listener;
        hookedTexture = findTextureView(container);
        if (hookedTexture != null) {
            hookedTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    if (hookedTexture instanceof SandboxTextureHost) {
                        ((SandboxTextureHost) hookedTexture).drawPulse();
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                    captureNow();
                }
            });
        }
    }

    public void start(long intervalMs) {
        running = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                captureNow();
                View tex = hookedTexture;
                if (tex instanceof SandboxTextureHost) {
                    ((SandboxTextureHost) tex).drawPulse();
                }
                main.postDelayed(this, intervalMs);
            }
        });
    }

    public void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
    }

    public void captureNow() {
        View target = container;
        FrameListener sink = listener;
        if (target == null || sink == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return;
        }
        if (target instanceof SurfaceView && ((SurfaceView) target).getHolder().getSurface().isValid()) {
            Bitmap out = Bitmap.createBitmap(target.getWidth(), target.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                PixelCopy.request((SurfaceView) target, out, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        sink.onContainerFrame(out);
                    } else {
                        out.recycle();
                        softwareCapture(target, sink);
                    }
                }, main);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "PixelCopy failed", e);
                softwareCapture(target, sink);
            }
            return;
        }
        softwareCapture(target, sink);
    }

    private static void softwareCapture(View target, FrameListener sink) {
        Bitmap bitmap = Bitmap.createBitmap(target.getWidth(), target.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        target.draw(canvas);
        sink.onContainerFrame(bitmap);
    }

    private static TextureView findTextureView(View root) {
        if (root instanceof TextureView) {
            return (TextureView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextureView found = findTextureView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
