package com.spatiallauncher.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Step 2 — extract dialogue / UI text from a screen snapshot.
 * <p>
 * Option A: on-device lightweight vision AI (Qwen2.5-VL 3B or Gemma Vision, quantized).
 * Pros: handles stylized/fantasy fonts, game text boxes, low-contrast text, and complex
 * UI layouts much better than OCR. The screen crop is passed straight to llama.cpp /
 * ExecuTorch / ONNX. Weights are optional (multi-GB) and not bundled; if the runtime
 * is missing, OCR is only a fallback.
 * <p>
 * Cons: about 500ms–1.5s per frame, depending on model size and GPU/NPU load.
 * <p>
 * Option B: fast on-device OCR (Google ML Kit, with PaddleOCR as a drop-in later).
 * How it works: traditional text recognition locally in about 20–50ms per frame.
 * Pros: blazing fast, minimal battery and thermal overhead on Quest.
 * Cons: struggles with stylized text, heavy anime dialogue fonts, and type
 * blended into busy background textures (use Option A for those).
 * Use this when latency and thermals matter more than stylized-font robustness.
 * <p>
 * Key technical challenges and solutions:
 * Avoiding repeating the same text (deduplication): hash + Levenshtein vs last
 * spoken line; only {@code tts.speak} when the new text differs significantly.
 * System insets and UI cropping: strip status/nav (scaled onto the snapshot)
 * so OCR/VLM does not read panel chrome.
 * Full-screen crops waste VLM/OCR on HUD bars and status icons — keep the
 * lower-center subtitle band (above the player chrome).
 * Performance on mobile/VR: cap reader snapshots at ~5 FPS. Lightweight OCR is
 * the trigger; Vision model and TTS run only when text appears or changes in the
 * bottom-30% dialogue ROI (never a continuous VLM beside the game).
 */
final class DialogueTextExtractor {
    private static final String TAG = "DialogueTextExtractor";

    static final String OPTION_A_PROMPT =
            "Look at this game screenshot. Extract only the spoken dialogue text inside "
                    + "the speech bubble/dialogue box and return it as plain text. Do not summarize.";

    enum ReadingStepMode {
        /** Option A — quantized on-device VLM when weights exist. */
        OPTION_A_ON_DEVICE_VLM,
        /** Option B — fast on-device OCR (ML Kit / PaddleOCR). */
        OPTION_B_FAST_OCR
    }

    interface Listener {
        void onDialogueText(String text);

        /** Tap-to-speak — re-read even if the caption has not changed. */
        default void onDialogueTextForced(String text) {
            onDialogueText(text);
        }
    }

    private final Context appContext;
    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private TextRecognizer japaneseRecognizer;
    private TextRecognizer chineseRecognizer;
    private TextRecognizer koreanRecognizer;
    private final OnDeviceTranslator translator;
    private volatile boolean translateToEnglish;
    private volatile boolean cjkOcr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final DialogueDeduper deduper = new DialogueDeduper();
    private volatile String lastText = "";
    private volatile ReadingStepMode mode = ReadingStepMode.OPTION_A_ON_DEVICE_VLM;
    private final QuantizedVisionRuntime visionRuntime;
    private BooleanSupplier deferHeavyVision;
    private Listener listener;
    /** Queued tap-to-speak frame when OCR is already busy. */
    private Bitmap pendingForcedFrame;

    DialogueTextExtractor(Context context) {
        appContext = context.getApplicationContext();
        visionRuntime = new QuantizedVisionRuntime(appContext);
        translator = OnDeviceTranslator.get(appContext);
        Log.i(TAG, "Option A crop→VLM backend=" + visionRuntime.backend());
    }

    void setMode(ReadingStepMode mode) {
        this.mode = mode != null ? mode : ReadingStepMode.OPTION_A_ON_DEVICE_VLM;
    }

    void setTranslateToEnglish(boolean enabled) {
        translateToEnglish = enabled;
        if (enabled) {
            translator.ensureReady(ok -> { });
        }
    }

    void setCjkOcr(boolean enabled) {
        cjkOcr = enabled;
    }

