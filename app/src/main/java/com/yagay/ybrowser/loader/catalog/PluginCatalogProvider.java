package com.yagay.ybrowser.loader.catalog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only bridge across Android app sandboxes.
 * Only our own UID and official Cromite/Brave UIDs can read the catalog/APKs.
 * Only installed, enabled and SAME-SIGNED feature APKs can be opened.
 */
public final class PluginCatalogProvider extends ContentProvider {
    public static final Uri BASE = Uri.parse("content://" + PluginRepository.AUTHORITY);

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        PackageManager pm = getContext().getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages != null) {
            for (String pkg : packages) {
                if ("org.cromite.cromite".equals(pkg) || "com.brave.browser".equals(pkg)) return;
            }
        }
        throw new SecurityException("Only scoped Chromium browsers may read feature code");
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        if (!"catalog".equals(method)) return Bundle.EMPTY;
        ArrayList<Bundle> features = new ArrayList<>();
        for (PluginRepository.Entry entry : PluginRepository.scan(getContext(), false)) {
            features.add(entry.toBundle());
        }
        Bundle out = new Bundle();
        out.putParcelableArrayList("plugins", features);
        return out;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceCaller();
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only endpoint");
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !"apk".equals(segments.get(0))) {
            throw new FileNotFoundException("Bad feature URI");
        }
        String pkg = segments.get(1);
        boolean trusted = false;
        for (PluginRepository.Entry e : PluginRepository.scan(getContext(), false)) {
            if (e.packageName.equals(pkg)) { trusted = true; break; }
        }
        if (!trusted) throw new FileNotFoundException("Feature disabled or unsigned");
        try {
            ApplicationInfo info = getContext().getPackageManager().getApplicationInfo(pkg, 0);
            return ParcelFileDescriptor.open(new File(info.sourceDir),
                    ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (PackageManager.NameNotFoundException e) {
            throw new FileNotFoundException("Feature no longer installed");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
