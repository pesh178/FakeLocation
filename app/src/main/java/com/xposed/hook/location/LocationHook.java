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

/** Installs location, Wi-Fi and cell identity hooks for one target process. */
public class LocationHook {
    public static String TAG = "LocationHook";
    private static boolean installed;

    public static synchronized void hookAndChange(String packageName, ClassLoader classLoader,
                                                   final double latitude, final double longitude,
                                                   final long lac, final long cid) {
        LocationConfig.bindClassLoader(classLoader, packageName);
        LocationConfig.configure(packageName, latitude, longitude, lac, cid);
        if (installed) {
            Log.w(TAG, "Hooks already installed for process; registered " + packageName);
            return;
        }

        Log.d(TAG, "Avalon Hook Location Test: " + packageName);
        installed = true;

        hookReturnConstant(WifiManager.class, "getScanResults", Collections.emptyList());
        hookReturnConstant(WifiInfo.class, "getMacAddress", "02:00:00:00:00:00");
        hookReturnConstant(WifiInfo.class, "getSSID", "<unknown ssid>");
        hookReturnConstant(WifiInfo.class, "getBSSID", "02:00:00:00:00:00");

        hookAll("android.location.LocationManager", classLoader, "requestLocationUpdates", chain -> {
            String targetPackage = LocationConfig.packageNameForObject(chain.getThisObject());
            for (Object arg : chain.getArgs()) {
                if (arg instanceof android.location.LocationListener) {
                    LocationConfig.bindListener(arg, targetPackage);
                    break;
                }
            }
            LocationHandler.getInstance().start();
            return chain.proceed();
        });

        final Hooker lastLocationHooker = chain -> {
            String targetPackage = LocationConfig.packageNameForObject(chain.getThisObject());
            Location loc = (Location) chain.proceed();
            if (loc != null) {
                LocationHandler.updateLocation(loc, targetPackage);
            } else {
                loc = LocationHandler.createLocation(targetPackage);
            }
            return loc;
        };
        HookUtils.findAndHookMethod("android.location.LocationManager", classLoader,
                "getLastLocation", lastLocationHooker);
        hookAll("android.location.LocationManager", classLoader,
                "getLastKnownLocation", lastLocationHooker);

        hookReplace(Location.class, "getLatitude", chain -> {
            Location location = (Location) chain.getThisObject();
            return LocationConfig.getLatitude(LocationConfig.packageForLocation(location));
        });
        hookReplace(Location.class, "getLongitude", chain -> {
            Location location = (Location) chain.getThisObject();
            return LocationConfig.getLongitude(LocationConfig.packageForLocation(location));
        });
        hookReturnConstant(LocationManager.class, "getBestProvider",
                new Class<?>[]{Criteria.class, boolean.class}, "gps");
        HookUtils.findAndHookMethod(LocationManager.class, classLoader,
                "isProviderEnabled", String.class, new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        if ("gps".equals(chain.getArg(0))) return true;
                        return chain.proceed();
                    }
                });

        hookReturnConstant(TelephonyManager.class, "getNeighboringCellInfo", (Object) null);
        PhoneStateListenerDelegate.hookPhoneStateListener();
        HookUtils.findAndHookMethod(TelephonyManager.class, classLoader,
                "getCellLocation", new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        LocationConfig.bindCellLocation(result,
                                LocationConfig.packageNameForObject(chain.getThisObject()));
                        return result;
                    }
                });
        HookUtils.findAndHookMethod(TelephonyManager.class, classLoader,
                "getAllCellInfo", new Hooker() {
                    @Override
                    public Object intercept(Chain chain) throws Throwable {
                        Object result = chain.proceed();
                        if (result instanceof Iterable) {
                            String targetPackage = LocationConfig.packageNameForObject(
                                    chain.getThisObject());
                            for (Object item : (Iterable<?>) result) {
                                if (item instanceof android.telephony.CellInfo) {
                                    LocationConfig.bindCellInfo(
                                            (android.telephony.CellInfo) item, targetPackage);
                                }
                            }
                        }
                        return result;
                    }
                });

        hookReplace(GsmCellLocation.class, "getLac",
                chain -> LocationConfig.getLac(chain.getThisObject(), (int) lac));
        hookReplace(GsmCellLocation.class, "getCid",
                chain -> LocationConfig.getCid(chain.getThisObject(), (int) cid));
        hookReplace(CellIdentityGsm.class, "getLac",
                chain -> LocationConfig.getLac(chain.getThisObject(), (int) lac));
        hookReplace(CellIdentityGsm.class, "getCid",
                chain -> LocationConfig.getCid(chain.getThisObject(), (int) cid));
        hookReplace(CellIdentityWcdma.class, "getLac",
                chain -> LocationConfig.getLac(chain.getThisObject(), (int) lac));
        hookReplace(CellIdentityWcdma.class, "getCid",
                chain -> LocationConfig.getCid(chain.getThisObject(), (int) cid));
        hookReplace(CellIdentityLte.class, "getTac",
                chain -> LocationConfig.getLac(chain.getThisObject(), (int) lac));
        hookReplace(CellIdentityLte.class, "getCi",
                chain -> LocationConfig.getCid(chain.getThisObject(), (int) cid));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            hookReplace(CellIdentityTdscdma.class, "getLac",
                    chain -> LocationConfig.getLac(chain.getThisObject(), (int) lac));
            hookReplace(CellIdentityTdscdma.class, "getCid",
                    chain -> LocationConfig.getCid(chain.getThisObject(), (int) cid));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hookReplace(CellIdentityNr.class, "getTac",
                    chain -> LocationConfig.getLac(chain.getThisObject(), (int) lac));
            hookReplace(CellIdentityNr.class, "getNci",
                    chain -> LocationConfig.getNci(chain.getThisObject(), cid));
        }
    }

    private static void hookReturnConstant(Class<?> clazz, String methodName, Object constant) {
        hook(clazz, methodName, new Class<?>[0], chain -> constant);
    }

    private static void hookReturnConstant(Class<?> clazz, String methodName,
                                           Class<?>[] paramTypes, Object constant) {
        hook(clazz, methodName, paramTypes, chain -> constant);
    }

    private static void hookReplace(Class<?> clazz, String methodName, Hooker hooker) {
        hook(clazz, methodName, new Class<?>[0], hooker);
    }

    private static void hook(Class<?> clazz, String methodName,
                             Class<?>[] paramTypes, Hooker hooker) {
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

    private static void hookAll(String className, ClassLoader classLoader,
                                String methodName, Hooker hooker) {
        try {
            HookUtils.hookAllMethods(className, classLoader, methodName, hooker);
        } catch (Throwable e) {
            Log.d(TAG, e.toString());
        }
    }
}
