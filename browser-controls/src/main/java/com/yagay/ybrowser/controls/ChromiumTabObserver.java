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
 * Initialization uses ChromeActivity.getTabModelSelectorSupplier() and
 * MonotonicObservableSupplier.addSyncObserverAndPostIfNonNull().
 */
final class ChromiumTabObserver {
    private static final String TAG = "YBrowserTabObserver";
    private final Activity activity;
    private final Runnable onChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Object> tabObservers = new IdentityHashMap<>();
    private boolean stopped;
    private final Runnable notifyChanged;
    private Object supplier;
    private Object supplierObserver;
    private Class<?> supplierCallbackClass;
    private Object selector;
    private Object modelObserver;
    private Object selectorObserver;
    private Class<?> tabObserverClass;
    private String status = "等待 Chromium 标签模型初始化";

    ChromiumTabObserver(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.notifyChanged = () -> {
            if (!stopped && !this.activity.isDestroyed()) {
                discoverTabs();
                this.onChanged.run();
            }
        };
    }

    String status() { return status; }

    void start() {
        stopped = false;
        subscribeToModelSupplier();
    }

    void stop() {
        stopped = true;
        handler.removeCallbacks(notifyChanged);
        if (supplier != null && supplierObserver != null) {
            try {
                supplier.getClass().getMethod("removeObserver", supplierCallbackClass)
                        .invoke(supplier, supplierObserver);
            } catch (Exception e) {
                Log.d(TAG, "Supplier observer cleanup skipped", e);
            }
        }
        supplier = null;
        supplierObserver = null;
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

    private void subscribeToModelSupplier() {
        if (stopped || activity.isDestroyed()) return;
        try {
            // ChromeActivity exposes a monotonic supplier that notifies when
            // the selector is ready. This avoids timing guesses/retry loops.
            Object source = activity.getClass()
                    .getMethod("getTabModelSelectorSupplier").invoke(activity);
            if (source == null) {
                status = "Chromium 未提供 TabModelSelectorSupplier";
                return;
            }
            Class<?> callbackType = activity.getClassLoader()
                    .loadClass("org.chromium.base.Callback");
            if (!callbackType.isInterface())
                throw new IllegalStateException("Chromium Callback 不是接口");
            Method add = source.getClass()
                    .getMethod("addSyncObserverAndPostIfNonNull", callbackType);
            source.getClass().getMethod("removeObserver", callbackType);
            Object callback = makeProxy(callbackType, (name, args) -> {
                if ("onResult".equals(name) && args != null && args.length == 1) {
                    connectReady(args[0]);
                }
            });
            supplier = source;
            supplierObserver = callback;
            supplierCallbackClass = callbackType;
            status = "已订阅 Chromium TabModelSelectorSupplier，等待初始化通知";
            add.invoke(source, callback);
        } catch (Exception | LinkageError e) {
            status = "无法订阅 ChromeActivity.getTabModelSelectorSupplier: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, status, e);
        }
    }

    private void connectReady(Object candidate) {
        if (stopped || activity.isDestroyed() || selector != null || candidate == null) return;
        try {
            if (!Boolean.TRUE.equals(activity.getClass()
                    .getMethod("areTabModelsInitialized").invoke(activity))) {
                status = "TabModelSelector 回调时模型尚未初始化";
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
            Method addModel = candidate.getClass()
                    .getMethod("addObserverToAllModels", modelType);
            Method removeModel = candidate.getClass()
                    .getMethod("removeObserverFromAllModels", modelType);
            Method addSelector = candidate.getClass()
                    .getMethod("addObserver", selectorType);
            candidate.getClass().getMethod("removeObserver", selectorType);
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
            status = "无法注册 Chromium 标签观察者: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.w(TAG, status, e);
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
