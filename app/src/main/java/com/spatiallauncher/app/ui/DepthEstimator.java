package com.spatiallauncher.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * On-device monocular depth. Live uses MiDaS v2.1; Static uses Depth Anything V2 Small.
 */
public class DepthEstimator {
    private static final String TAG = "DepthEstimator";
    static final String LIVE_MODEL_ASSET = "midas_depth.tflite";
    static final String STATIC_MODEL_ASSET = "depth_anything_v2_small.tflite";

    private Interpreter interpreter;
    private String backend = "none";
    private int inputH;
    private int inputW;
    private int outputH;
    private int outputW;
    private boolean nchwInput;
    private boolean available;
    private final boolean invertRaw;
    private final String modelLabel;
    private volatile float depthGamma = 0.6f;
    /** iw3-ish foreground pop. 0 default — scale was flattening content vs window. */
    private volatile float foregroundScale = 0f;
    /**
     * Temporal blend on the output grid after per-frame normalize (keeps full contrast).
     * 0 = off for Static; Live can enable lightly.
     */
    private volatile float temporalKeep = 0f;
    /** Edge-weighted dilation only. 0 default until content pop is solid. */
    private volatile int edgeDilation = 0;
    private float[][] prevGrid;
    private float[][] workA;
    private float[][] workB;
    private float[][] workW;

    public void setDepthGamma(float gamma) {
        depthGamma = Math.max(0.25f, Math.min(1.2f, gamma));
    }

    public void setEdgeDilation(int iterations) {
        edgeDilation = Math.max(0, Math.min(3, iterations));
    }

    public void setTemporalKeep(float keep) {
        temporalKeep = Math.max(0f, Math.min(0.85f, keep));
    }

    public void setForegroundScale(float scale) {
        foregroundScale = Math.max(0f, Math.min(3f, scale));
    }

    public void resetTemporal() {
        prevGrid = null;
    }

    public DepthEstimator(Context context) {
        this(context, LIVE_MODEL_ASSET, false, 4);
    }

    /** Live default is MiDaS (smoother on busy pages). Static loads V2 separately. */
    public static DepthEstimator createDefault(Context context) {
        DepthEstimator midas = new DepthEstimator(context, LIVE_MODEL_ASSET, false, 4);
        if (midas.isAvailable()) {
            return midas;
        }
        Log.w(TAG, "MiDaS unavailable; trying Depth Anything V2");
        return new DepthEstimator(context, STATIC_MODEL_ASSET, true, 4);
    }

