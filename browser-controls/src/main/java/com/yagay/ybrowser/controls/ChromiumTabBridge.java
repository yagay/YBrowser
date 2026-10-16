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
    private static String lastError = "尚未开始读取标签";
    private static String lastActionError = "暂无操作错误";
    static String actionError() { return lastActionError; }

    private static boolean actionFail(String action, String reason, Throwable cause) {
        String detail = cause == null ? reason
                : reason + " (" + cause.getClass().getSimpleName()
                + ": " + String.valueOf(cause.getMessage()) + ")";
        lastActionError = action + ": " + detail;
        Log.w(TAG, lastActionError, cause);
        return false;
    }

    private static void actionOK() { lastActionError = "最近操作成功"; }
    static String lastError() { return lastError; }
    private static void fail(String message, Throwable cause) {
        if (!message.equals(lastError)) Log.w(TAG, message, cause);
        lastError = message;
    }

    private static Object currentModel(Activity activity) throws Exception {
        // Never recurse into currentModel(); try Chromium's Activity accessor.
        ReflectiveOperationException directFailure = null;
        try {
            Object model = call(activity, "getCurrentTabModel");
            if (model != null) return model;
        } catch (ReflectiveOperationException e) {
            directFailure = e;
        }

        // Chromium revisions offer either a selector or a selector supplier.
        Object selector = null;
        try {
            selector = call(activity, "getTabModelSelector");
        } catch (ReflectiveOperationException ignored) {}
        if (selector == null) {
            try {
                Object supplier = call(activity, "getTabModelSelectorSupplier");
                if (supplier != null) selector = call(supplier, "get");
            } catch (ReflectiveOperationException ignored) {}
        }
        if (selector != null) return call(selector, "getCurrentModel");
        if (directFailure != null) throw directFailure;
        return null;
    }


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
            Object model = currentModel(activity);
            if (model == null) {
                fail("标签模型尚未准备好", null);
                return null;
            }
            int count = ((Number)call(model, "getCount")).intValue();
            int selected = ((Number)call(model, "index")).intValue();
            if (count < 0 || count > 10000) {
                fail("标签数量异常: " + count, null);
                return null;
            }
            List<TabInfo> tabs = new ArrayList<>();
            // Cap the overlay rendering cost; underlying model remains complete.
            final int visibleLimit = 40;
            int start = count > visibleLimit
                    ? Math.max(0, Math.min(selected - visibleLimit / 2, count - visibleLimit))
                    : 0;
            for (int i = start; i < Math.min(count, start + visibleLimit); i++) {
                // A single restoring/destroyed tab must not hide all other tabs.
                try {
                    Object tab = call(model, "getTabAt", int.class, i);
                    if (tab == null) continue;
                    String title = "";
                    try {
                        Object titleObj = call(tab, "getTitle");
                        if (titleObj instanceof String) title = ((String) titleObj).trim();
                    } catch (ReflectiveOperationException ignored) {}
                    if (title.isEmpty()) title = "Tab " + (i + 1);
                    tabs.add(new TabInfo(i, title, tab));
                } catch (ReflectiveOperationException e) {
                    Log.d(TAG, "Tab " + i + " is not ready", e);
                }
            }
            Object current = null;
            try {
                current = currentTab(activity);
            } catch (ReflectiveOperationException ignored) {
                // Navigation controls can be unavailable independently of tab list.
            }
            if (current == null && selected >= 0 && selected < count) {
                for (TabInfo item : tabs) {
                    if (item.index == selected) {
                        current = item.tab;
                        break;
                    }
                }
            }
            boolean back = current != null && bool(current, "canGoBack");
            boolean forward = current != null && bool(current, "canGoForward");
            lastError = "";
            return new Snapshot(model, selected, count, tabs, back, forward);
        } catch (Exception | LinkageError t) {
            Throwable root = t;
            if (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) root = t.getCause();
            fail("读取 Chromium 标签模型失败: " + root.getClass().getSimpleName()
                    + ": " + root.getMessage(), t);
            return null;
        }
    }

    static boolean select(Activity activity, int index) {
        Snapshot s = snapshot(activity);
        if (s == null) return actionFail("切换标签", "无法取得 TabModel: " + lastError, null);
        if (index < 0 || index >= s.count) {
            return actionFail("切换标签", "目标序号已失效: " + index, null);
        }
        if (s.selected == index) { actionOK(); return true; }
        Throwable previous = null;
        try {
            // Chromium supplies a helper that also dispatches tab selection events.
            Class<?> utils = activity.getClassLoader().loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabModelUtils");
            for (Method m : utils.getMethods()) {
                if (!m.getName().equals("setIndex") || !Modifier.isStatic(m.getModifiers())
                        || m.getParameterTypes().length != 2
                        || m.getParameterTypes()[1] != int.class
                        || !m.getParameterTypes()[0].isInstance(s.model)) continue;
                m.invoke(null, s.model, index);
                actionOK();
                return true;
            }
        } catch (Exception | LinkageError e) { previous = e; }
        // Some Chromium builds expose setIndex on TabModel but not TabModelUtils.
        try {
            for (Method m : s.model.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("setIndex") && p.length == 2
                        && p[0] == int.class && p[1] == int.class) {
                    // TabSelectionType.FROM_USER in the classic int-based API.
                    m.invoke(s.model, index, 3);
                    actionOK();
                    return true;
                }
            }
        } catch (Exception | LinkageError e) { previous = e; }
        return actionFail("切换标签", "未找到可调用的 setIndex API", previous);
    }

    static boolean navigate(Activity activity, String command) {
        String label = "导航/" + command;
        try {
            Object tab = currentTab(activity);
            if (tab == null) return actionFail(label, "当前标签尚未准备好", null);
            switch (command) {
                case "back":
                    if (!bool(tab, "canGoBack"))
                        return actionFail("后退", "当前标签没有可返回的历史页面", null);
                    call(tab, "goBack");
                    break;
                case "forward":
                    if (!bool(tab, "canGoForward"))
                        return actionFail("前进", "当前标签没有可前进的历史页面", null);
                    call(tab, "goForward");
                    break;
                case "reload":
                    call(tab, "reload");
                    break;
                default:
                    return actionFail(label, "未知命令", null);
            }
            actionOK();
            return true;
        } catch (Exception | LinkageError e) {
            return actionFail(label, "Chromium 导航接口调用失败", e);
        }
    }

    static boolean newTab(Activity activity) {
        Object creator = null;
        Throwable previous = null;
        boolean incognito = false;
        try {
            Object model = currentModel(activity);
            if (model != null) incognito = Boolean.TRUE.equals(call(model, "isIncognito"));
        } catch (Exception | LinkageError e) {
            // Unknown profile: use Activity's active creator if it exists,
            // rather than silently forcing a normal tab in private mode.
            try {
                creator = call(activity, "getCurrentTabCreator");
            } catch (Exception | LinkageError ignored) {
                return actionFail("新建标签", "无法确定普通/无痕标签模型", e);
            }
        }
        if (creator == null) {
            try { creator = call(activity, "getTabCreator", boolean.class, incognito); }
            catch (Exception | LinkageError e) { previous = e; }
        }
        if (creator == null) {
            try { creator = call(activity, "getCurrentTabCreator"); }
            catch (Exception | LinkageError e) { previous = e; }
        }
        if (creator == null) {
            try {
                Object model = currentModel(activity);
                if (model != null) creator = call(model, "getTabCreator");
            } catch (Exception | LinkageError e) { previous = e; }
        }
        if (creator == null) {
            try {
                Object selector = call(activity, "getTabModelSelector");
                Object manager = selector == null ? null
                        : call(selector, "getTabCreatorManager");
                if (manager != null) creator =
                        call(manager, "getTabCreator", boolean.class, incognito);
            } catch (Exception | LinkageError e) { previous = e; }
        }
        if (creator == null) {
            return actionFail("新建标签", "无法获取 TabCreator", previous);
        }
        try {
            // FROM_CHROME_UI = 2 in classic Chromium builds.
            Object result = call(creator, "launchUrl", String.class, int.class,
                    "chrome://newtab/", 2);
            if (result == null) {
                return actionFail("新建标签", "launchUrl 没有返回新标签", null);
            }
            actionOK();
            return true;
        } catch (Exception | LinkageError e) {
            return actionFail("新建标签", "TabCreator.launchUrl 调用失败", e);
        }
    }

    static boolean closeTab(Activity activity, Object tab) {
        if (tab == null) return actionFail("关闭标签", "标签引用已失效", null);
        Snapshot s = snapshot(activity);
        if (s == null) return actionFail("关闭标签", "无法取得 TabModel: " + lastError, null);
        Throwable previous = null;
        try {
            Class<?> params = activity.getClassLoader().loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabClosureParams");
            Object builder = null;
            for (Method m : params.getMethods()) {
                if (!m.getName().equals("closeTab") || !Modifier.isStatic(m.getModifiers())
                        || m.getParameterTypes().length != 1
                        || !m.getParameterTypes()[0].isInstance(tab)) continue;
                builder = m.invoke(null, tab);
                break;
            }
            if (builder != null) {
                Object closeParams = call(builder, "build");
                Object remover = null;
                try { remover = call(s.model, "getTabRemover"); }
                catch (ReflectiveOperationException ignored) {}
                if (remover != null) {
                    for (Method m : remover.getClass().getMethods()) {
                        if (!m.getName().equals("closeTabs")) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if ((p.length != 2 && p.length != 3)
                                || !p[0].isInstance(closeParams) || p[1] != boolean.class)
                            continue;
                        // Keep native confirmation dialogs for grouped/shared tabs.
                        if (p.length == 2) m.invoke(remover, closeParams, true);
                        else m.invoke(remover, closeParams, true, null);
                        actionOK();
                        return true;
                    }
                }
                // Earlier Chromium exposes TabModel.closeTabs(params) itself.
                for (Method m : s.model.getClass().getMethods()) {
                    if (m.getName().equals("closeTabs") && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0].isInstance(closeParams)) {
                        Object result = m.invoke(s.model, closeParams);
                        if (Boolean.FALSE.equals(result))
                            return actionFail("关闭标签", "TabModel 拒绝关闭标签", null);
                        actionOK();
                        return true;
                    }
                }
            }
        } catch (Exception | LinkageError e) { previous = e; }
        // Old TabModel versions use closeTab(tab, animate, uponExit, canUndo).
        try {
            for (Method m : s.model.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("closeTab") || p.length != 4
                        || !p[0].isInstance(tab) || p[1] != boolean.class
                        || p[2] != boolean.class || p[3] != boolean.class) continue;
                Object result = m.invoke(s.model, tab, true, false, true);
                if (Boolean.FALSE.equals(result))
                    return actionFail("关闭标签", "旧版 TabModel 拒绝关闭标签", null);
                actionOK();
                return true;
            }
        } catch (Exception | LinkageError e) { previous = e; }
        return actionFail("关闭标签", "没有兼容的 TabRemover/closeTab 接口", previous);
    }

    private static Object currentTab(Activity activity) throws Exception {
        try {
            Object tab = call(activity, "getActivityTab");
            if (tab != null) return tab;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Object provider = call(activity, "getActivityTabProvider");
            if (provider != null) {
                Object tab = call(provider, "get");
                if (tab != null) return tab;
            }
        } catch (ReflectiveOperationException ignored) {}
        try {
            Object selector = call(activity, "getTabModelSelector");
            if (selector != null) {
                Object tab = call(selector, "getCurrentTab");
                if (tab != null) return tab;
            }
        } catch (ReflectiveOperationException ignored) {}
        // A missing activity tab accessor must not invalidate a valid model.
        Object model = currentModel(activity);
        if (model == null) return null;
        int index = ((Number) call(model, "index")).intValue();
        int count = ((Number) call(model, "getCount")).intValue();
        return index >= 0 && index < count
                ? call(model, "getTabAt", int.class, index) : null;
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
