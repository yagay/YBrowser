package com.yagay.ybrowser.loader.hook;

import android.app.Activity;
import android.util.Log;
import com.yagay.ybrowser.controls.BrowserControlsFeature;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * One stable Android framework hook instead of a brittle Chromium private-method hook.
 * Scope is restricted to the two official browser package names.
 */
public final class BrowserXposedEntry extends XposedModule {
    private static final String TAG = "YBrowserLoader";
    private boolean hookInstalled;

    @Override public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!"org.cromite.cromite".equals(pkg) && !"com.brave.browser".equals(pkg)) return;
        if (hookInstalled) return;
        hookInstalled = true;
        try {
            hook(Activity.class.getDeclaredMethod("onPostResume"))
                    .setId("ybrowser.loader.activity.resumed")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object target = chain.getThisObject();
                        if (target instanceof Activity) {
                            Activity activity = (Activity) target;
                            if (BrowserOverlay.isBrowserActivity(activity)) {
                                activity.runOnUiThread(() -> {
                                    try {
                                        BrowserOverlay.attach(activity);
                                        if (BrowserSettingsClient.tabsEnabled(activity)) {
                                            new BrowserControlsFeature()
                                                    .onBrowserActivityResumed(activity,
                                                            activity.getPackageName());
                                        } else {
                                            BrowserControlsFeature.detach(activity);
                                        }
                                    } catch (Throwable t) {
                                        Log.e(TAG, "Browser extension failed safely", t);
                                    }
                                });
                            }
                        }
                        return result;
                    });
            log(Log.INFO, TAG, "Entry hook attached for " + pkg);
        } catch (Throwable t) {
            hookInstalled = false;
            log(Log.ERROR, TAG, "Entry hook could not be installed for " + pkg, t);
        }
    }
}
