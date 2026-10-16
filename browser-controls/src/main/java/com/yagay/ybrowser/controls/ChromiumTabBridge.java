package com.yagay.ybrowser.controls;

import android.app.Activity;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chromium Java API bridge based on upstream ChromeActivity, TabModel, Tab and
 * TabCreator. No Chromium code is bundled or redistributed. Methods may be
 * obfuscated/changed in a fork; callers should always tolerate null/false.
 */
final class ChromiumTabBridge {
    private static final String TAG = "YBrowserControls";
    private ChromiumTabBridge() {}

    static final class TabInfo {
        final int index;
        final String title;
        final Object tab;
        TabInfo(int index, String title, Object tab) {
            this.index = index;
            this.title = title;
            this.tab = tab;
        }
    }

    static final class Snapshot {
        final Object model;
        final int selected;
        final int count;
        final List<TabInfo> tabs;
        final boolean back, forward;
        Snapshot(Object model, int selected, int count, List<TabInfo> tabs,
                 boolean back, boolean forward) {
            this.model = model;
            this.selected = selected;
            this.count = count;
            this.tabs = Collections.unmodifiableList(tabs);
            this.back = back;
            this.forward = forward;
        }
    }

    static Snapshot snapshot(Activity activity) {
        try {
            Object model = call(activity, "getCurrentTabModel");
            if (model == null) return null;
            int count = ((Number)call(model, "getCount")).intValue();
            int selected = ((Number)call(model, "index")).intValue();
            if (count < 0 || count > 10000) return null;
            List<TabInfo> tabs = new ArrayList<>();
            // Cap the overlay rendering cost; underlying model remains complete.
            final int visibleLimit = 40;
            int start = count > visibleLimit
                    ? Math.max(0, Math.min(selected - visibleLimit / 2, count - visibleLimit))
                    : 0;
            for (int i = start; i < Math.min(count, start + visibleLimit); i++) {
                Object tab = call(model, "getTabAt", int.class, i);
                if (tab == null) continue;
                Object titleObj = call(tab, "getTitle");
                String title = titleObj instanceof String ? ((String)titleObj).trim() : "";
                if (title.isEmpty()) title = "Tab " + (i + 1);
                tabs.add(new TabInfo(i, title, tab));
            }
            Object current = currentTab(activity);
            boolean back = current != null && bool(current, "canGoBack");
            boolean forward = current != null && bool(current, "canGoForward");
            return new Snapshot(model, selected, count, tabs, back, forward);
        } catch (Exception | LinkageError t) {
            return null;
        }
    }

    static boolean select(Activity activity, int index) {
        Snapshot s = snapshot(activity);
        if (s == null || index < 0 || index >= s.count) return false;
        try {
            // Chromium's public static helper performs the needed selection notifications.
            Class<?> utils = activity.getClassLoader().loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabModelUtils");
            for (Method m : utils.getMethods()) {
                if (!m.getName().equals("setIndex")
                        || !Modifier.isStatic(m.getModifiers())
                        || m.getParameterTypes().length != 2
                        || m.getParameterTypes()[1] != int.class
                        || !m.getParameterTypes()[0].isInstance(s.model)) continue;
                m.invoke(null, s.model, index);
                return true;
            }
            // No unsafe fallback to mutation of private mIndex.
        } catch (Exception | LinkageError t) {
            Log.w(TAG, "Tab selection API unavailable", t);
        }
        return false;
    }

    static boolean navigate(Activity activity, String command) {
        try {
            Object tab = currentTab(activity);
            if (tab == null) return false;
            switch (command) {
                case "back":
                    if (!bool(tab, "canGoBack")) return false;
                    call(tab, "goBack");
                    return true;
                case "forward":
                    if (!bool(tab, "canGoForward")) return false;
                    call(tab, "goForward");
                    return true;
                case "reload":
                    call(tab, "reload");
                    return true;
                default:
                    return false;
            }
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "Navigation API unavailable: " + command, e);
            return false;
        }
    }

    static boolean newTab(Activity activity) {
        try {
            Object creator = call(activity, "getTabCreator", boolean.class, false);
            if (creator == null) return false;
            Class<?> type = activity.getClassLoader().loadClass(
                    "org.chromium.chrome.browser.tab.TabLaunchType");
            int fromUi = type.getField("FROM_CHROME_UI").getInt(null);
            call(creator, "launchUrl", String.class, int.class, "chrome://newtab/", fromUi);
            return true;
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "New-tab API unavailable", e);
            return false;
        }
    }

    static boolean closeTab(Activity activity, Object tab) {
        if (tab == null) return false;
        try {
            Snapshot s = snapshot(activity);
            if (s == null) return false;
            ClassLoader loader = activity.getClassLoader();
            Class<?> params = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabClosureParams");
            Object builder = null;
            for (Method m : params.getMethods()) {
                if (!m.getName().equals("closeTab") || !Modifier.isStatic(m.getModifiers())
                        || m.getParameterTypes().length != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(tab)) continue;
                builder = m.invoke(null, tab);
                break;
            }
            if (builder == null) return false;
            Object closeParams = call(builder, "build");
            Object remover = call(s.model, "getTabRemover");
            if (remover == null) return false;
            for (Method m : remover.getClass().getMethods()) {
                if (!m.getName().equals("closeTabs")) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types.length < 2 || types.length > 3
                        || !types[0].isInstance(closeParams)
                        || types[1] != boolean.class) continue;
                if (types.length == 2) m.invoke(remover, closeParams, false);
                else m.invoke(remover, closeParams, false, null);
                return true;
            }
        } catch (Exception | LinkageError e) {
            Log.w(TAG, "Tab-close API unavailable", e);
        }
        return false;
    }

    private static Object currentTab(Activity activity) throws Exception {
        return call(activity, "getActivityTab");
    }

    private static boolean bool(Object object, String method) {
        try {
            return Boolean.TRUE.equals(call(object, method));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Object call(Object object, String name) throws Exception {
        return object.getClass().getMethod(name).invoke(object);
    }

    private static Object call(Object object, String name, Class<?> type, Object arg)
            throws Exception {
        return object.getClass().getMethod(name, type).invoke(object, arg);
    }

    private static Object call(Object object, String name, Class<?> t1, Class<?> t2,
                               Object arg1, Object arg2) throws Exception {
        return object.getClass().getMethod(name, t1, t2).invoke(object, arg1, arg2);
    }
}
