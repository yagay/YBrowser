package com.yagay.ybrowser.loader.settings;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Read-only settings bridge for two LSPosed-scoped browser packages. */
public final class BrowserSettingsProvider extends ContentProvider {
    public static final String PREFS = "ybrowser.settings";
    public static final String TABS_ENABLED = "tabs_enabled";

    private void checkCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if ("org.cromite.cromite".equals(pkg)
                        || "com.brave.browser".equals(pkg)) return;
            }
        }
        throw new SecurityException("Only scoped browsers can read YBrowser settings");
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        checkCaller();
        if (!"status".equals(method)) return Bundle.EMPTY;
        Context context = getContext();
        Bundle response = new Bundle();
        response.putBoolean(TABS_ENABLED, context.getSharedPreferences(PREFS,
                Context.MODE_PRIVATE).getBoolean(TABS_ENABLED, true));
        return response;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
