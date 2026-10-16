package com.yagay.ybrowser.loader.hook;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

/** Optional-looking 42dp in-browser entry; does not modify Chromium's toolbar views. */
final class BrowserOverlay {
    private static final String TAG = "ybrowser.loader.entry";
    private BrowserOverlay() {}

    static boolean isBrowserActivity(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        if (!"org.cromite.cromite".equals(activity.getPackageName())
                && !"com.brave.browser".equals(activity.getPackageName())) return false;
        Class<?> type = activity.getClass();
        while (type != null && type != Activity.class) {
            String name = type.getName();
            if ("org.chromium.chrome.browser.ChromeTabbedActivity".equals(name)
                    || "org.chromium.chrome.browser.BraveActivity".equals(name)
                    || "org.chromium.chrome.browser.ChromeTabbedActivity2".equals(name)
                    || name.endsWith(".ChromeTabbedActivity")
                    || name.endsWith(".BraveActivity")) return true;
            type = type.getSuperclass();
        }
        return false;
    }

    static void attach(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof FrameLayout)) return;
        FrameLayout content = (FrameLayout) root;
        if (content.findViewWithTag(TAG) != null) return;
        ImageButton button = new ImageButton(activity);
        button.setTag(TAG);
        button.setContentDescription("YBrowser module center");
        button.setImageResource(android.R.drawable.ic_menu_manage);
        button.setColorFilter(Color.WHITE);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(activity, 9), dp(activity, 9), dp(activity, 9), dp(activity, 9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(32, 76, 151));
        background.setCornerRadius(dp(activity, 14));
        button.setBackground(background);
        button.setAlpha(0.87f);
        button.setElevation(dp(activity, 5));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(activity, 42),
                dp(activity, 42), Gravity.RIGHT | Gravity.BOTTOM);
        p.rightMargin = dp(activity, 12);
        p.bottomMargin = dp(activity, 105);
        button.setOnClickListener(v -> {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.yagay.ybrowser.loader",
                    "com.yagay.ybrowser.loader.ui.MainActivity"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity, "Install or enable YBrowser Loader",
                        Toast.LENGTH_LONG).show();
            }
        });
        content.addView(button, p);
    }

    private static int dp(Activity a, int value) {
        return Math.round(value * a.getResources().getDisplayMetrics().density);
    }
}
