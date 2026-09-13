package com.spatiallauncher.app.ui;

import com.spatiallauncher.app.R;

/**
 * Reader extras. Each mode is a fixed pipeline — Settings shows “Active: …” for the pick.
 * Translate / Share / Listen can optionally skip OPUS (ML Kit OCR / ASR → Piper).
 */
enum AssistMode {
    /** OCR → Piper (no OPUS). English / as-read on-screen text. */
    DEFAULT("default"),
    /** OCR → OPUS → Piper when OPUS engine is on. */
    TRANSLATE("translate"),
    /** Cast audio → STT → OPUS → Piper when OPUS engine is on. Forces 3D off. */
    LISTEN("listen"),
    /** OCR → OPUS → Piper + caption when OPUS engine is on. */
    SHARE("share");

    final String prefKey;

    AssistMode(String prefKey) {
        this.prefKey = prefKey;
    }

    static AssistMode fromPref(String key) {
        if (key == null) {
            return DEFAULT;
        }
        for (AssistMode mode : values()) {
            if (mode.prefKey.equals(key)) {
                return mode;
            }
        }
        return DEFAULT;
    }

    /** Pipeline string under the mode buttons; {@code useOpus} is the Translate-engine switch. */
    int pipelineStringRes(boolean useOpus) {
        switch (this) {
            case TRANSLATE:
                return useOpus
                        ? R.string.assist_pipeline_translate
                        : R.string.assist_pipeline_translate_ocr;
            case LISTEN:
                return useOpus
                        ? R.string.assist_pipeline_listen
                        : R.string.assist_pipeline_listen_ocr;
            case SHARE:
                return useOpus
                        ? R.string.assist_pipeline_share
                        : R.string.assist_pipeline_share_ocr;
            case DEFAULT:
            default:
                return R.string.assist_pipeline_read;
        }
    }
}
