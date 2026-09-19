package com.spatiallauncher.app.ui;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.Manifest;
import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.spatiallauncher.app.R;

import java.io.File;
import java.util.UUID;

/**
 * Hosts {@link BookImportHttp} so the desktop Novel Translator can push EPUBs
 * over Wi‑Fi (POST /import). Started only while the panel is resumed — not an
 * FGS, so opening a game cannot crash us with foreground-service restrictions.
 */
public class BookImportService extends Service {

    private static final String CHANNEL_ID = "book_import";
    private static final int NOTIFICATION_ID = 87;
    private static final String PREFS = "book_import";
    private static final String KEY_TOKEN = "token";
    public static final String ACTION_BOOK_RECEIVED = "com.spatiallauncher.app.BOOK_RECEIVED";
    public static final String ACTION_IMPORT_READY = "com.spatiallauncher.app.IMPORT_READY";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TOKEN = "token";

    public class LocalBinder extends Binder {
        BookImportService getService() {
            return BookImportService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BookImportHttp http;
    private String lanUrl = "";
    private String token = "";

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        token = loadOrCreateToken();
        postNotification(getString(R.string.book_import_starting));
        http = new BookImportHttp(this, token, BookImportHttp.DEFAULT_PORT, new BookImportHttp.Listener() {
            @Override
            public void onBookImported(File epubFile, String displayName) {
                Intent intent = new Intent(ACTION_BOOK_RECEIVED);
                intent.setPackage(getPackageName());
                intent.putExtra(EXTRA_PATH, epubFile.getAbsolutePath());
                intent.putExtra(EXTRA_NAME, displayName);
                sendBroadcast(intent);
            }

            @Override
            public void onServerReady(String url, String importToken) {
                lanUrl = url;
                token = importToken;
                main.post(() -> postNotification(getString(R.string.book_import_ready, url)));
                Intent intent = new Intent(ACTION_IMPORT_READY);
                intent.setPackage(getPackageName());
                intent.putExtra(EXTRA_URL, url);
                intent.putExtra(EXTRA_TOKEN, importToken);
                sendBroadcast(intent);
            }

            @Override
            public void onServerError(String message) {
                main.post(() -> postNotification(getString(R.string.book_import_error, message)));
            }
        });
        http.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (http != null) {
            http.stop();
            http = null;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
        super.onDestroy();
    }

    String getLanUrl() {
        return lanUrl;
    }

    String getToken() {
        return token;
    }

    boolean isHttpRunning() {
        return http != null && http.isRunning();
    }

    private String loadOrCreateToken() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_TOKEN, "");
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String created = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        prefs.edit().putString(KEY_TOKEN, created).apply();
        return created;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.book_import_channel),
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(false)
                .build();
    }

    // POST_NOTIFICATIONS is intentionally undeclared (Security.2 trim): import
    // progress already shows in the panel UI, the shade copy is best-effort.
    @SuppressLint("NotificationPermission")
    private void postNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
