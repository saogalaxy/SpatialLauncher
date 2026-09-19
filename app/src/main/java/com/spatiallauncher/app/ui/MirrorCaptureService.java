package com.spatiallauncher.app.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.spatiallauncher.app.R;

/**
 * Satisfies the platform rule that MediaProjection.createVirtualDisplay() only runs
 * while a foreground service of type {@code FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION}
 * is active.
 *
 * <p>targetSdk 34+: do <b>not</b> call {@code startForeground(MEDIA_PROJECTION)} before
 * the user has granted a MediaProjection token — the system throws
 * {@link SecurityException} / {@code ForegroundServiceStartNotAllowedException}.
 * Bind early (so the service process exists), then call {@link #enterProjectionForeground()}
 * only after {@code getMediaProjection()} succeeds and before {@code createVirtualDisplay()}.
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
     * Must run after a successful {@code getMediaProjection()}.
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "leaveProjectionForeground", e);
        }
        projectionForeground = false;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
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
