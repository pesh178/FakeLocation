package com.xposed.hook.location;

import android.telephony.CellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import com.xposed.hook.core.HookUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Created by lin on 2018/1/25.
 */

public class PhoneStateListenerDelegate {

    private static final String TAG = "PhoneStateListener";

    private static List<String> hookedClass = new ArrayList<>();

    public static void hookPhoneStateListener(int lac, int cid) {
        try {
            Constructor<PhoneStateListener> constructor = PhoneStateListener.class.getConstructor();
            HookUtils.hookConstructor(constructor, new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    Class<?> clazz = chain.getThisObject().getClass();
                    while (clazz != null && clazz != PhoneStateListener.class) {
                        if (hookedClass.contains(clazz.getName()))
                            break;
                        try {
                            Method method = HookUtils.findMethodExact(clazz, "onCellLocationChanged", CellLocation.class);
                            hookPhoneStateListener(method, lac, cid);
                            hookedClass.add(clazz.getName());
                            break;
                        } catch (Throwable e) {
                            Log.i(TAG, e.toString());
                        }
                        clazz = clazz.getSuperclass();
                    }
                    return result;
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, e.toString());
        }
    }

    private static void hookPhoneStateListener(Method method, int lac, int cid) {
        try {
            HookUtils.hookMethod(method, new Hooker() {
                @Override
                public Object intercept(Chain chain) throws Throwable {
                    Object[] args = HookUtils.argsOf(chain);
                    if (args[0] instanceof GsmCellLocation) {
                        Log.i(TAG, "hooking onCellLocationChanged");
                        ((GsmCellLocation) args[0]).setLacAndCid(lac, cid);
                    }
                    return chain.proceed(args);
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, e.toString());
        }
    }
}
