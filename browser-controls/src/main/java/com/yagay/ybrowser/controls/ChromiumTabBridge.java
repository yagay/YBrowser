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

    private static Object selector(Activity activity) throws Exception {
        // ChromeActivity 153/156 explicitly throws if TabModel is not initialized.
        if (!Boolean.TRUE.equals(call(activity, "areTabModelsInitialized"))) return null;
        return call(activity, "getTabModelSelector");
    }

    private static Object currentModel(Activity activity) throws Exception {
        if (!Boolean.TRUE.equals(call(activity, "areTabModelsInitialized"))) return null;
        // ChromeActivity.getCurrentTabModel() is public in both audited revisions.
        return call(activity, "getCurrentTabModel");
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

    /**
     * Chromium's TabModelUtils.setIndex(TabModel,int) is preferred when
     * available. Current Chromium also exposes TabModel.setIndex(int,
     * TabSelectionType) where TabSelectionType may be an enum, not an int.
     * Resolve FROM_USER from the actual runtime type, not a magic 3.
     */
    static boolean select(Activity activity, int index) {
        Snapshot state = snapshot(activity);
        if (state == null) return actionFail("切换标签", lastError, null);
        if (index < 0 || index >= state.count)
            return actionFail("切换标签", "标签序号不在当前模型范围内", null);
        if (index == state.selected) { actionOK(); return true; }
        try {
            // Chromium 153/156 TabModelUtils.java:
            // public static void setIndex(TabModel model, int index)
            ClassLoader loader = activity.getClassLoader();
            Class<?> modelInterface = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabModel");
            if (!modelInterface.isInstance(state.model))
                return actionFail("切换标签", "实际模型不是 TabModel", null);
            Class<?> utils = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabModelUtils");
            Method method = utils.getMethod("setIndex", modelInterface, int.class);
            if (!Modifier.isStatic(method.getModifiers()))
                return actionFail("切换标签", "TabModelUtils.setIndex 非静态方法", null);
            method.invoke(null, state.model, index);
            actionOK();
            return true;
        } catch (Exception | LinkageError e) {
            return actionFail("切换标签", "源码定义的 TabModelUtils.setIndex 调用失败", e);
        }
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
        try {
            if (selector(activity) == null) {
                return actionFail("新建标签", "ChromeActivity 标签模型尚未初始化", null);
            }
            // ChromeActivity.getCurrentTabCreator() is public in 153/156
            // and selects regular/incognito creator based on selected model.
            Object creator = call(activity, "getCurrentTabCreator");
            if (creator == null) return actionFail("新建标签", "当前 TabCreator 为 null", null);
            ClassLoader loader = activity.getClassLoader();
            Class<?> launchType = loader.loadClass(
                    "org.chromium.chrome.browser.tab.TabLaunchType");
            Object launchConstant = launchType.getField("FROM_CHROME_UI").get(null);
            if (!(launchConstant instanceof Number))
                return actionFail("新建标签", "TabLaunchType.FROM_CHROME_UI 不是整数", null);
            // Source: Chromium UrlConstants.NTP_URL is chrome-native://newtab/.
            Class<?> constants = loader.loadClass(
                    "org.chromium.components.embedder_support.util.UrlConstants");
            Object ntp = constants.getField("NTP_URL").get(null);
            if (!(ntp instanceof String))
                return actionFail("新建标签", "UrlConstants.NTP_URL 不存在", null);
            // Chromium 153/156 TabCreator is an interface exposing
            // launchUrl(String, @TabLaunchType int). No launchNTP() there.
            Class<?> creatorInterface = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabCreator");
            if (!creatorInterface.isInstance(creator))
                return actionFail("新建标签", "实际 Creator 类型不匹配", null);
            Object tab = creatorInterface.getMethod("launchUrl", String.class, int.class)
                    .invoke(creator, ntp, ((Number) launchConstant).intValue());
            if (tab == null) return actionFail("新建标签", "Chromium 未创建标签", null);
            actionOK();
            return true;
        } catch (Exception | LinkageError e) {
            return actionFail("新建标签", "源码定义的 TabCreator.launchUrl 调用失败", e);
        }
    }

    static boolean closeTab(Activity activity, Object tab) {
        if (tab == null) return actionFail("关闭标签", "标签引用为空", null);
        Snapshot state = snapshot(activity);
        if (state == null) return actionFail("关闭标签", lastError, null);
        try {
            ClassLoader loader = activity.getClassLoader();
            Class<?> tabInterface = loader.loadClass("org.chromium.chrome.browser.tab.Tab");
            if (!tabInterface.isInstance(tab))
                return actionFail("关闭标签", "Tab 引用类型不匹配", null);
            Class<?> paramsType = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabClosureParams");
            Method start = paramsType.getMethod("closeTab", tabInterface);
            Object builder = start.invoke(null, tab);
            if (builder == null) return actionFail("关闭标签", "TabClosureParams.Builder 为空", null);
            Object params = call(builder, "build");
            Object remover = call(state.model, "getTabRemover");
            if (remover == null) return actionFail("关闭标签", "TabRemover 为空", null);
            // Source: TabRemover.closeTabs(TabClosureParams, boolean)
            // delegates to the three-argument variant with null listener.
            Class<?> removerType = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabRemover");
            if (!removerType.isInstance(remover))
                return actionFail("关闭标签", "TabRemover 类型不匹配", null);
            removerType.getMethod("closeTabs", paramsType, boolean.class)
                    .invoke(remover, params, true);
            actionOK();
            return true;
        } catch (Exception | LinkageError e) {
            return actionFail("关闭标签", "源码定义的 TabRemover.closeTabs 调用失败", e);
        }
    }

    private static Object currentTab(Activity activity) throws Exception {
        // TabModelSelector.getCurrentTab() is public in Chromium 153/156.
        Object currentSelector = selector(activity);
        return currentSelector == null ? null : call(currentSelector, "getCurrentTab");
    }

    /**
     * Device-local source compatibility audit. This enumerates actual loaded
     * Java methods instead of assuming the shipping browser matches HEAD.
     * Read-only: it never invokes a tab operation.
     */
    static String diagnostics(Activity activity) {
        StringBuilder report = new StringBuilder(1400);
        report.append("浏览器: ").append(activity.getPackageName());
        try {
            android.content.pm.PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            report.append(" v").append(info.versionName);
        } catch (Exception ignored) {}
        report.append("\nActivity: ").append(activity.getClass().getName());
        report.append("\n模型: ").append(lastError.isEmpty() ? "已读取" : lastError);
        report.append("\n最近操作: ").append(lastActionError);
        Object model = null;
        try {
            model = currentModel(activity);
            appendMethods(report, "TabModel", model,
                    "setIndex", "getCount", "index", "getTabRemover", "closeTab", "closeTabs");
        } catch (Exception | LinkageError e) {
            report.append("\nTabModel 获取失败: ").append(e.getClass().getSimpleName());
        }
        Object creator = null;
        try { creator = call(activity, "getCurrentTabCreator"); }
        catch (Exception | LinkageError ignored) {}
        if (creator == null) {
            try { creator = call(activity, "getTabCreator", boolean.class, false); }
            catch (Exception | LinkageError ignored) {}
        }
        appendMethods(report, "TabCreator", creator, "launchNTP", "launchUrl");
        if (model != null) {
            try {
                Object remover = call(model, "getTabRemover");
                appendMethods(report, "TabRemover", remover,
                        "closeTabs", "prepareCloseTabs", "removeTab");
            } catch (Exception | LinkageError e) {
                report.append("\nTabRemover 获取失败: ")
                        .append(e.getClass().getSimpleName());
            }
        }
        return report.toString();
    }

    private static void appendMethods(StringBuilder report, String label, Object object,
                                      String... names) {
        if (object == null) {
            report.append("\n").append(label).append(": 未获取");
            return;
        }
        report.append("\n").append(label).append(": ")
                .append(object.getClass().getName());
        int shown = 0;
        for (Method method : object.getClass().getMethods()) {
            boolean wanted = false;
            for (String name : names) {
                if (name.equals(method.getName())) { wanted = true; break; }
            }
            if (!wanted) continue;
            if (++shown > 15) {
                report.append("\n  ...");
                break;
            }
            report.append("\n  ").append(method.getName()).append("(");
            Class<?>[] args = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (i != 0) report.append(", ");
                report.append(args[i].getSimpleName());
            }
            report.append(")");
        }
        if (shown == 0) report.append("\n  (未发现匹配的方法)");
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
