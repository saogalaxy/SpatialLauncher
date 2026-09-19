package com.spatiallauncher.app.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.spatiallauncher.app.R;

/**
 * Satisfies the platform rule that MediaProjection.createVirtualDisplay() only runs
 * while a foreground service of type {@code FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}
 * is active.
 *
 * <p>targetSdk 34 / Horizon OS ordering: {@code getMediaProjection()} is validated
 * server-side against a live FGS of this type, so {@link #enterProjectionForeground()}
 * must run BEFORE the capture intent returns — {@code PanelMainActivity.launchGame()}
 * promotes at tap time (foreground, so no background-start restriction), keeps it
 * across the share-sheet grant, and drops it on dismiss ({@code onActivityResult})
 * or session stop ({@code stopMirroring()}). Promoting in {@code onCreate()} was
 * unreliable (not necessarily foreground yet); promoting only after the grant is
 * too late ({@code SecurityException} from {@code getMediaProjection()}).
 */
public class MirrorCaptureService extends Service {
    private static final String TAG = "MirrorCaptureService";
    private static final String CHANNEL_ID = "mirror_capture";
    private static final int NOTIFICATION_ID = 42;

    public class LocalBinder extends Binder {
        MirrorCaptureService getService() {
            return MirrorCaptureService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private boolean projectionForeground;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        // Intentionally no startForeground here — see class doc (targetSdk 34).
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Promote to mediaProjection FGS. Safe to call repeatedly; no-op if already up.
     * Must run while the app is foreground and BEFORE getMediaProjection().
     */
    public void enterProjectionForeground() {
        if (projectionForeground) {
            return;
        }
        ensureChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.mirror_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        try {
            // minSdk 29: the typed startForeground() exists on all supported OS
            // versions, so no version branch is needed.
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            projectionForeground = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "enterProjectionForeground failed", e);
            throw e;
        }
    }

    /** Drop FGS when mirroring ends (bound service can stay alive for the next cast). */
    public void leaveProjectionForeground() {
        if (!projectionForeground) {
            return;
        }
        try {
            // minSdk 29 (always >= N): STOP_FOREGROUND_REMOVE exists everywhere.
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException e) {
            Log.w(TAG, "leaveProjectionForeground", e);
        }
        projectionForeground = false;
    }

    private void ensureChannel() {
        // minSdk 29 (always >= O): channels always exist; manager is non-null
        // on a running service, but keep the null-guard (cheap, runs once).
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Game mirror", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        leaveProjectionForeground();
        super.onDestroy();
    }
}