    long recommendedCaptureIntervalMs() {
        long modeMs = mode == ReadingStepMode.OPTION_B_FAST_OCR
                ? ScreenFrameCapture.OPTION_B_PERIODIC_MS
                : ScreenFrameCapture.OPTION_A_PERIODIC_MS;
        // Quest: never feed the reader faster than ~5 FPS.
        return Math.max(modeMs, ScreenFrameCapture.VR_READER_INTERVAL_MS);
    }

    void setDeferHeavyVision(BooleanSupplier deferHeavyVision) {
        this.deferHeavyVision = deferHeavyVision;
    }

    ReadingStepMode mode() {
        return mode;
    }

    boolean hasQuantizedVlmAsset() {
        return visionRuntime.backend() != QuantizedVisionRuntime.Backend.NONE;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    String lastText() {
        return lastText;
    }

    void analyze(Bitmap frame) {
        analyze(frame, true);
    }

    /**
     * Best setup: lightweight OCR/text detection is the trigger. Vision model and
     * TTS run only when text appears or changes inside the dialogue ROI.
     */
    void analyze(Bitmap frame, boolean allowHeavyVision) {
        runOcrTriggerThenMaybeVlm(frame, false);
    }

    /** One-shot OCR for tap-to-speak — always emits even if text matches the last line. */
    void analyzeForced(Bitmap frame) {
        if (frame == null || frame.isRecycled()) {
            return;
        }
        Bitmap copy;
        try {
            copy = frame.copy(
                    frame.getConfig() != null ? frame.getConfig() : Bitmap.Config.ARGB_8888,
                    false);
        } catch (RuntimeException e) {
            return;
        }
        if (copy == null) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            // Don't drop the user's tap — run as soon as the current OCR finishes.
            synchronized (this) {
                if (pendingForcedFrame != null) {
                    pendingForcedFrame.recycle();
                }
                pendingForcedFrame = copy;
            }
            return;
        }
        processCopiedFrame(copy, true);
    }

    private void runOcrTriggerThenMaybeVlm(Bitmap frame, boolean forceEmit) {
        if (frame == null || frame.isRecycled() || !busy.compareAndSet(false, true)) {
            return;
        }
        Bitmap copy;
        try {
            copy = frame.copy(
                    frame.getConfig() != null ? frame.getConfig() : Bitmap.Config.ARGB_8888,
                    false);
        } catch (RuntimeException e) {
            busy.set(false);
            return;
        }
        if (copy == null) {
            busy.set(false);
            return;
        }
        processCopiedFrame(copy, forceEmit);
    }

