package com.spatiallauncher.app.ui;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import ai.onnxruntime.OrtEnvironment;

import java.io.File;
import java.util.List;

/**
 * Runs OPUS-MT in a separate process so Microsoft ORT JNI is not mixed with
 * sherpa-onnx's libonnxruntime.so used by Piper in the UI process.
 */
public class TranslateMtService extends Service {
    static final int MSG_PING = 1;
    static final int MSG_READY = 2;
    static final int MSG_FAIL = 3;
    static final int MSG_TRANSLATE = 4;
    static final int MSG_RESULT = 5;

    static final String KEY_TEXT = "text";
    static final String KEY_ERROR = "error";
    static final String KEY_MS = "ms";

    private static final String TAG = "TranslateMt";

    private HandlerThread thread;
    private Messenger messenger;
    private OrtEnvironment env;
    private MarianOnnxPair jaEn;
    private MarianOnnxPair zhEn;
    private MarianOnnxPair koEn;
    private volatile boolean ready;
    private volatile String failReason;
    private volatile long loadMs;

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("opus-mt", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Handler handler = new Incoming(thread.getLooper());
        messenger = new Messenger(handler);
        handler.post(this::loadEngine);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onDestroy() {
        if (thread != null) {
            thread.quitSafely();
        }
        super.onDestroy();
    }

    private void loadEngine() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            File so = OfflineModelPack.extractMicrosoftOrt(this);
            System.load(so.getAbsolutePath());
            Log.i(TAG, "loaded Microsoft ORT " + so.length() + " bytes");
            OfflineModelPack.unpackAll(this);
            env = OrtEnvironment.getEnvironment();
            jaEn = MarianOnnxPair.load(this, env, "jaen");
            ready = jaEn != null;
            failReason = ready ? null : "ja-en session was null";
            loadMs = SystemClock.elapsedRealtime() - t0;
            Log.i(TAG, "engine ready=" + ready + " in " + loadMs + "ms");
        } catch (Throwable t) {
            ready = false;
            failReason = t.getClass().getSimpleName() + ": " + t.getMessage();
            loadMs = SystemClock.elapsedRealtime() - t0;
            Log.w(TAG, "engine failed after " + loadMs + "ms", t);
        }
    }

    private String translateLine(String text) throws Exception {
        if (text == null || text.trim().isEmpty() || OnDeviceTranslator.looksPrimarilyEnglish(text)) {
            return text == null ? "" : text.trim();
        }
        MarianOnnxPair pair = pairFor(text);
        List<String> parts = OnDeviceTranslator.splitForModel(text.trim());
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            String piece = pair.translate(part);
            if (piece == null || piece.trim().isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(piece.trim());
        }
        if (joined.length() == 0) {
            return text.trim();
        }
        return OnDeviceTranslator.cleanMtEnglish(joined.toString());
    }

    private MarianOnnxPair pairFor(String text) throws Exception {
        String id = OfflineModelPack.pickCaptionPair(text);
        if ("zhen".equals(id)) {
            if (zhEn == null) {
                zhEn = MarianOnnxPair.load(this, env, "zhen");
            }
            return zhEn;
        }
        if ("koen".equals(id)) {
            if (koEn == null) {
                koEn = MarianOnnxPair.load(this, env, "koen");
            }
            return koEn;
        }
        return jaEn;
    }

    private final class Incoming extends Handler {
        Incoming(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_PING) {
                replyStatus(msg.replyTo);
                return;
            }
            if (msg.what == MSG_TRANSLATE) {
                handleTranslate(msg);
            }
        }

        private void handleTranslate(Message msg) {
            Messenger replyTo = msg.replyTo;
            String text = msg.getData() == null ? "" : msg.getData().getString(KEY_TEXT, "");
            Message out = Message.obtain(null, MSG_RESULT);
            out.arg1 = msg.arg1;
            Bundle data = new Bundle();
            try {
                if (!ready || jaEn == null) {
                    throw new IllegalStateException(failReason != null ? failReason : "not ready");
                }
                data.putString(KEY_TEXT, translateLine(text));
            } catch (Throwable t) {
                data.putString(KEY_ERROR, String.valueOf(t.getMessage()));
                data.putString(KEY_TEXT, text);
            }
            out.setData(data);
            sendQuiet(replyTo, out);
        }

        private void replyStatus(Messenger replyTo) {
            Message out = Message.obtain(null, ready ? MSG_READY : MSG_FAIL);
            Bundle data = new Bundle();
            data.putLong(KEY_MS, loadMs);
            if (failReason != null) {
                data.putString(KEY_ERROR, failReason);
            }
            out.setData(data);
            sendQuiet(replyTo, out);
        }

        private void sendQuiet(Messenger replyTo, Message out) {
            if (replyTo == null) {
                return;
            }
            try {
                replyTo.send(out);
            } catch (RemoteException ignored) {
            }
        }
    }
}
