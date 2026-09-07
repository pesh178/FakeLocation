package com.xposed.hook.location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import mirror.RefMethod;

/**
 * @author Lody
 */
public class MockLocationHelper {
    public static void invokeNmeaReceived(Object listener) {
        invokeNmeaReceived(listener, null);
    }

    public static void invokeNmeaReceived(Object listener, String packageName) {
        if (listener == null) return;
        RefMethod<Void> method = selectMethod(listener);
        if (method == null) return;

        VirtualGPSSatalines satalines = VirtualGPSSatalines.get();
        long timestamp = System.currentTimeMillis();
        Date now = new Date(timestamp);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss.SS", Locale.US);
        SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyy", Locale.US);
        TimeZone utc = TimeZone.getTimeZone("UTC");
        timeFormat.setTimeZone(utc);
        dateFormat.setTimeZone(utc);
        String time = timeFormat.format(now);
        String date = dateFormat.format(now);
        double latitude = LocationConfig.getLatitude(packageName);
        double longitude = LocationConfig.getLongitude(packageName);
        String lat = formatCoordinate(latitude, 2);
        String lon = formatCoordinate(longitude, 3);
        String latDirection = getNorthWest(latitude);
        String lonDirection = getSouthEast(longitude);
        String gga = checksum(String.format(Locale.US,
                "$GPGGA,%s,%s,%s,%s,%s,1,%s,692,.00,M,.00,M,,,",
                time, lat, latDirection, lon, lonDirection, satalines.getSvCount()));
        String rmc = checksum(String.format(Locale.US,
                "$GPRMC,%s,A,%s,%s,%s,%s,0,0,%s,,,A,",
                time, lat, latDirection, lon, lonDirection, date));
        callNmeaReceived(method, listener, timestamp,
                checksum("$GPGSV,1,1,04,12,05,159,36,15,41,087,15,19,38,262,30,31,56,146,19,"));
        callNmeaReceived(method, listener, timestamp, gga);
        callNmeaReceived(method, listener, timestamp, checksum("$GPVTG,0,T,0,M,0,N,0,K,A,"));
        callNmeaReceived(method, listener, timestamp, rmc);
        callNmeaReceived(method, listener, timestamp,
                checksum("$GPGSA,A,2,12,15,19,31,,,,,,,,,604,712,986,"));
    }

    private static RefMethod<Void> selectMethod(Object listener) {
        Class<?> type = listener.getClass();
        if (LocationManager.GnssNmeaTransport.TYPE != null
                && LocationManager.GnssNmeaTransport.TYPE.isAssignableFrom(type)) {
            return LocationManager.GnssNmeaTransport.onNmeaReceived;
        }
        if (LocationManager.GnssStatusListener.TYPE != null
                && LocationManager.GnssStatusListener.TYPE.isAssignableFrom(type)) {
            return LocationManager.GnssStatusListener.onNmeaReceived;
        }
        if (LocationManager.GnssStatusListenerTransport.TYPE != null
                && LocationManager.GnssStatusListenerTransport.TYPE.isAssignableFrom(type)) {
            return LocationManager.GnssStatusListenerTransport.onNmeaReceived;
        }
        if (LocationManager.GpsStatusListenerTransport.TYPE != null
                && LocationManager.GpsStatusListenerTransport.TYPE.isAssignableFrom(type)) {
            return LocationManager.GpsStatusListenerTransport.onNmeaReceived;
        }
        return null;
    }

    private static void callNmeaReceived(RefMethod<Void> method, Object listener,
                                         long timestamp, String nmea) {
        method.call(listener, timestamp, nmea);
    }

    private static String getSouthEast(double longitude) {
        return longitude < 0.0d ? "W" : "E";
    }

    private static String getNorthWest(double latitude) {
        return latitude < 0.0d ? "S" : "N";
    }

    public static String getGPSLat(double value) {
        return formatCoordinate(value, 2);
    }

    public static String getGPSLon(double value) {
        return formatCoordinate(value, 3);
    }

    private static String formatCoordinate(double value, int degreeWidth) {
        double absolute = Math.abs(value);
        int degrees = (int) absolute;
        double minutes = (absolute - degrees) * 60.0d;
        minutes = Math.round(minutes * 10000.0d) / 10000.0d;
        if (minutes >= 60.0d) {
            degrees++;
            minutes = 0.0d;
        }
        return String.format(Locale.US, "%0" + degreeWidth + "d%07.4f", degrees, minutes);
    }

    public static String checksum(String nmea) {
        String checkStr = nmea.startsWith("$") ? nmea.substring(1) : nmea;
        int sum = 0;
        for (int i = 0; i < checkStr.length(); i++) {
            sum ^= checkStr.charAt(i);
        }
        return nmea + "*" + String.format(Locale.US, "%02X", sum);
    }
}