    public DepthEstimator(Context context, String assetName, boolean invertRaw, int threads) {
        this.invertRaw = invertRaw;
        this.modelLabel = assetName;
        try {
            MappedByteBuffer modelBuffer = loadModelFile(context, assetName);
            interpreter = createInterpreter(modelBuffer, threads);
            int[] in = interpreter.getInputTensor(0).shape();
            // [1,H,W,3] NHWC or [1,3,H,W] NCHW
            if (in.length == 4 && in[1] == 3) {
                nchwInput = true;
                inputH = in[2];
                inputW = in[3];
            } else if (in.length == 4) {
                nchwInput = false;
                inputH = in[1];
                inputW = in[2];
            } else {
                throw new IllegalStateException("Unexpected input shape");
            }
            int[] out = interpreter.getOutputTensor(0).shape();
            int[] hw = spatialHw(out);
            outputH = hw[0];
            outputW = hw[1];
            available = true;
            Log.i(TAG, modelLabel + " loaded in=" + inputW + "x" + inputH
                    + (nchwInput ? " nchw" : " nhwc")
                    + " out=" + outputW + "x" + outputH
                    + " invertRaw=" + invertRaw
                    + " backend=" + backend);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, modelLabel + " unavailable", e);
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String getBackend() {
        return backend;
    }

    /**
     * CPU only. NNAPI/Hexagon shares the same DSP Quest tracking uses; putting V2
     * there caused display static / tracking glitches.
     */
    private Interpreter createInterpreter(MappedByteBuffer modelBuffer, int threads) {
        int n = Math.max(1, threads);
        Interpreter cpu = new Interpreter(modelBuffer, cpuOptions(n));
        backend = "cpu-fp32";
        Log.i(TAG, "Using CPU threads=" + n + " for " + modelLabel);
        return cpu;
    }

    private static Interpreter.Options cpuOptions(int threads) {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(threads);
        return options;
    }

    private static MappedByteBuffer loadModelFile(Context context, String assetName) throws IOException {
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(assetName);
                FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = in.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    public float[][] estimate(Bitmap frame, int gridCols, int gridRows) {
        if (!available) {
            return null;
        }
        try {
            ByteBuffer input = preprocess(frame);
            Tensor outTensor = interpreter.getOutputTensor(0);
            ByteBuffer outBuf = ByteBuffer.allocateDirect(outTensor.numBytes());
            outBuf.order(ByteOrder.nativeOrder());
            long t0 = android.os.SystemClock.elapsedRealtime();
            interpreter.run(input, outBuf);
            long ms = android.os.SystemClock.elapsedRealtime() - t0;
            Log.i(TAG, modelLabel + " infer " + ms + "ms backend=" + backend);
            outBuf.rewind();
            float[][] depth = readDepthMap(outBuf, outTensor.shape());
            if (depth == null) {
                return null;
            }
            return resampleAndNormalize(depth, gridCols, gridRows);
        } catch (RuntimeException e) {
            Log.w(TAG, "Depth inference failed (" + modelLabel + ")", e);
            return null;
        }
    }

    private static int[] spatialHw(int[] shape) {
        if (shape.length == 4 && shape[1] == 1) {
            return new int[] { shape[2], shape[3] };
        }
        if (shape.length == 4 && shape[3] == 1) {
            return new int[] { shape[1], shape[2] };
        }
        if (shape.length == 3) {
            return new int[] { shape[1], shape[2] };
        }
        if (shape.length == 4) {
            return new int[] { shape[1], shape[2] };
        }
        throw new IllegalStateException("Unexpected output shape");
    }

    private float[][] readDepthMap(ByteBuffer buf, int[] shape) {
        int n = outputH * outputW;
        float[] flat = new float[n];
        buf.asFloatBuffer().get(flat);
        float[][] depth = new float[outputH][outputW];
        boolean nchwOut = shape.length == 4 && shape[1] == 1;
        for (int y = 0; y < outputH; y++) {
            for (int x = 0; x < outputW; x++) {
                int idx = nchwOut ? y * outputW + x : y * outputW + x;
                depth[y][x] = flat[idx];
            }
        }
        return depth;
    }

    private ByteBuffer preprocess(Bitmap frame) {
        Bitmap resized = Bitmap.createScaledBitmap(frame, inputW, inputH, true);
        ByteBuffer buffer = ByteBuffer.allocateDirect(4 * inputH * inputW * 3);
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[inputW * inputH];
        resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH);
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        if (nchwInput) {
            float[] rCh = new float[inputH * inputW];
            float[] gCh = new float[inputH * inputW];
            float[] bCh = new float[inputH * inputW];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                rCh[i] = (((p >> 16) & 0xFF) / 255f - mean[0]) / std[0];
                gCh[i] = (((p >> 8) & 0xFF) / 255f - mean[1]) / std[1];
                bCh[i] = ((p & 0xFF) / 255f - mean[2]) / std[2];
            }
            for (float v : rCh) {
                buffer.putFloat(v);
            }
            for (float v : gCh) {
                buffer.putFloat(v);
            }
            for (float v : bCh) {
                buffer.putFloat(v);
            }
        } else {
            for (int p : pixels) {
                float r = ((p >> 16) & 0xFF) / 255f;
                float g = ((p >> 8) & 0xFF) / 255f;
                float b = (p & 0xFF) / 255f;
                buffer.putFloat((r - mean[0]) / std[0]);
                buffer.putFloat((g - mean[1]) / std[1]);
                buffer.putFloat((b - mean[2]) / std[2]);
            }
        }
        buffer.rewind();
        if (resized != frame) {
            resized.recycle();
        }
        return buffer;
    }