    private void processCopiedFrame(Bitmap copy, boolean forceEmit) {
        InputImage image = InputImage.fromBitmap(copy, 0);
        recognizer.process(image)
                .addOnSuccessListener(latin -> {
                    String parsed = parseDialogue(latin);
                    if (!cjkOcr) {
                        finishParsed(copy, parsed, forceEmit);
                        return;
                    }
                    japanese().process(image)
                            .addOnSuccessListener(jp -> {
                                String merged = pickRicher(parsed, parseDialogue(jp));
                                chinese().process(image)
                                        .addOnSuccessListener(zh -> {
                                            String withZh = pickRicher(merged, parseDialogue(zh));
                                            korean().process(image)
                                                    .addOnSuccessListener(ko -> {
                                                        finishParsed(
                                                                copy,
                                                                pickRicher(withZh, parseDialogue(ko)),
                                                                forceEmit);
                                                    })
                                                    .addOnFailureListener(e ->
                                                            finishParsed(copy, withZh, forceEmit));
                                        })
                                        .addOnFailureListener(e -> finishParsed(copy, merged, forceEmit));
                            })
                            .addOnFailureListener(e -> finishParsed(copy, parsed, forceEmit));
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "OCR trigger failed", e);
                    finishFrame(copy);
                });
    }

    private void finishParsed(Bitmap copy, String parsed, boolean forceEmit) {
        try {
            if (parsed == null || parsed.isEmpty()) {
                return;
            }
            if (!forceEmit && !deduper.wouldBeNew(parsed)) {
                return;
            }
            String out = parsed;
            boolean gpuBusy = deferHeavyVision != null && deferHeavyVision.getAsBoolean();
            if (mode == ReadingStepMode.OPTION_A_ON_DEVICE_VLM && !gpuBusy) {
                String vlm = visionRuntime.infer(copy, OPTION_A_PROMPT);
                if (vlm != null && !vlm.trim().isEmpty()) {
                    out = vlm.trim();
                }
            }
            if (translateToEnglish && !OnDeviceTranslator.looksPrimarilyEnglish(out)) {
                final boolean forced = forceEmit;
                translator.toEnglish(out, en -> {
                    if (forced) {
                        emitForced(en);
                    } else {
                        emitIfNew(en);
                    }
                });
                return;
            }
            if (forceEmit) {
                emitForced(out);
            } else {
                emitIfNew(out);
            }
        } finally {
            finishFrame(copy);
        }
    }

    private void finishFrame(Bitmap copy) {
        copy.recycle();
        Bitmap nextForced;
        synchronized (this) {
            nextForced = pendingForcedFrame;
            pendingForcedFrame = null;
        }
        if (nextForced != null) {
            processCopiedFrame(nextForced, true);
        } else {
            busy.set(false);
        }
    }

    private static String pickRicher(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (right.length() > left.length()) {
            return right;
        }
        return left;
    }

    private boolean emitIfNew(String raw) {
        if (raw == null || !deduper.isNew(raw)) {
            return false;
        }
        lastText = raw.trim();
        Log.i(TAG, "Dialogue (deduped): " + lastText);
        Listener sink = listener;
        if (sink != null) {
            String spoken = lastText;
            main.post(() -> sink.onDialogueText(spoken));
        }
        return true;
    }

    private void emitForced(String raw) {
        if (raw == null) {
            return;
        }
        String line = raw.trim();
        if (line.isEmpty()) {
            return;
        }
        deduper.isNew(line); // keep continuous dedupe in sync
        lastText = line;
        Log.i(TAG, "Dialogue (forced): " + lastText);
        Listener sink = listener;
        if (sink != null) {
            String spoken = lastText;
            main.post(() -> sink.onDialogueTextForced(spoken));
        }
    }

    private synchronized TextRecognizer japanese() {
        if (japaneseRecognizer == null) {
            japaneseRecognizer = TextRecognition.getClient(
                    new JapaneseTextRecognizerOptions.Builder().build());
        }
        return japaneseRecognizer;
    }

    private synchronized TextRecognizer chinese() {
        if (chineseRecognizer == null) {
            chineseRecognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return chineseRecognizer;
    }

    private synchronized TextRecognizer korean() {
        if (koreanRecognizer == null) {
            koreanRecognizer = TextRecognition.getClient(
                    new KoreanTextRecognizerOptions.Builder().build());
        }
        return koreanRecognizer;
    }

    void close() {
        listener = null;
        recognizer.close();
        if (japaneseRecognizer != null) {
            japaneseRecognizer.close();
        }
        if (chineseRecognizer != null) {
            chineseRecognizer.close();
        }
        if (koreanRecognizer != null) {
            koreanRecognizer.close();
        }
    }

    static String parseDialogue(Text visionText) {
        if (visionText == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        if (visionText.getTextBlocks() != null) {
            for (Text.TextBlock block : visionText.getTextBlocks()) {
                String piece = block.getText();
                if (isPlayerChrome(piece)) {
                    continue;
                }
                if (kept.length() > 0) {
                    kept.append('\n');
                }
                kept.append(piece.trim());
            }
        }
        String raw = kept.length() > 0 ? kept.toString() : visionText.getText();
        if (raw == null) {
            return "";
        }
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (isPlayerChrome(line)) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString().trim();
    }

    /** Progress bar, quality, CC — not spoken dialogue. */
    static boolean isPlayerChrome(String line) {
        if (line == null) {
            return true;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return true;
        }
        String n = t.replace(" ", "").toLowerCase();
        if (n.equals("cc") || n.equals("hd") || n.equals("4k") || n.equals("auto")) {
            return true;
        }
        if (n.matches("\\d{3,4}p")) {
            return true;
        }
        if (t.matches("(?i).*\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}.*")) {
            return true;
        }
        if (t.matches("\\d{1,2}:\\d{2}")) {
            return true;
        }
        return t.length() <= 2 && t.equals(t.toUpperCase());
    }
}
