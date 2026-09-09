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
 * On-device monocular depth. Live path uses bundled MiDaS v2.1; Static (comics/stills)
 * uses Depth Anything V2 Small when that asset is present.
 */
public class DepthEstimator {
    private static final String TAG = "DepthEstimator";
    static final String LIVE_MODEL_ASSET = "midas_depth.tflite";
    static final String STATIC_MODEL_ASSET = "depth_anything_v2_small.tflite";

    private Interpreter interpreter;
    private int inputH;
    private int inputW;
    private int outputH;
    private int outputW;
    private boolean nchwInput;
    private boolean available;
    private final boolean invertRaw;
    private final String modelLabel;
    private volatile float depthGamma = 0.6f;

    public void setDepthGamma(float gamma) {
        depthGamma = Math.max(0.25f, Math.min(1.2f, gamma));
    }

    public DepthEstimator(Context context) {
        this(context, LIVE_MODEL_ASSET, false, 4);
    }

    public DepthEstimator(Context context, String assetName, boolean invertRaw, int threads) {
        this.invertRaw = invertRaw;
        this.modelLabel = assetName;
        try {
            MappedByteBuffer modelBuffer = loadModelFile(context, assetName);
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(Math.max(1, threads));
            interpreter = new Interpreter(modelBuffer, options);

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
                    + " invertRaw=" + invertRaw);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, modelLabel + " unavailable", e);
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
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
            interpreter.run(input, outBuf);
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
        float p5 = sorted[(int) (n * 0.05f)];
        float p95 = sorted[(int) (n * 0.95f)];
        float range = Math.max(1e-6f, p95 - p5);

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
                        sum += raw[sy][sx];
                        count++;
                    }
                }
                float avg = count == 0 ? 0f : sum / count;
                float norm = clamp01((avg - p5) / range);
                if (invertRaw) {
                    norm = 1f - norm;
                }
                grid[gy][gx] = (float) Math.pow(norm, depthGamma);
            }
        }
        return grid;
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