    private float[][] resampleAndNormalize(float[][] raw, int gridCols, int gridRows) {
        int n = outputH * outputW;
        float[] sorted = new float[n];
        int idx = 0;
        for (float[] row : raw) {
            for (float v : row) {
                sorted[idx++] = v;
            }
        }
        java.util.Arrays.sort(sorted);
        // Always stretch THIS frame to full 0–1 (what keeps pop). Do not EMA the range.
        float p5 = sorted[(int) (n * 0.05f)];
        float p95 = sorted[Math.min(n - 1, (int) (n * 0.95f))];
        float range = Math.max(1e-6f, p95 - p5);

        float[][] full = ensureWork(workA, outputH, outputW);
        workA = full;
        float gamma = depthGamma;
        float fg = foregroundScale;
        for (int y = 0; y < outputH; y++) {
            float[] src = raw[y];
            float[] dst = full[y];
            for (int x = 0; x < outputW; x++) {
                float norm = clamp01((src[x] - p5) / range);
                if (invertRaw) {
                    norm = 1f - norm;
                }
                norm = (float) Math.pow(norm, gamma);
                dst[x] = applyForegroundScale(norm, fg);
            }
        }

        // iw3 dilate_edge: only mix dilated depth where local contrast is high.
        if (edgeDilation > 0) {
            full = dilateEdgeWeighted(full, edgeDilation);
        }

        float[][] grid = new float[gridRows][gridCols];
        int cellH = Math.max(1, outputH / gridRows);
        int cellW = Math.max(1, outputW / gridCols);
        for (int gy = 0; gy < gridRows; gy++) {
            int syStart = Math.min(outputH - 1, gy * outputH / gridRows);
            int syEnd = Math.min(outputH, syStart + cellH);
            for (int gx = 0; gx < gridCols; gx++) {
                int sxStart = Math.min(outputW - 1, gx * outputW / gridCols);
                int sxEnd = Math.min(outputW, sxStart + cellW);
                float sum = 0f;
                int count = 0;
                for (int sy = syStart; sy < syEnd; sy++) {
                    for (int sx = sxStart; sx < sxEnd; sx++) {
                        sum += full[sy][sx];
                        count++;
                    }
                }
                grid[gy][gx] = count == 0 ? 0f : sum / count;
            }
        }
        return temporalBlend(grid);
    }

    /** Lift near / crush far a bit (iw3 --foreground-scale spirit). */
    private static float applyForegroundScale(float d, float scale) {
        if (scale <= 0.01f) {
            return d;
        }
        float p = 1f + 0.35f * scale;
        return 1f - (float) Math.pow(1f - clamp01(d), p);
    }

    /**
     * Port of nunif iw3 dilate_edge idea: weight by local depth range, blur+max only
     * on edges, leave flat regions alone so pop stays.
     */
    private float[][] dilateEdgeWeighted(float[][] src, int iterations) {
        float[][] cur = src;
        for (int it = 0; it < iterations; it++) {
            float[][] blur = ensureWork(workB, outputH, outputW);
            workB = blur;
            gaussianBlur3(cur, blur);

            float[][] dilated = ensureWork(workW, outputH, outputW);
            workW = dilated;
            maxPool3(blur, dilated);

            // Local contrast → edge weight, then min-max to 0..1
            float wMin = Float.POSITIVE_INFINITY;
            float wMax = Float.NEGATIVE_INFINITY;
            // Reuse blur buffer for weights after dilated is filled
            float[][] weights = blur;
            for (int y = 0; y < outputH; y++) {
                for (int x = 0; x < outputW; x++) {
                    float localMax = Float.NEGATIVE_INFINITY;
                    float localMin = Float.POSITIVE_INFINITY;
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = clamp(y + dy, 0, outputH - 1);
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = clamp(x + dx, 0, outputW - 1);
                            float v = cur[ny][nx];
                            if (v > localMax) {
                                localMax = v;
                            }
                            if (v < localMin) {
                                localMin = v;
                            }
                        }
                    }
                    float w = localMax - localMin;
                    weights[y][x] = w;
                    if (w < wMin) {
                        wMin = w;
                    }
                    if (w > wMax) {
                        wMax = w;
                    }
                }
            }
            float wRange = Math.max(1e-6f, wMax - wMin);

