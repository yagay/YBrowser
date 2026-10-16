package com.yagay.ybrowser.sample;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.yagay.ybrowser.api.BrowserFeature;

public final class SampleFeature implements BrowserFeature {
    private static final String TAG = "ybrowser.sample.feature";

    @Override public void onBrowserActivityResumed(Activity activity, String browserPackage) {
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) root;
        if (container.findViewWithTag(TAG) != null) return;

        TextView chip = new TextView(activity);
        chip.setTag(TAG);
        chip.setText("Y+");
        chip.setTextColor(Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(13f);
        int d = Math.round(activity.getResources().getDisplayMetrics().density * 38);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(25, 115, 96));
        bg.setCornerRadius(d / 2f);
        chip.setBackground(bg);
        chip.setElevation(6f * activity.getResources().getDisplayMetrics().density);
        chip.setContentDescription("YBrowser Sample Feature");
        chip.setOnClickListener(v -> Toast.makeText(activity,
                "YBrowser plugin loaded in " + browserPackage, Toast.LENGTH_SHORT).show());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d, d,
                Gravity.TOP | Gravity.RIGHT);
        int margin = Math.round(activity.getResources().getDisplayMetrics().density * 18);
        lp.topMargin = margin + d;
        lp.rightMargin = margin;
        if (container instanceof FrameLayout) container.addView(chip, lp);
    }
}
