package com.yagay.ybrowser.api;

import android.app.Activity;

/**
 * Binary contract shared by the Loader APK and separately compiled feature APKs.
 * Feature APKs must use compileOnly for plugin-api to avoid bundling another copy.
 * A feature should modify only the target's own UI; no Chromium C++ hooks are provided.
 */
public interface BrowserFeature {
    /** Called on the UI thread after a supported browser activity resumes. */
    void onBrowserActivityResumed(Activity activity, String browserPackage);
}
