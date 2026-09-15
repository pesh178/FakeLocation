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

import com.xposed.hook.core.ProcessContext;

import mirror.RefMethod;

/**
 * Periodically dispatches the configured location to registered framework transports.
 *
 * <p>The dispatch loop only stays scheduled while there is something to deliver: once no
 * target listener is registered the loop stops, so a hooked process is not woken every
 * interval for nothing. Registering a listener through {@link LocationHook} calls
 * {@link #start()} again and resumes the loop.
 */
public class LocationHandler extends Handler {
    /** First dispatch happens shortly after a listener is registered. */
    private static final long FIRST_DELAY_MS = 1000L;
    /** Steady-state dispatch interval. */
    private static final long INTERVAL_MS = 10000L;
    private static final int MSG_DISPATCH = 0;

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

    /** Guards {@link #looping} and {@link #registrationEpoch}. */
    private final Object loopLock = new Object();
    private boolean looping;
    private long registrationEpoch;

    private LocationHandler() {
        super(Looper.getMainLooper());
    }

    @Override
    public void handleMessage(Message msg) {
        long epoch;
        synchronized (loopLock) {
            epoch = registrationEpoch;
        }
        boolean delivered;
        try {
            Object transport = ProcessContext.create().getSystemService(Context.LOCATION_SERVICE);
            delivered = notifyNmeaReceived(transport);
            delivered |= notifyLocation(transport);
        } catch (Throwable e) {
            // Framework internals differ per API level; keep the loop alive so a transient
            // failure cannot silently stop mocking for the rest of the process lifetime.
            Log.d(LocationHook.TAG, e.toString(), e);
            delivered = true;
        }
        finishCycle(epoch, delivered);
    }

    /** Starts or keeps the dispatch loop running. Safe to call from any thread. */
    public void start() {
        synchronized (loopLock) {
            registrationEpoch++;
            if (!looping) {
                looping = true;
                sendEmptyMessageDelayed(MSG_DISPATCH, FIRST_DELAY_MS);
            }
        }
    }

    /** @return whether the dispatch loop is currently scheduled. */
    public boolean isRunning() {
        synchronized (loopLock) {
            return looping;
        }
    }

    private void finishCycle(long epoch, boolean delivered) {
        synchronized (loopLock) {
            if (delivered || registrationEpoch != epoch) {
                sendEmptyMessageDelayed(MSG_DISPATCH, INTERVAL_MS);
            } else {
                looping = false;
            }
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

    /** @return whether at least one registered listener received an update. */
    private boolean notifyLocation(Object transport) {
        Map listeners = null;
        if (LocationManager.sLocationListeners != null) {
            listeners = LocationManager.sLocationListeners.get(transport);
        } else if (LocationManager.mListeners != null) {
            listeners = LocationManager.mListeners.get(transport);
        }
        if (listeners == null || listeners.isEmpty()) return false;

        RefMethod<Void> method = LocationManager.ListenerTransport.onLocationChanged;
        if (method == null) method = LocationManager.LocationListenerTransport.onLocationChanged;
        if (method == null) return false;

        boolean delivered = false;
        //noinspection unchecked
        Set<Map.Entry> entries = listeners.entrySet();
        for (Map.Entry entry : entries) {
            Object value = entry.getValue();
            if (value == null) continue;
            String packageName = LocationConfig.packageForListener(entry.getKey());
            delivered |= notifyLocation(method, value, createLocation(packageName));
        }
        return delivered;
    }

    private boolean notifyLocation(RefMethod<Void> method, Object transport, Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!(transport instanceof WeakReference)) return false;
            transport = ((WeakReference) transport).get();
            if (transport == null) return false;
            method.call(transport, Collections.singletonList(location), null);
        } else {
            method.call(transport, location);
        }
        return true;
    }

    /** @return whether at least one registered NMEA listener received a sentence. */
    private boolean notifyNmeaReceived(Object transport) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Object manager = LocationManager.GnssLazyLoader.sGnssNmeaListeners == null
                        ? null : LocationManager.GnssLazyLoader.sGnssNmeaListeners.get();
                Map registrations = manager == null || LocationManager.ListenerTransportManager.mRegistrations == null
                        ? null : LocationManager.ListenerTransportManager.mRegistrations.get(manager);
                return notifyNmeaRegistrations(registrations);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Object manager = LocationManager.mGnssStatusListenerManager == null
                        ? null : LocationManager.mGnssStatusListenerManager.get(transport);
                Object listenerTransport = manager == null || LocationManager.GnssStatusListenerManager.mListenerTransport == null
                        ? null : LocationManager.GnssStatusListenerManager.mListenerTransport.get(manager);
                return notifyNmeaListener(listenerTransport, LocationConfig.packageNameForObject(listenerTransport));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                boolean delivered = false;
                if (LocationManager.mGnssNmeaListeners != null) {
                    delivered |= notifyNmeaListener(LocationManager.mGnssNmeaListeners.get(transport));
                }
                if (LocationManager.mGpsNmeaListeners != null) {
                    delivered |= notifyNmeaListener(LocationManager.mGpsNmeaListeners.get(transport));
                }
                return delivered;
            } else if (LocationManager.mNmeaListeners != null) {
                return notifyNmeaListener(LocationManager.mNmeaListeners.get(transport));
            }
        } catch (Throwable e) {
            Log.d(LocationHook.TAG, e.toString(), e);
        }
        return false;
    }

    private boolean notifyNmeaRegistrations(Map registrations) {
        if (registrations == null || registrations.isEmpty()) return false;
        boolean delivered = false;
        for (Object value : registrations.values()) {
            if (!(value instanceof WeakReference)) continue;
            Object listenerTransport = ((WeakReference) value).get();
            if (listenerTransport != null) {
                delivered |= notifyNmeaListener(listenerTransport,
                        LocationConfig.packageForObject(listenerTransport));
            }
        }
        return delivered;
    }

    private boolean notifyNmeaListener(Map listeners) {
        if (listeners == null || listeners.isEmpty()) return false;
        boolean delivered = false;
        //noinspection unchecked
        Set<Map.Entry> entries = listeners.entrySet();
        for (Map.Entry entry : entries) {
            delivered |= notifyNmeaListener(entry.getValue(),
                    LocationConfig.packageForObject(entry.getKey()));
        }
        return delivered;
    }

    private boolean notifyNmeaListener(Object object, String packageName) {
        if (object == null) return false;
        try {
            MockLocationHelper.invokeNmeaReceived(object, packageName);
            return true;
        } catch (Throwable e) {
            Log.d(LocationHook.TAG, e.toString(), e);
            return false;
        }
    }
}
