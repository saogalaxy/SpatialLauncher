package com.spatiallauncher.app.ui;

/**
 * Reader extras. {@link #DEFAULT} is the existing OCR → Piper path (no translation).
 */
enum AssistMode {
    DEFAULT("default"),
    /** On-screen foreign subs/UI → English → Piper. */
    TRANSLATE("translate"),
    /** No subs: capture guest audio → STT → English → Piper. Forces 3D off. */
    LISTEN("listen"),
    /** Shared-screen world/UI text → English overlay (Piper only if auto-read is on). */
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
}
