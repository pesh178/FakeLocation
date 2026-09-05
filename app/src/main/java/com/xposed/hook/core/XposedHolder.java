package com.xposed.hook.core;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * libxposed API 102 入口持有者。
 * 模块入口 {@link io.github.libxposed.api.XposedModule} 实例在 onModuleLoaded 时注入，
 * 供各 hook 类通过静态方法访问框架接口，替代 legacy 的 XposedBridge 静态调用。
 */
public final class XposedHolder {

    private static final String DEFAULT_TAG = "FakeLocation";

    private static volatile XposedInterface sApi;
    private static volatile String sProcessName = "";

    private XposedHolder() {
    }

    public static void init(XposedInterface api, String processName) {
        sApi = api;
        sProcessName = processName == null ? "" : processName;
    }

    public static XposedInterface get() {
        return sApi;
    }

    public static String getProcessName() {
        return sProcessName;
    }

    public static void log(String msg) {
        log(DEFAULT_TAG, msg);
    }

    public static void log(String tag, String msg) {
        XposedInterface api = sApi;
        if (api != null) {
            api.log(Log.INFO, tag, msg);
        } else {
            Log.i(tag, msg);
        }
    }

    public static void log(Throwable tr) {
        XposedInterface api = sApi;
        if (api != null) {
            api.log(Log.ERROR, DEFAULT_TAG, Log.getStackTraceString(tr), tr);
        } else {
            Log.e(DEFAULT_TAG, "Hook error", tr);
        }
    }
}
