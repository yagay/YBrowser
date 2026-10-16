package com.yagay.ybrowser.loader.hook;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import com.yagay.ybrowser.api.BrowserFeature;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads installed and same-signed feature APKs from the Loader's guarded provider.
 * The Loader never executes unchecked files from downloads or shared storage.
 * Loaded code runs in the browser process, so failures are isolated per callback.
 */
final class RuntimePluginLoader {
    private static final String TAG = "YBrowserFeatures";
    private static final Uri BASE = Uri.parse("content://com.yagay.ybrowser.loader.plugins");
    private static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Map<String, Instance> LOADED = new HashMap<>();
    private RuntimePluginLoader() {}

    private static final class Instance {
        final String sha;
        final BrowserFeature plugin;
        Instance(String sha, BrowserFeature plugin) {
            this.sha = sha;
            this.plugin = plugin;
        }
    }

    @SuppressWarnings("deprecation")
    static void dispatch(Activity activity) {
        try {
            Bundle result = activity.getContentResolver().call(BASE, "catalog", null, null);
            if (result == null) return;
            java.util.ArrayList<Bundle> records = result.getParcelableArrayList("plugins");
            if (records == null) return;
            for (Bundle record : records) {
                String pkg = record.getString("package", "");
                String entry = record.getString("entry", "");
                String sha = record.getString("sha256", "");
                if (!pkg.startsWith("com.yagay.ybrowser.plugin.")
                        || !sha.matches("[0-9a-f]{64}") || entry.isEmpty()) continue;
                try {
                    Instance current = LOADED.get(pkg);
                    if (current == null) {
                        BrowserFeature plugin = load(activity, pkg, entry, sha);
                        current = new Instance(sha, plugin);
                        LOADED.put(pkg, current);
                    }
                    // Never hot-swap active DEX in a live Chromium process.
                    if (sha.equals(current.sha)) {
                        current.plugin.onBrowserActivityResumed(activity, activity.getPackageName());
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Feature " + pkg + " failed; rest remain active", t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Module catalog unavailable; original browser remains functional", t);
        }
    }

    private static BrowserFeature load(Activity activity, String pkg,
                                       String entryClass, String sha) throws Exception {
        File file = getVerifiedApk(activity, pkg, sha);
        DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(),
                activity.getCodeCacheDir().getAbsolutePath(), null,
                BrowserFeature.class.getClassLoader());
        Object instance = loader.loadClass(entryClass).getDeclaredConstructor().newInstance();
        if (!(instance instanceof BrowserFeature)) {
            throw new SecurityException("Entry does not implement the shared BrowserFeature ABI");
        }
        return (BrowserFeature) instance;
    }

    private static File getVerifiedApk(Activity activity, String pkg, String expected) throws Exception {
        File cache = new File(activity.getCodeCacheDir(), "ybrowser-features");
        if (!cache.exists() && !cache.mkdirs()) throw new IllegalStateException("No cache directory");
        File destination = new File(cache, expected + ".apk");
        if (destination.isFile() && expected.equals(hash(destination))) return destination;
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("Stale APK");

        Uri uri = BASE.buildUpon().appendPath("apk").appendPath(pkg).build();
        File tmp = File.createTempFile("feature-", ".apk", cache);
        try {
            long total = 0;
            try (ParcelFileDescriptor fd = activity.getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd == null) throw new IllegalStateException("Provider did not return APK");
                try (InputStream in = new FileInputStream(fd.getFileDescriptor());
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_BYTES) throw new SecurityException("Oversize feature APK");
                        out.write(buf, 0, n);
                    }
                    out.getFD().sync();
                }
            }
            if (!expected.equals(hash(tmp))) throw new SecurityException("Feature checksum mismatch");
            if (!tmp.setReadOnly()) throw new SecurityException("Could not make code read-only");
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static String hash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte value : md.digest()) {
            sb.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return sb.toString();
    }
}
