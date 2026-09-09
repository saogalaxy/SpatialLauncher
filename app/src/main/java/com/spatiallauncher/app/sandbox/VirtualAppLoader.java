package com.spatiallauncher.app.sandbox;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Constructor;

import dalvik.system.DexClassLoader;

/**
 * In-process load stub. Tries DexClassLoader against an optional APK path, then
 * falls back to a local demo host so the harness can run without a guest APK.
 */
public final class VirtualAppLoader {
    private static final String TAG = "VirtualAppLoader";

    public static final String EXTRA_APK_PATH = "sandbox_apk_path";
    public static final String EXTRA_VIEW_CLASS = "sandbox_view_class";

    private VirtualAppLoader() {}

    public static View load(Context context, ViewGroup host, String apkPath, String viewClassName) {
        View fromApk = tryLoadFromApk(context, apkPath, viewClassName);
        if (fromApk != null) {
            return fromApk;
        }
        return createDemoHost(context);
    }

    private static View tryLoadFromApk(Context context, String apkPath, String viewClassName) {
        if (apkPath == null || apkPath.isEmpty() || viewClassName == null || viewClassName.isEmpty()) {
            return null;
        }
        File apk = new File(apkPath);
        if (!apk.isFile()) {
            Log.w(TAG, "APK not found: " + apkPath);
            return null;
        }
        try {
            File dexOut = new File(context.getCodeCacheDir(), "virtual_sandbox_dex");
            if (!dexOut.exists() && !dexOut.mkdirs()) {
                Log.w(TAG, "Could not create dex cache");
                return null;
            }
            DexClassLoader loader = new DexClassLoader(
                    apk.getAbsolutePath(),
                    dexOut.getAbsolutePath(),
                    null,
                    context.getClassLoader());
            Class<?> cls = loader.loadClass(viewClassName);
            Constructor<?> ctor = cls.getConstructor(Context.class);
            Object instance = ctor.newInstance(context);
            if (instance instanceof View) {
                Log.i(TAG, "Loaded guest view " + viewClassName);
                return (View) instance;
            }
            Log.w(TAG, viewClassName + " is not a View");
        } catch (Throwable t) {
            Log.w(TAG, "DexClassLoader stub failed; using demo host", t);
        }
        return null;
    }

    /** Colorful scrolling content so MiDaS has edges/depth to work with. */
    static View createDemoHost(Context context) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16));

        int[][] palettes = {
                {0xFF1E3A5F, 0xFF3D7EA6},
                {0xFF14532D, 0xFF4ADE80},
                {0xFF7C2D12, 0xFFF97316},
                {0xFF4C1D95, 0xFFA78BFA},
                {0xFF0F172A, 0xFF38BDF8},
        };
        String[] labels = {
                "Near card — tap me",
                "Mid card — scroll",
                "Far card — depth test",
                "Accent card",
                "Bottom card"
        };
        for (int i = 0; i < palettes.length; i++) {
            TextView card = new TextView(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 140));
            lp.bottomMargin = dp(context, 12);
            card.setLayoutParams(lp);
            card.setGravity(Gravity.CENTER);
            card.setText(labels[i]);
            card.setTextColor(Color.WHITE);
            card.setTextSize(18f);
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    palettes[i]);
            bg.setCornerRadius(dp(context, 16));
            card.setBackground(bg);
            card.setClickable(true);
            final int index = i;
            card.setOnClickListener(v ->
                    android.widget.Toast.makeText(context, "Demo card " + (index + 1), android.widget.Toast.LENGTH_SHORT)
                            .show());
            column.addView(card);
        }
        scroll.addView(column);

        FrameLayout host = new FrameLayout(context);
        host.setBackgroundColor(0xFF0B1220);
        host.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        SandboxTextureHost textureHost = new SandboxTextureHost(context);
        FrameLayout.LayoutParams texLp = new FrameLayout.LayoutParams(dp(context, 120), dp(context, 80));
        texLp.gravity = Gravity.END | Gravity.TOP;
        texLp.setMargins(0, dp(context, 8), dp(context, 8), 0);
        host.addView(textureHost, texLp);
        return host;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
