package com.spatiallauncher.app.ui;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.spatiallauncher.app.R;

/**
 * Full-text status. Quest toasts clip to a few characters; this writes the in-app
 * banner and a system notification with the complete string.
 */
final class PanelAlerts {
    private static final String CHANNEL_ID = "launcher_status";
    private static final int NOTIFY_ID = 71;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile TextView banner;
    private static final Runnable hideBanner = () -> {
        TextView view = banner;
        if (view != null) {
            view.setVisibility(View.GONE);
        }
    };

    static void bindBanner(TextView view) {
        banner = view;
    }

    static void show(Context context, int stringRes) {
        if (context == null) {
            return;
        }
        show(context, context.getString(stringRes));
    }

    static void show(Context context, CharSequence message) {
        if (context == null || message == null) {
            return;
        }
        String text = message.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            TextView view = banner;
            if (view != null) {
                view.setText(text);
                view.setVisibility(View.VISIBLE);
                MAIN.removeCallbacks(hideBanner);
                MAIN.postDelayed(hideBanner, 12000);
            }
        });
        postShade(app, text);
    }

    private static void postShade(Context app, String text) {
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
                if (channel == null) {
                    channel = new NotificationChannel(
                            CHANNEL_ID,
                            app.getString(R.string.panel_alerts_channel),
                            NotificationManager.IMPORTANCE_DEFAULT);
                    channel.setDescription(app.getString(R.string.panel_alerts_channel));
                    manager.createNotificationChannel(channel);
                }
            }
            manager.notify(NOTIFY_ID, new NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(app.getString(R.string.app_name))
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    private PanelAlerts() {
    }
}
