package com.spatiallauncher.app.ui;

import android.graphics.drawable.Drawable;

/** Minimal view of an installed, launchable app: what the dock and the "Add Game" picker
 *  dialog need to display and to relaunch it later. */
public class InstalledAppInfo {
    public final String packageName;
    public final String label;
    public final Drawable icon;

    public InstalledAppInfo(String packageName, String label, Drawable icon) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
    }
}
