package com.yagay.ybrowser.loader.hook;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/** Browser-process read-only settings access. No external feature APKs. */
final class BrowserSettingsClient {
    private static final Uri URI =
            Uri.parse("content://com.yagay.ybrowser.loader.settings");
    private BrowserSettingsClient() {}

    static boolean tabsEnabled(Activity activity) {
        try {
            Bundle data = activity.getContentResolver()
                    .call(URI, "status", null, null);
            if (data == null || !data.containsKey("tabs_enabled")) {
                Log.w("YBrowserSettings", "App settings provider unavailable");
                return false;
            }
            return data.getBoolean("tabs_enabled", true);
        } catch (RuntimeException e) {
            Log.w("YBrowserSettings", "Unable to read settings from YBrowser app", e);
            return false; // Do not inject UI on ambiguous state.
        }
    }
}
