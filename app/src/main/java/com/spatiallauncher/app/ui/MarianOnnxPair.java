package com.spatiallauncher.app.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

/** One bundled OPUS-MT language pair (encoder + decoder ONNX). */
final class MarianOnnxPair {
    private static final String TAG = "MarianOnnx";
    private static final int MAX_SRC = 96;
    private static final int MAX_TGT = 64;

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final UnigramTokenizer tokenizer;
    private final int padId;
    private final int eosId;
    private final String encoderIdsName;
    private final String encoderMaskName;
    private final String encoderOutName;
    private final String decoderIdsName;
    private final String decoderHiddenName;
    private final String decoderMaskName;
    private final String decoderLogitsName;

    static MarianOnnxPair load(Context context, OrtEnvironment env, String pairId) throws Exception {
        File dir = copyPair(context, pairId);
        JSONObject config = new JSONObject(readUtf8(new File(dir, "config.json")));
        int pad = config.optInt("pad_token_id", 60715);
        int eos = config.optInt("eos_token_id", 0);
        UnigramTokenizer tok = new UnigramTokenizer(new File(dir, "pieces.tsv"), 1, eos, pad);
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        OrtSession enc = env.createSession(new File(dir, "encoder.onnx").getAbsolutePath(), opts);
        OrtSession dec = env.createSession(new File(dir, "decoder.onnx").getAbsolutePath(), opts);
        return new MarianOnnxPair(env, enc, dec, tok, pad, eos);
    }

    private MarianOnnxPair(
            OrtEnvironment env,
            OrtSession encoder,
            OrtSession decoder,
            UnigramTokenizer tokenizer,
            int padId,
            int eosId) {
        this.env = env;
        this.encoder = encoder;
        this.decoder = decoder;
        this.tokenizer = tokenizer;
        this.padId = padId;
        this.eosId = eosId;
        this.encoderIdsName = pick(encoder.getInputNames(), "input_ids");
        this.encoderMaskName = pick(encoder.getInputNames(), "attention_mask");
        this.encoderOutName = pick(encoder.getOutputNames(), "last_hidden_state");
        this.decoderIdsName = pick(decoder.getInputNames(), "input_ids");
        this.decoderHiddenName = pickContains(decoder.getInputNames(), "encoder_hidden_states", "hidden");
        this.decoderMaskName = pickContains(decoder.getInputNames(), "encoder_attention_mask", "attention_mask");
        this.decoderLogitsName = pickContains(decoder.getOutputNames(), "logits", "output");
        Log.i(TAG, "IO enc=" + encoder.getInputNames() + "->" + encoder.getOutputNames()
                + " dec=" + decoder.getInputNames() + "->" + decoder.getOutputNames());
    }

    synchronized String translate(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        int[] src = tokenizer.encode(text.trim());
        if (src.length > MAX_SRC) {
            int[] cut = new int[MAX_SRC];
            System.arraycopy(src, 0, cut, 0, MAX_SRC - 1);
            cut[MAX_SRC - 1] = eosId;
            src = cut;
        }
        long[] srcIds = toLong(src);
        long[] mask = new long[src.length];
        java.util.Arrays.fill(mask, 1L);
        try (OnnxTensor idTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(srcIds), new long[] {1, src.length});
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), new long[] {1, src.length})) {
            Map<String, OnnxTensor> encIn = new HashMap<>();
            encIn.put(encoderIdsName, idTensor);
            if (encoderMaskName != null) {
                encIn.put(encoderMaskName, maskTensor);
            }
            try (OrtSession.Result encOut = encoder.run(encIn)) {
                Object hidden = encOut.get(encoderOutName).get().getValue();
                try (OnnxTensor hiddenTensor = OnnxTensor.createTensor(env, hidden)) {
                    int[] outIds = greedyDecode(hiddenTensor, maskTensor);
                    return tokenizer.decode(outIds);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "translate failed", e);
            return text;
        }
    }

    private int[] greedyDecode(OnnxTensor hidden, OnnxTensor encMask) throws Exception {
        int[] ys = new int[MAX_TGT];
        ys[0] = padId;
        int len = 1;
        for (int step = 1; step < MAX_TGT; step++) {
            long[] decIds = toLong(ys, len);
            try (OnnxTensor decTensor = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(decIds), new long[] {1, len})) {
                Map<String, OnnxTensor> decIn = new HashMap<>();
                decIn.put(decoderIdsName, decTensor);
                if (decoderHiddenName != null) {
                    decIn.put(decoderHiddenName, hidden);
                }
                if (decoderMaskName != null) {
                    decIn.put(decoderMaskName, encMask);
                }
                try (OrtSession.Result decOut = decoder.run(decIn)) {
                    float[][][] logits = (float[][][]) decOut.get(decoderLogitsName).get().getValue();
                    float[] last = logits[0][len - 1];
                    int next = argmaxSkip(last, padId);
                    if (next == eosId) {
                        break;
                    }
                    ys[len] = next;
                    len++;
                }
            }
        }
        int[] cut = new int[len];
        System.arraycopy(ys, 0, cut, 0, len);
        return cut;
    }

    private static int argmaxSkip(float[] logits, int skip) {
        int best = 0;
        float bestV = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (i == skip) {
                continue;
            }
            if (logits[i] > bestV) {
                bestV = logits[i];
                best = i;
            }
        }
        return best;
    }

    private static long[] toLong(int[] ids) {
        return toLong(ids, ids.length);
    }

    private static long[] toLong(int[] ids, int len) {
        long[] out = new long[len];
        for (int i = 0; i < len; i++) {
            out[i] = ids[i];
        }
        return out;
    }

    private static String pick(java.util.Set<String> names, String prefer) {
        if (names.contains(prefer)) {
            return prefer;
        }
        if (names.isEmpty()) {
            return prefer;
        }
        return names.iterator().next();
    }

    private static String pickContains(java.util.Set<String> names, String... keys) {
        for (String key : keys) {
            for (String name : names) {
                if (name.equals(key) || name.contains(key)) {
                    return name;
                }
            }
        }
        return names.isEmpty() ? null : names.iterator().next();
    }

    private static File copyPair(Context context, String pairId) throws Exception {
        File dest = new File(context.getFilesDir(), "translate/" + pairId);
        dest.mkdirs();
        AssetManager assets = context.getAssets();
        String prefix = "translate/" + pairId + "/";
        String[] files = {"encoder.onnx", "decoder.onnx", "pieces.tsv", "config.json"};
        for (String name : files) {
            File out = new File(dest, name);
            if (out.exists() && out.length() > 64) {
                continue;
            }
            try (InputStream in = assets.open(prefix + name);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    fos.write(buf, 0, n);
                }
            }
        }
        return dest;
    }

    private static String readUtf8(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }
}
