package com.spatiallauncher.app.ui;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads extracted screen dialogue. Prefers bundled Piper (Horizon system TTS does not work).
 */
final class ScreenDialogueReader implements TextToSpeech.OnInitListener {
    private static final String TAG = "ScreenDialogueReader";
    private static final String UTTERANCE_ID = "DialogueID";

    private TextToSpeech tts;
    private final AtomicBoolean androidReady = new AtomicBoolean(false);
    private final DialogueDeduper deduper = new DialogueDeduper();
    private final PiperTtsEngine piper;

    ScreenDialogueReader(Context context) {
        piper = PiperTtsEngine.get(context);
        tts = new TextToSpeech(context, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int lang = tts.setLanguage(Locale.US);
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Android TTS language missing; Piper is the primary engine");
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                }

                @Override
                public void onError(String utteranceId) {
                    Log.w(TAG, "TTS utterance error id=" + utteranceId);
                }
            });
            androidReady.set(true);
        } else {
            androidReady.set(false);
            Log.i(TAG, "Android TTS unavailable; waiting for Piper");
        }
    }

    void speakDialogue(String dialogueText) {
        speakDialogue(dialogueText, false);
    }

    void speakDialogue(String dialogueText, boolean force) {
        if (dialogueText == null) {
            return;
        }
        if (!force && !deduper.isNew(dialogueText)) {
            return;
        }
        if (force) {
            deduper.isNew(dialogueText);
        }
        String line = dialogueText.trim();
        if (piper.speak(line, force)) {
            return;
        }
        speakAndroidNative(line);
    }

    private void speakAndroidNative(String line) {
        if (!androidReady.get() || tts == null) {
            return;
        }
        int result = tts.speak(line, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID);
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Android TTS speak() failed: " + result);
        }
    }

    void shutdown() {
        androidReady.set(false);
        piper.stopSpeaking();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
