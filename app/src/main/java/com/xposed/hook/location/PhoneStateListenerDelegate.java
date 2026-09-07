package com.xposed.hook.location;

import android.telephony.CellInfo;
import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.util.Log;

import com.xposed.hook.core.HookUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/** Binds telephony callback objects to their owning target package. */
public final class PhoneStateListenerDelegate {
    private static final String TAG = "PhoneStateListener";
    private static final Set<Method> HOOKED_METHODS = new HashSet<>();
    private static boolean constructorHooked;

    private PhoneStateListenerDelegate() {
    }

    public static synchronized void hookPhoneStateListener() {
        if (constructorHooked) return;
        constructorHooked = true;
        try {
            Constructor<PhoneStateListener> constructor = PhoneStateListener.class.getConstructor();
            HookUtils.hookConstructor(constructor, chain -> {
                Object result = chain.proceed();
                hookListenerClass(result == null ? null : result.getClass());
                return result;
            });
        } catch (Throwable e) {
            Log.w(TAG, e.toString());
        }
    }

    private static synchronized void hookListenerClass(Class<?> clazz) {
        if (clazz == null || clazz == PhoneStateListener.class) return;
        hookCallback(findMethod(clazz, "onCellLocationChanged", CellLocation.class));
        hookCallback(findMethod(clazz, "onCellInfoChanged", List.class));
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?> parameter) {
        try {
            return HookUtils.findMethodExact(clazz, name, parameter);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void hookCallback(Method method) {
        if (method == null || !HOOKED_METHODS.add(method)) return;
        HookUtils.hookMethod(method, new Hooker() {
            @Override
            public Object intercept(Chain chain) throws Throwable {
                Object[] args = HookUtils.argsOf(chain);
                String packageName = LocationConfig.packageForListener(chain.getThisObject());
                if (args.length > 0 && args[0] instanceof CellLocation) {
                    LocationConfig.bindCellLocation(args[0], packageName);
                } else if (args.length > 0 && args[0] instanceof Iterable) {
                    for (Object item : (Iterable<?>) args[0]) {
                        if (item instanceof CellInfo) {
                            LocationConfig.bindCellInfo((CellInfo) item, packageName);
                        }
                    }
                }
                return chain.proceed(args);
            }
        });
    }
}
