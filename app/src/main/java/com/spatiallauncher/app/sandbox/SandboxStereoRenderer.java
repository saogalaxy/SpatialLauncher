package com.spatiallauncher.app.sandbox;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.SurfaceView;

import com.spatiallauncher.app.ui.DepthEstimator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds captured container frames into the existing MiDaS DepthEstimator and
 * draws a side-by-side parallax mesh on the overlay SurfaceView.
 */
public final class SandboxStereoRenderer {
    private static final Paint MESH_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final int GRID_COLS = 24;
    private static final int GRID_ROWS = 36;

    private final SurfaceView overlay;
    private final ExecutorService depthExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean depthBusy = new AtomicBoolean(false);
    private DepthEstimator estimator;
    private volatile float[][] parallaxGrid;
    private volatile Bitmap latestFrame;
    private volatile boolean stereoEnabled;

    public SandboxStereoRenderer(SurfaceView overlay) {
        this.overlay = overlay;
    }

    public void setEstimator(DepthEstimator estimator) {
        this.estimator = estimator;
    }

    public boolean isStereoEnabled() {
        return stereoEnabled;
    }

    public void setStereoEnabled(boolean enabled) {
        stereoEnabled = enabled;
        Bitmap latest = latestFrame;
        if (latest != null) {
            overlay.post(() -> draw(latest));
        }
    }

    public void onContainerFrame(Bitmap frame) {
        latestFrame = frame;
        requestDepth(frame);
        draw(frame);
    }

    public void close() {
        depthExecutor.shutdownNow();
        DepthEstimator e = estimator;
        if (e != null) {
            e.close();
        }
    }

    private void requestDepth(Bitmap frame) {
        DepthEstimator midas = estimator;
        if (midas == null || !midas.isAvailable() || !depthBusy.compareAndSet(false, true)) {
            return;
        }
        Bitmap copy = frame.copy(frame.getConfig() != null ? frame.getConfig() : Bitmap.Config.ARGB_8888, false);
        depthExecutor.execute(() -> {
            try {
                float[][] depth01 = midas.estimate(copy, GRID_COLS + 1, GRID_ROWS + 1);
                if (depth01 != null) {
                    parallaxGrid = toParallax(depth01);
                }
            } finally {
                if (copy != frame) {
                    copy.recycle();
                }
                depthBusy.set(false);
                Bitmap latest = latestFrame;
                if (latest != null) {
                    overlay.post(() -> draw(latest));
                }
            }
        });
    }

    private static float[][] toParallax(float[][] depth01) {
        int rows = depth01.length;
        int cols = depth01[0].length;
        float[][] grid = new float[rows][cols];
        float sum = 0f;
        float minPx = 4f;
        float maxPx = 28f;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float shift = minPx + depth01[r][c] * (maxPx - minPx);
                grid[r][c] = shift;
                sum += shift;
            }
        }
        float mean = sum / (rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] -= mean;
            }
        }
        return grid;
    }

    private void draw(Bitmap frame) {
        if (!overlay.getHolder().getSurface().isValid()) {
            return;
        }
        Canvas canvas = overlay.getHolder().lockCanvas();
        if (canvas == null) {
            return;
        }
        try {
            canvas.drawColor(Color.BLACK);
            if (!stereoEnabled) {
                canvas.drawBitmap(frame, null,
                        new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), MESH_PAINT);
                return;
            }
            int half = canvas.getWidth() / 2;
            float[][] grid = parallaxGrid;
            if (grid == null) {
                Rect left = new Rect(0, 0, half, canvas.getHeight());
                Rect right = new Rect(half, 0, canvas.getWidth(), canvas.getHeight());
                canvas.drawBitmap(frame, null, left, MESH_PAINT);
                canvas.drawBitmap(frame, null, right, MESH_PAINT);
                return;
            }
            drawEye(canvas, frame, new Rect(0, 0, half, canvas.getHeight()), grid, 1);
            drawEye(canvas, frame, new Rect(half, 0, canvas.getWidth(), canvas.getHeight()), grid, -1);
        } finally {
            overlay.getHolder().unlockCanvasAndPost(canvas);
        }
    }

    private static void drawEye(Canvas canvas, Bitmap frame, Rect eyeBounds, float[][] parallaxGrid, int direction) {
        int meshRows = parallaxGrid.length - 1;
        int meshCols = parallaxGrid[0].length - 1;
        float[] verts = new float[(meshCols + 1) * (meshRows + 1) * 2];
        int k = 0;
        for (int row = 0; row <= meshRows; row++) {
            float v = row / (float) meshRows;
            float yDst = eyeBounds.top + v * eyeBounds.height();
            for (int col = 0; col <= meshCols; col++) {
                float u = col / (float) meshCols;
                float shift = direction * parallaxGrid[row][col];
                verts[k++] = eyeBounds.left + u * eyeBounds.width() + shift;
                verts[k++] = yDst;
            }
        }
        canvas.save();
        canvas.clipRect(eyeBounds);
        canvas.drawBitmapMesh(frame, meshCols, meshRows, verts, 0, null, 0, MESH_PAINT);
        canvas.restore();
    }
}
