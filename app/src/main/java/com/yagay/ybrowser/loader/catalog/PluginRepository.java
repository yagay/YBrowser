package com.yagay.ybrowser.loader.catalog;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installed-package discovery runs only in the Loader's own Android process. */
public final class PluginRepository {
    public static final String ACTION = "com.yagay.ybrowser.loader.FEATURE";
    public static final String ENTRY_KEY = "ybrowser.feature.class";
    public static final String AUTHORITY = "com.yagay.ybrowser.loader.plugins";
    public static final String PREF_NAME = "ybrowser.loader.features";
    private PluginRepository() {}

    public static final class Entry {
        public final String packageName;
        public final String className;
        public final String label;
        public final String version;
        public final String sha256;
        public final boolean enabled;

        public Entry(String pkg, String className, String label, String version,
                     String sha256, boolean enabled) {
            this.packageName = pkg;
            this.className = className;
            this.label = label;
            this.version = version;
            this.sha256 = sha256;
            this.enabled = enabled;
        }

        public Bundle toBundle() {
            Bundle b = new Bundle();
            b.putString("package", packageName);
            b.putString("entry", className);
            b.putString("label", label);
            b.putString("version", version);
            b.putString("sha256", sha256);
            return b;
        }
    }

    public static boolean isEnabled(Context context, String pkg) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean("enabled_" + pkg, false);
    }

    public static void setEnabled(Context context, String pkg, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled_" + pkg, enabled).apply();
    }

    public static boolean isSameSigner(Context context, String pkg) {
        return context.getPackageManager().checkSignatures(context.getPackageName(), pkg)
                == PackageManager.SIGNATURE_MATCH;
    }

    public static List<Entry> scan(Context context, boolean includeDisabled) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(ACTION);
        List<ResolveInfo> matches = pm.queryIntentServices(intent, PackageManager.GET_META_DATA);
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo candidate : matches) {
            ServiceInfo service = candidate.serviceInfo;
            if (service == null || service.metaData == null) continue;
            String pkg = service.packageName;
            String className = service.metaData.getString(ENTRY_KEY);
            if (pkg == null || !pkg.startsWith("com.yagay.ybrowser.plugin.")
                    || !isValidEntry(className) || !seen.add(pkg)
                    || !isSameSigner(context, pkg)) continue;
            boolean enabled = isEnabled(context, pkg);
            if (!includeDisabled && !enabled) continue;
            try {
                android.content.pm.PackageInfo info = pm.getPackageInfo(pkg, 0);
                String path = info.applicationInfo.sourceDir;
                File apk = new File(path);
                if (!apk.isFile()) continue;
                String label = String.valueOf(info.applicationInfo.loadLabel(pm));
                entries.add(new Entry(pkg, className, label, info.versionName,
                        sha256(apk), enabled));
            } catch (Exception ignored) {
                // Package may have been updated or uninstalled during the scan.
            }
        }
        return entries;
    }

    private static boolean isValidEntry(String className) {
        return className != null && className.length() > 3
                && className.length() <= 180
                && className.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+");
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : md.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
