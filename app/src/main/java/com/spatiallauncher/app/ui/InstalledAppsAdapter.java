package com.spatiallauncher.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.spatiallauncher.app.R;

import java.util.List;

/** Backs the "Add Game" picker dialog's list of installed, launchable apps. */
public class InstalledAppsAdapter extends ArrayAdapter<InstalledAppInfo> {

    public InstalledAppsAdapter(Context context, List<InstalledAppInfo> apps) {
        super(context, 0, apps);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        if (view == null) {
            view = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_installed_app, parent, false);
        }
        InstalledAppInfo app = getItem(position);
        if (app != null) {
            ((ImageView) view.findViewById(R.id.app_icon)).setImageDrawable(app.icon);
            ((TextView) view.findViewById(R.id.app_label)).setText(app.label);
        }
        return view;
    }
}
