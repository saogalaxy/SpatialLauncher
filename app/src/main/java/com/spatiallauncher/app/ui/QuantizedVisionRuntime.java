package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Option A — pass the screen <em>crop</em> straight into a quantized vision model.
 * Runtime preference: llama.cpp (GGUF / mmproj) → ExecuTorch ({@code .pte}) →
 * ONNX Runtime ({@code .onnx}).
 * <p>
 * Pros vs OCR: stylized/fantasy fonts, game speech bubbles, low-contrast text,
 * and busy HUDs. Cons: about 500ms–1.5s per frame (model size and GPU/NPU).
 * None of these native stacks are linked in the APK yet;
 * {@link #infer} returns null so OCR can fill in.
 */
final class QuantizedVisionRuntime {
    private static final String TAG = "QuantizedVisionRuntime";
    private static final int MAX_CROP_EDGE = 672;

    enum Backend {
        LLAMA_CPP,
        EXECUTORCH,
        ONNX_RUNTIME,
        NONE
    }

    private final Backend backend;
    private final String modelAsset;

    QuantizedVisionRuntime(Context context) {
        AssetManager assets = context.getApplicationContext().getAssets();
        String llama = firstAsset(assets, ".gguf");
        String pte = firstAsset(assets, ".pte");
        String onnx = firstAsset(assets, ".onnx");
        if (llama != null && nativeLibraryLoaded("llama")) {
            backend = Backend.LLAMA_CPP;
            modelAsset = llama;
        } else if (pte != null && nativeLibraryLoaded("executorch")) {
            backend = Backend.EXECUTORCH;
            modelAsset = pte;
        } else if (onnx != null && nativeLibraryLoaded("onnxruntime")) {
            backend = Backend.ONNX_RUNTIME;
            modelAsset = onnx;
        } else {
            backend = Backend.NONE;
            modelAsset = firstNonNull(llama, pte, onnx);
        }
        Log.i(TAG, "Vision backend=" + backend
                + " modelAsset=" + (modelAsset != null ? modelAsset : "none")
                + " (crop is JPEG'd and passed as the image input)");
    }

    Backend backend() {
        return backend;
    }

    /**
     * Runs the crop through the selected runtime. {@code null} means the native
     * engine is not linked — caller should OCR-fallback.
     */
    String infer(Bitmap screenCrop, String prompt) {
        if (screenCrop == null || screenCrop.isRecycled()) {
            return null;
        }
        Bitmap crop = fitMaxEdge(screenCrop, MAX_CROP_EDGE);
        byte[] jpeg = toJpeg(crop);
        if (crop != screenCrop) {
            crop.recycle();
        }
        if (jpeg.length == 0 || backend == Backend.NONE) {
            return null;
        }
        switch (backend) {
            case LLAMA_CPP:
                return inferLlamaCpp(jpeg, prompt);
            case EXECUTORCH:
                return inferExecuTorch(jpeg, prompt);
            case ONNX_RUNTIME:
                return inferOnnx(jpeg, prompt);
            default:
                return null;
        }
    }

    private String inferLlamaCpp(byte[] cropJpeg, String prompt) {
        Log.i(TAG, "llama.cpp would consume crop JPEG (" + cropJpeg.length
                + " bytes) + prompt");
        try {
            return nativeLlamaInfer(cropJpeg, prompt, modelAsset);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "llama.cpp JNI not present", e);
            return null;
        }
    }

    private String inferExecuTorch(byte[] cropJpeg, String prompt) {
        Log.i(TAG, "ExecuTorch would consume crop JPEG (" + cropJpeg.length
                + " bytes) + prompt against " + modelAsset);
        try {
            return nativeExecuTorchInfer(cropJpeg, prompt, modelAsset);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "ExecuTorch JNI not present", e);
            return null;
        }
    }

    private String inferOnnx(byte[] cropJpeg, String prompt) {
        Log.i(TAG, "ONNX Runtime would consume crop JPEG (" + cropJpeg.length
                + " bytes) + prompt against " + modelAsset);
        try {
            return nativeOnnxInfer(cropJpeg, prompt, modelAsset);
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "ONNX Runtime JNI not present", e);
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static native String nativeLlamaInfer(byte[] cropJpeg, String prompt, String asset);

    @SuppressWarnings("unused")
    private static native String nativeExecuTorchInfer(byte[] cropJpeg, String prompt, String asset);

    @SuppressWarnings("unused")
    private static native String nativeOnnxInfer(byte[] cropJpeg, String prompt, String asset);

    private static boolean nativeLibraryLoaded(String name) {
        try {
            System.loadLibrary(name);
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    static Bitmap fitMaxEdge(Bitmap src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int edge = Math.max(w, h);
        if (edge <= maxEdge) {
            return src;
        }
        float scale = maxEdge / (float) edge;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static byte[] toJpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    private static String firstAsset(AssetManager assets, String suffix) {
        try {
            String[] list = assets.list("");
            if (list == null) {
                return null;
            }
            for (String item : list) {
                if (item != null && item.toLowerCase().endsWith(suffix)) {
                    return item;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
