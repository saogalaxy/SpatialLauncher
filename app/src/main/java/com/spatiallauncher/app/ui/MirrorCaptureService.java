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

import androidx.core.app.NotificationCompat;

import com.spatiallauncher.app.R;

/**
 * Minimal foreground service whose only job is to satisfy the platform requirement that
 * MediaProjection.createVirtualDisplay() only be called while a foreground service of
 * type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is running (enforced on this Horizon OS
 * build — see the SecurityException this fixes: "Media projections require a foreground
 * service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION").
 *
 * PanelMainActivity binds to this *before* touching MediaProjection each time, which
 * guarantees startForeground() below has already run (Service.onCreate() completes
 * before onServiceConnected() fires) before any capture APIs are called.
 */
public class MirrorCaptureService extends Service {

    private static final String CHANNEL_ID = "mirror_capture";
    private static final int NOTIFICATION_ID = 42;

    public class LocalBinder extends Binder {
        MirrorCaptureService getService() {
            return MirrorCaptureService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Game mirror", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.mirror_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }
}
