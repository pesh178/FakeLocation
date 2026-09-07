package com.xposed.hook.location;

import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.xposed.hook.core.ProcessContext;

import mirror.RefMethod;

/**
 * Periodically dispatches the configured location to registered framework transports.
 */
public class LocationHandler extends Handler {
    private static volatile LocationHandler instance;

    public static LocationHandler getInstance() {
        LocationHandler result = instance;
        if (result == null) {
            synchronized (LocationHandler.class) {
                result = instance;
                if (result == null) {
                    result = new LocationHandler();
                    instance = result;
                }
            }
        }
        return result;
    }

    private final AtomicBoolean started = new AtomicBoolean();

    private LocationHandler() {
        super(Looper.getMainLooper());
    }

    @Override
    public void handleMessage(Message msg) {
        try {
            Object transport = ProcessContext.create().getSystemService(Context.LOCATION_SERVICE);
            notifyNmeaReceived(transport);
            notifyLocation(transport);
            sendEmptyMessageDelayed(0, 10000);
            Log.d(LocationHook.TAG, "Avalon Hook Location Success");
        } catch (Throwable e) {
            Log.d(LocationHook.TAG, e.toString(), e);
        }
    }

    public static Location createLocation(String packageName) {
        return createLocation(
                LocationConfig.getLatitude(packageName),
                LocationConfig.getLongitude(packageName),
                packageName);
    }

    public static Location createLocation(double latitude, double longitude) {
        return createLocation(latitude, longitude, null);
    }

    private static Location createLocation(double latitude, double longitude, String packageName) {
        Location location = new Location(android.location.LocationManager.GPS_PROVIDER);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setAccuracy((float) Math.random() + 8);
        location.setBearing((int) (360 * Math.random()));
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle extras = new Bundle();
        int satelliteCount = VirtualGPSSatalines.get().getSvCount();
        extras.putInt("satellites", satelliteCount);
        extras.putInt("satellitesvalue", satelliteCount);
        location.setExtras(extras);
        LocationConfig.bindLocation(location, packageName);
        return location;
    }

    public static void updateLocation(Location location, String packageName) {
        if (location == null) return;
        updateLocation(
                location,
                LocationConfig.getLatitude(packageName),
                LocationConfig.getLongitude(packageName));
        LocationConfig.bindLocation(location, packageName);
    }

    public static void updateLocation(Location location, double latitude, double longitude) {
        if (location == null) return;
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            sendEmptyMessageDelayed(0, 1000);
        }
    }

    private void notifyLocation(Object transport) {
        Map listeners = null;
        if (LocationManager.sLocationListeners != null) {
            listeners = LocationManager.sLocationListeners.get(transport);
        } else if (LocationManager.mListeners != null) {
            listeners = LocationManager.mListeners.get(transport);
        }
        if (listeners == null || listeners.isEmpty()) return;

        RefMethod<Void> method = LocationManager.ListenerTransport.onLocationChanged;
        if (method == null) method = LocationManager.LocationListenerTransport.onLocationChanged;
        if (method == null) return;

        //noinspection unchecked
        Set<Map.Entry> entries = listeners.entrySet();
        for (Map.Entry entry : entries) {
            Object value = entry.getValue();
            if (value == null) continue;
            String packageName = LocationConfig.packageForListener(entry.getKey());
            notifyLocation(method, value, createLocation(packageName));
        }
    }

    private void notifyLocation(RefMethod<Void> method, Object transport, Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(transport instanceof WeakReference)) return;
            transport = ((WeakReference) transport).get();
            if (transport == null) return;
            method.call(transport, Collections.singletonList(location), null);
        } else {
            method.call(transport, location);
        }
    }

    private void notifyNmeaReceived(Object transport) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Object manager = LocationManager.GnssLazyLoader.sGnssNmeaListeners == null
                        ? null : LocationManager.GnssLazyLoader.sGnssNmeaListeners.get();
                Map registrations = manager == null || LocationManager.ListenerTransportManager.mRegistrations == null
                        ? null : LocationManager.ListenerTransportManager.mRegistrations.get(manager);
                notifyNmeaRegistrations(registrations);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Object manager = LocationManager.mGnssStatusListenerManager == null
                        ? null : LocationManager.mGnssStatusListenerManager.get(transport);
                Object listenerTransport = manager == null || LocationManager.GnssStatusListenerManager.mListenerTransport == null
                        ? null : LocationManager.GnssStatusListenerManager.mListenerTransport.get(manager);
                notifyNmeaListener(listenerTransport, LocationConfig.packageNameForObject(listenerTransport));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (LocationManager.mGnssNmeaListeners != null) notifyNmeaListener(LocationManager.mGnssNmeaListeners.get(transport));
                if (LocationManager.mGpsNmeaListeners != null) notifyNmeaListener(LocationManager.mGpsNmeaListeners.get(transport));
            } else if (LocationManager.mNmeaListeners != null) {
                notifyNmeaListener(LocationManager.mNmeaListeners.get(transport));
            }
        } catch (Throwable e) {
            Log.d(LocationHook.TAG, e.toString(), e);
        }
    }

    private void notifyNmeaRegistrations(Map registrations) {
        if (registrations == null || registrations.isEmpty()) return;
        for (Object value : registrations.values()) {
            if (!(value instanceof WeakReference)) continue;
            Object listenerTransport = ((WeakReference) value).get();
            if (listenerTransport != null) {
                notifyNmeaListener(listenerTransport, LocationConfig.packageForObject(listenerTransport));
            }
        }
    }

    private void notifyNmeaListener(Map listeners) {
        if (listeners == null || listeners.isEmpty()) return;
        //noinspection unchecked
        Set<Map.Entry> entries = listeners.entrySet();
        for (Map.Entry entry : entries) {
            notifyNmeaListener(entry.getValue(), LocationConfig.packageForObject(entry.getKey()));
        }
    }

    private void notifyNmeaListener(Object object) {
        notifyNmeaListener(object, LocationConfig.packageForObject(object));
    }

    private void notifyNmeaListener(Object object, String packageName) {
        if (object == null) return;
        try {
            MockLocationHelper.invokeNmeaReceived(object, packageName);
        } catch (Throwable e) {
            Log.d(LocationHook.TAG, e.toString(), e);
        }
    }
}