            float[][] next = (cur == workA)
                    ? ensureWork(null, outputH, outputW)
                    : ensureWork(workA, outputH, outputW);
            workA = next;
            for (int y = 0; y < outputH; y++) {
                for (int x = 0; x < outputW; x++) {
                    float w = (weights[y][x] - wMin) / wRange;
                    float edge = w * w; // bias toward true edges only
                    next[y][x] = cur[y][x] * (1f - edge) + dilated[y][x] * edge;
                }
            }
            cur = next;
        }
        return cur;
    }

    private static void gaussianBlur3(float[][] src, float[][] dst) {
        // iw3 kernel / 256
        final float c00 = 21 / 256f, c01 = 31 / 256f, c02 = 21 / 256f;
        final float c10 = 31 / 256f, c11 = 48 / 256f, c12 = 31 / 256f;
        final float c20 = 21 / 256f, c21 = 31 / 256f, c22 = 21 / 256f;
        int h = src.length;
        int w = src[0].length;
        for (int y = 0; y < h; y++) {
            int ym = clamp(y - 1, 0, h - 1);
            int yp = clamp(y + 1, 0, h - 1);
            for (int x = 0; x < w; x++) {
                int xm = clamp(x - 1, 0, w - 1);
                int xp = clamp(x + 1, 0, w - 1);
                dst[y][x] =
                        src[ym][xm] * c00 + src[ym][x] * c01 + src[ym][xp] * c02
                                + src[y][xm] * c10 + src[y][x] * c11 + src[y][xp] * c12
                                + src[yp][xm] * c20 + src[yp][x] * c21 + src[yp][xp] * c22;
            }
        }
    }

    private static void maxPool3(float[][] src, float[][] dst) {
        int h = src.length;
        int w = src[0].length;
        for (int y = 0; y < h; y++) {
            int ym = clamp(y - 1, 0, h - 1);
            int yp = clamp(y + 1, 0, h - 1);
            for (int x = 0; x < w; x++) {
                int xm = clamp(x - 1, 0, w - 1);
                int xp = clamp(x + 1, 0, w - 1);
                float m = src[ym][xm];
                m = Math.max(m, src[ym][x]);
                m = Math.max(m, src[ym][xp]);
                m = Math.max(m, src[y][xm]);
                m = Math.max(m, src[y][x]);
                m = Math.max(m, src[y][xp]);
                m = Math.max(m, src[yp][xm]);
                m = Math.max(m, src[yp][x]);
                m = Math.max(m, src[yp][xp]);
                dst[y][x] = m;
            }
        }
    }

    private float[][] temporalBlend(float[][] grid) {
        float keep = temporalKeep;
        if (keep < 0.01f || prevGrid == null
                || prevGrid.length != grid.length
                || prevGrid[0].length != grid[0].length) {
            prevGrid = copyGrid(grid);
            return grid;
        }
        int rows = grid.length;
        int cols = grid[0].length;
        float sumAbs = 0f;
        int n = rows * cols;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                sumAbs += Math.abs(grid[y][x] - prevGrid[y][x]);
            }
        }
        // Scene / content jump — snap to new depth so we don't smear two worlds.
        if (sumAbs / n > 0.18f) {
            prevGrid = copyGrid(grid);
            return grid;
        }
        float add = 1f - keep;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                float v = keep * prevGrid[y][x] + add * grid[y][x];
                grid[y][x] = v;
                prevGrid[y][x] = v;
            }
        }
        return grid;
    }

    private static float[][] copyGrid(float[][] src) {
        float[][] out = new float[src.length][src[0].length];
        for (int y = 0; y < src.length; y++) {
            System.arraycopy(src[y], 0, out[y], 0, src[y].length);
        }
        return out;
    }

    private static float[][] ensureWork(float[][] buf, int h, int w) {
        if (buf != null && buf.length == h && buf[0].length == w) {
            return buf;
        }
        return new float[h][w];
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
