package com.spatiallauncher.app.sandbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import com.spatiallauncher.app.R;
import com.spatiallauncher.app.ui.InstalledAppInfo;
import com.spatiallauncher.app.ui.InstalledAppsAdapter;
import com.spatiallauncher.app.ui.MirrorCaptureService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shows a real app the same way the main panel does: launch it, then MediaProjection
 * AUTO_MIRROR into an ImageView. Horizon will not let us embed another app's Activity
 * on a private display.
 */
final class SandboxAppMirror {
    private static final String TAG = "SandboxAppMirror";
    static final int REQUEST_CAPTURE = 7101;

    private final Activity activity;
    private ImageView preview;
    private final MediaProjectionManager projectionManager;
    private final PackageManager packageManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private InstalledAppInfo pendingApp;
    private boolean serviceBound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    void setPreview(ImageView preview) {
        this.preview = preview;
    }

    SandboxAppMirror(Activity activity) {
        this.activity = activity;
        this.preview = null;
        this.packageManager = activity.getPackageManager();
        this.projectionManager =
                (MediaProjectionManager) activity.getSystemService(Activity.MEDIA_PROJECTION_SERVICE);
        activity.bindService(
                new Intent(activity, MirrorCaptureService.class),
                connection,
                Activity.BIND_AUTO_CREATE);
    }

    void pickAndShowApp() {
        List<InstalledAppInfo> apps = listLaunchableApps();
        if (apps.isEmpty()) {
            Toast.makeText(activity, R.string.sandbox_no_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        InstalledAppsAdapter adapter = new InstalledAppsAdapter(activity, apps);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.sandbox_pick_app)
                .setAdapter(adapter, (dialog, which) -> startAppCapture(adapter.getItem(which)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void startAppCapture(InstalledAppInfo app) {
        if (app == null) {
            return;
        }
        Intent launch = packageManager.getLaunchIntentForPackage(app.packageName);
        if (launch == null) {
            Toast.makeText(activity, activity.getString(R.string.launch_failed_message, app.label),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serviceBound) {
            Toast.makeText(activity, R.string.sandbox_capture_wait, Toast.LENGTH_SHORT).show();
            return;
        }
        stopCapture();
        pendingApp = app;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(launch);
        Toast.makeText(activity, activity.getString(R.string.sandbox_pick_window, app.label),
                Toast.LENGTH_LONG).show();
        main.postDelayed(() -> {
            if (pendingApp == null) {
                return;
            }
            activity.startActivityForResult(
                    projectionManager.createScreenCaptureIntent(), REQUEST_CAPTURE);
        }, 1600);
    }

    void onCaptureResult(int resultCode, Intent data) {
        InstalledAppInfo app = pendingApp;
        pendingApp = null;
        if (app == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            return;
        }
        ensureThread();
        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int width = Math.max(1, metrics.widthPixels);
        int height = Math.max(1, metrics.heightPixels);
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onFrame, captureHandler);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "SandboxAppMirror",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);
        main.postDelayed(() -> {
            Intent front = new Intent(activity, VirtualSandboxTestActivity.class);
            front.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            activity.startActivity(front);
        }, 1200);
        Log.i(TAG, "Mirroring " + app.packageName);
    }

    private void onFrame(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            Bitmap frame = imageToBitmap(image);
            ImageView target = preview;
            if (target != null) {
                main.post(() -> target.setImageBitmap(frame));
            }
        } finally {
            image.close();
        }
    }

    private static Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        ByteBuffer buffer = plane.getBuffer();
        Bitmap wide = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        wide.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(wide, 0, 0, image.getWidth(), image.getHeight());
    }

    private List<InstalledAppInfo> listLaunchableApps() {
        String self = activity.getPackageName();
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = packageManager.queryIntentActivities(launcher, 0);
        List<InstalledAppInfo> result = new ArrayList<>();
        for (ResolveInfo info : infos) {
            String pkg = info.activityInfo.packageName;
            if (self.equals(pkg)) {
                continue;
            }
            result.add(new InstalledAppInfo(
                    pkg,
                    info.loadLabel(packageManager).toString(),
                    info.loadIcon(packageManager)));
        }
        Collections.sort(result, Comparator.comparing(a -> a.label.toLowerCase()));
        return result;
    }

    private void ensureThread() {
        if (captureThread == null) {
            captureThread = new HandlerThread("SandboxAppMirror");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());
        }
    }

    void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
    }

    void release() {
        stopCapture();
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        if (serviceBound) {
            try {
                activity.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
            serviceBound = false;
        }
    }
}
