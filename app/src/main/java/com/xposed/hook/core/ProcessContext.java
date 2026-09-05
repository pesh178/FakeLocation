package com.xposed.hook.core;

import android.content.Context;

import java.lang.reflect.Method;

/** 基于当前进程 LoadedApk 创建应用 Context。 */
public final class ProcessContext {

    private static final Object LOCK = new Object();
    private static volatile Context sContext;

    private ProcessContext() {
    }

    public static Context create() throws Throwable {
        Context cachedContext = sContext;
        if (cachedContext != null) {
            return cachedContext;
        }

        synchronized (LOCK) {
            cachedContext = sContext;
            if (cachedContext != null) {
                return cachedContext;
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = HookUtils.callStaticMethod(
                    activityThreadClass, "currentActivityThread");
            if (activityThread == null) {
                throw new IllegalStateException("ActivityThread is not ready");
            }

            Object boundApplication = HookUtils.getObjectField(activityThread, "mBoundApplication");
            Object loadedApk = HookUtils.getObjectField(boundApplication, "info");
            Method createAppContext = findCreateAppContext(activityThreadClass);
            createAppContext.setAccessible(true);

            Object context;
            if (createAppContext.getParameterTypes().length == 3) {
                String packageName = (String) HookUtils.callMethod(loadedApk, "getPackageName");
                context = createAppContext.invoke(null, activityThread, loadedApk, packageName);
            } else {
                context = createAppContext.invoke(null, activityThread, loadedApk);
            }
            if (!(context instanceof Context)) {
                throw new ClassCastException("createAppContext did not return Context");
            }

            sContext = (Context) context;
            return sContext;
        }
    }

    private static Method findCreateAppContext(Class<?> activityThreadClass)
            throws ClassNotFoundException, NoSuchMethodException {
        Class<?> loadedApkClass = Class.forName("android.app.LoadedApk");
        Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
        try {
            return contextImplClass.getDeclaredMethod(
                    "createAppContext", activityThreadClass, loadedApkClass, String.class);
        } catch (NoSuchMethodException ignored) {
            return contextImplClass.getDeclaredMethod(
                    "createAppContext", activityThreadClass, loadedApkClass);
        }
    }
}
