package com.yagay.ybrowser.controls;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Observes Chromium's public Java tab model interfaces.
 *
 * Chromium 153.0.8010.37 / 156.0.8078.39 sources:
 * TabModelSelector.addObserverToAllModels(TabModelObserver)
 * TabModelSelector.addObserver(TabModelSelectorObserver)
 * Tab.addObserver(TabObserver)
 *
 * No guessed private fields or periodic model polling.
 */
final class ChromiumTabObserver {
    private static final String TAG = "YBrowserTabObserver";
    private static final int INITIALIZATION_RETRIES = 40;
    private final Activity activity;
    private final Runnable onChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Object> tabObservers = new IdentityHashMap<>();
    private final Runnable reconnect = this::connect;
    private final Runnable notifyChanged = () -> {
        if (!stopped && !activity.isDestroyed()) {
            discoverTabs();
            onChanged.run();
        }
    };
    private int retries;
    private boolean stopped;
    private Object selector;
    private Object modelObserver;
    private Object selectorObserver;
    private Class<?> tabObserverClass;
    private String status = "等待 Chromium 标签模型初始化";

    ChromiumTabObserver(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
    }

    String status() { return status; }

    void start() {
        stopped = false;
        retries = 0;
        connect();
    }

    void stop() {
        stopped = true;
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(notifyChanged);
        for (Map.Entry<Object, Object> entry : tabObservers.entrySet()) {
            try {
                entry.getKey().getClass().getMethod("removeObserver", tabObserverClass)
                        .invoke(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                Log.d(TAG, "Tab observer cleanup skipped", e);
            }
        }
        tabObservers.clear();
        if (selector != null) {
            try {
                Class<?> type = modelObserver.getClass().getInterfaces()[0];
                selector.getClass().getMethod("removeObserverFromAllModels", type)
                        .invoke(selector, modelObserver);
            } catch (Exception e) { Log.d(TAG, "Model observer cleanup skipped", e); }
            try {
                Class<?> type = selectorObserver.getClass().getInterfaces()[0];
                selector.getClass().getMethod("removeObserver", type)
                        .invoke(selector, selectorObserver);
            } catch (Exception e) { Log.d(TAG, "Selector observer cleanup skipped", e); }
        }
        selector = null;
        modelObserver = null;
        selectorObserver = null;
    }

    private void connect() {
        if (stopped || activity.isDestroyed()) return;
        if (selector != null) return;
        try {
            // Source: ChromeActivity.areTabModelsInitialized() rejects premature access.
            Object initialized = activity.getClass()
                    .getMethod("areTabModelsInitialized").invoke(activity);
            if (!Boolean.TRUE.equals(initialized)) {
                retry("Chromium 仍在恢复标签模型");
                return;
            }
            Object candidate = activity.getClass().getMethod("getTabModelSelector")
                    .invoke(activity);
            if (candidate == null) {
                retry("TabModelSelector 尚未创建");
                return;
            }
            ClassLoader loader = activity.getClassLoader();
            Class<?> modelType = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabModelObserver");
            Class<?> selectorType = loader.loadClass(
                    "org.chromium.chrome.browser.tabmodel.TabModelSelectorObserver");
            tabObserverClass = loader.loadClass(
                    "org.chromium.chrome.browser.tab.TabObserver");
            if (!modelType.isInterface() || !selectorType.isInterface()
                    || !tabObserverClass.isInterface()) {
                throw new IllegalStateException("Chromium 观察者接口类型不匹配");
            }
            // Resolve exact public source signatures before attaching anything.
            Method addModel = candidate.getClass()
                    .getMethod("addObserverToAllModels", modelType);
            Method removeModel = candidate.getClass()
                    .getMethod("removeObserverFromAllModels", modelType);
            Method addSelector = candidate.getClass().getMethod("addObserver", selectorType);
            candidate.getClass().getMethod("removeObserver", selectorType);
            // Do not accidentally register an observer that cannot be removed.
            modelObserver = makeProxy(modelType, (name, args) -> {
                if ("didAddTab".equals(name) && args != null && args.length > 0) {
                    observeTab(args[0]);
                }
                if ("tabRemoved".equals(name) && args != null && args.length > 0) {
                    forgetTab(args[0]);
                }
                changed();
            });
            selectorObserver = makeProxy(selectorType, (name, args) -> {
                if ("onDestroyed".equals(name)) {
                    handler.post(this::stop);
                } else {
                    changed();
                }
            });
            // Both registrations are backed by source-defined public interfaces.
            addModel.invoke(candidate, modelObserver);
            try {
                addSelector.invoke(candidate, selectorObserver);
            } catch (Exception e) {
                removeModel.invoke(candidate, modelObserver);
                throw e;
            }
            selector = candidate;
            status = "已订阅 Chromium TabModelSelector / TabModelObserver";
            discoverTabs();
            changed();
        } catch (Exception | LinkageError e) {
            // A method missing from a shipped Dex is a concrete incompatibility,
            // not an invitation to make up further signatures.
            status = "无法安装 Chromium 标签观察者: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, status, e);
        }
    }

    private void retry(String message) {
        status = message;
        if (++retries <= INITIALIZATION_RETRIES) {
            handler.removeCallbacks(reconnect);
            handler.postDelayed(reconnect, 150);
        } else {
            status = "标签模型初始化超时，需检查实际浏览器版本";
            Log.w(TAG, status);
        }
    }

    private void discoverTabs() {
        if (selector == null || stopped) return;
        try {
            Object models = selector.getClass().getMethod("getModels").invoke(selector);
            if (!(models instanceof Iterable)) return;
            for (Object model : (Iterable<?>) models) {
                int count = ((Number) model.getClass().getMethod("getCount")
                        .invoke(model)).intValue();
                // Bound observation to avoid registering thousands of per-tab callbacks.
                for (int i = 0; i < Math.min(count, 200); i++) {
                    Object tab = model.getClass().getMethod("getTabAt", int.class)
                            .invoke(model, i);
                    observeTab(tab);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to enumerate tabs for title observation", e);
        }
    }

    private void observeTab(Object tab) {
        if (tab == null || tabObservers.containsKey(tab) || stopped) return;
        try {
            Method add = tab.getClass().getMethod("addObserver", tabObserverClass);
            tab.getClass().getMethod("removeObserver", tabObserverClass);
            Object callback = makeProxy(tabObserverClass, (name, args) -> changed());
            add.invoke(tab, callback);
            tabObservers.put(tab, callback);
        } catch (Exception e) {
            Log.d(TAG, "Tab observer unavailable for this runtime tab", e);
        }
    }

    private void forgetTab(Object tab) {
        Object callback = tabObservers.remove(tab);
        if (callback == null) return;
        try {
            tab.getClass().getMethod("removeObserver", tabObserverClass)
                    .invoke(tab, callback);
        } catch (Exception e) {
            Log.d(TAG, "Tab observer removal failed", e);
        }
    }

    private void changed() {
        if (stopped) return;
        handler.removeCallbacks(notifyChanged);
        handler.postDelayed(notifyChanged, 32);
    }

    private interface EventCallback { void changed(String method, Object[] args); }

    private static Object makeProxy(Class<?> api, EventCallback callback) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals": return proxy == args[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    case "toString": return "YBrowser " + api.getSimpleName();
                    default: return null;
                }
            }
            callback.changed(method.getName(), args);
            // All events in the audited observer interfaces are void.
            return null;
        };
        return Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, handler);
    }
}
