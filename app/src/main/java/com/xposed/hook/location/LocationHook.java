package com.xposed.hook.location;

import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityTdscdma;
import android.telephony.CellIdentityWcdma;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import com.xposed.hook.core.HookUtils;

import java.util.Collections;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * Created by lin on 2017/7/23.
 */

public class LocationHook {

    public static String TAG = "LocationHook";

    public static void hookAndChange(String packageName, ClassLoader classLoader, final double latitude, final double longitude, final long lac, final long cid) {

        Log.d(TAG, "Avalon Hook Location Test: " + packageName);
        LocationConfig.setLatitude(latitude);
        LocationConfig.setLongitude(longitude);

        hookReturnConstant(WifiManager.class, "getScanResults", Collections.emptyList());
        hookReturnConstant(WifiInfo.class, "getMacAddress", "02:00:00:00:00:00");
        hookReturnConstant(WifiInfo.class, "getSSID", "<unknown ssid>");
        hookReturnConstant(WifiInfo.class, "getBSSID", "02:00:00:00:00:00");

        hookAll("android.location.LocationManager", classLoader, "requestLocationUpdates", chain -> {
            LocationHandler.getInstance().start();
            return chain.proceed();
        });

        final Hooker lastLocationHooker = chain -> {
            Location loc = (Location) chain.proceed();
            if (loc != null)
                LocationHandler.updateLocation(loc, LocationConfig.getLatitude(), LocationConfig.getLongitude());
            else
                loc = LocationHandler.createLocation(LocationConfig.getLatitude(), LocationConfig.getLongitude());
            return loc;
        };
        HookUtils.findAndHookMethod("android.location.LocationManager", classLoader, "getLastLocation", lastLocationHooker);
        hookAll("android.location.LocationManager", classLoader, "getLastKnownLocation", lastLocationHooker);

        hookReplace(Location.class, "getLatitude", chain -> LocationConfig.getLatitude());
        hookReplace(Location.class, "getLongitude", chain -> LocationConfig.getLongitude());
        hookReturnConstant(LocationManager.class, "getBestProvider", new Class<?>[]{Criteria.class, boolean.class}, "gps");
        HookUtils.findAndHookMethod(LocationManager.class, classLoader, "isProviderEnabled", String.class, new Hooker() {
            @Override
            public Object intercept(Chain chain) throws Throwable {
                Log.d(TAG, "isProviderEnabled: " + chain.getArg(0));
                if ("gps".equals(chain.getArg(0)))
                    return true;
                return chain.proceed();
            }
        });

        hookReturnConstant(TelephonyManager.class, "getNeighboringCellInfo", (Object) null);

        int ac = (int) lac;
        int ci = cid > Integer.MAX_VALUE ? -1 : (int) cid;

        hookReturnConstant(GsmCellLocation.class, "getLac", ac);
        hookReturnConstant(GsmCellLocation.class, "getCid", ci);

        // 2G
        hookReturnConstant(CellIdentityGsm.class, "getLac", ac);
        hookReturnConstant(CellIdentityGsm.class, "getCid", ci);

        // 3G
        hookReturnConstant(CellIdentityWcdma.class, "getLac", ac);
        hookReturnConstant(CellIdentityWcdma.class, "getCid", ci);

        // 4G
        hookReturnConstant(CellIdentityLte.class, "getTac", ac);
        hookReturnConstant(CellIdentityLte.class, "getCi", ci);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // 3G
            hookReturnConstant(CellIdentityTdscdma.class, "getLac", ac);
            hookReturnConstant(CellIdentityTdscdma.class, "getCid", ci);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 5G
            hookReturnConstant(CellIdentityNr.class, "getTac", ac);
            hookReturnConstant(CellIdentityNr.class, "getNci", cid);
        }
    }

    /** 无条件替换方法返回值，等价 legacy XC_MethodReplacement.returnConstant。 */
    private static void hookReturnConstant(Class<?> clazz, String methodName, Object constant) {
        hook(clazz, methodName, new Class<?>[0], chain -> constant);
    }

    private static void hookReturnConstant(Class<?> clazz, String methodName, Class<?>[] paramTypes, Object constant) {
        hook(clazz, methodName, paramTypes, chain -> constant);
    }

    /** 无条件替换方法返回值，等价 legacy XC_MethodReplacement。 */
    private static void hookReplace(Class<?> clazz, String methodName, Hooker hooker) {
        hook(clazz, methodName, new Class<?>[0], hooker);
    }

    private static void hook(Class<?> clazz, String methodName, Class<?>[] paramTypes, Hooker hooker) {
        try {
            HookUtils.findAndHookMethod(clazz, clazz.getClassLoader(), methodName,
                    concat(paramTypes, hooker));
        } catch (Throwable e) {
            Log.d(TAG, e.toString());
        }
    }

    private static Object[] concat(Class<?>[] paramTypes, Hooker hooker) {
        Object[] result = new Object[paramTypes.length + 1];
        System.arraycopy(paramTypes, 0, result, 0, paramTypes.length);
        result[paramTypes.length] = hooker;
        return result;
    }

    /** 挂钩全部 public 非抽象同名方法，等价 legacy 遍历 getDeclaredMethods 的写法。 */
    private static void hookAll(String className, ClassLoader classLoader, String methodName, Hooker hooker) {
        try {
            HookUtils.hookAllMethods(className, classLoader, methodName, hooker);
        } catch (Throwable e) {
            Log.d(TAG, e.toString());
        }
    }

}
