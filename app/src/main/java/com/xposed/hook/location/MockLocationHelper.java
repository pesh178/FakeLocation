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
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static final String GPGSV = "$GPGSV,1,1,04,12,05,159,36,15,41,087,15,19,38,262,30,31,56,146,19,";
    private static final String GPVTG = "$GPVTG,0,T,0,M,0,N,0,K,A,";
    private static final String GPGSA = "$GPGSA,A,2,12,15,19,31,,,,,,,,,604,712,986,";
    private static final String GPGSV_SENTENCE = checksum(GPGSV);
    private static final String GPVTG_SENTENCE = checksum(GPVTG);
    private static final String GPGSA_SENTENCE = checksum(GPGSA);

    /** SimpleDateFormat is not thread safe, so one formatter pair is kept per thread. */
    private static final ThreadLocal<UtcFormats> UTC_FORMATS = new ThreadLocal<UtcFormats>() {
        @Override
        protected UtcFormats initialValue() {
            return new UtcFormats();
        }
    };

    private static final class UtcFormats {
        private final SimpleDateFormat time = new SimpleDateFormat("HHmmss.SS", Locale.US);
        private final SimpleDateFormat date = new SimpleDateFormat("ddMMyy", Locale.US);

        UtcFormats() {
            TimeZone utc = TimeZone.getTimeZone("UTC");
            time.setTimeZone(utc);
            date.setTimeZone(utc);
        }
    }

    public static void invokeNmeaReceived(Object listener) {
        invokeNmeaReceived(listener, null);
    }

    public static void invokeNmeaReceived(Object listener, String packageName) {
        if (listener == null) return;
        RefMethod<Void> method = selectMethod(listener);
        if (method == null) return;

        long timestamp = System.currentTimeMillis();
        Date now = new Date(timestamp);
        UtcFormats formats = UTC_FORMATS.get();
        String time = formats.time.format(now);
        String date = formats.date.format(now);

        double latitude = LocationConfig.getLatitude(packageName);
        double longitude = LocationConfig.getLongitude(packageName);
        String lat = getGPSLat(latitude);
        String lon = getGPSLon(longitude);
        String latDirection = getNorthWest(latitude);
        String lonDirection = getSouthEast(longitude);
        int satelliteCount = VirtualGPSSatalines.get().getSvCount();

        StringBuilder payload = new StringBuilder(96);
        callNmeaReceived(method, listener, timestamp, GPGSV_SENTENCE);

        payload.append("$GPGGA,").append(time)
                .append(',').append(lat).append(',').append(latDirection)
                .append(',').append(lon).append(',').append(lonDirection)
                .append(",1,").append(satelliteCount).append(",692,.00,M,.00,M,,,");
        callNmeaReceived(method, listener, timestamp, sentence(payload));

        callNmeaReceived(method, listener, timestamp, GPVTG_SENTENCE);

        payload.setLength(0);
        payload.append("$GPRMC,").append(time).append(",A,")
                .append(lat).append(',').append(latDirection)
                .append(',').append(lon).append(',').append(lonDirection)
                .append(",0,0,").append(date).append(",,,A,");
        callNmeaReceived(method, listener, timestamp, sentence(payload));

        callNmeaReceived(method, listener, timestamp, GPGSA_SENTENCE);
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

    /** Appends the NMEA XOR checksum to the payload and returns the complete sentence. */
    private static String sentence(StringBuilder payload) {
        int sum = xor(payload);
        payload.append('*');
        appendHex(payload, sum);
        return payload.toString();
    }

    public static String checksum(String nmea) {
        StringBuilder payload = new StringBuilder(nmea.length() + 3);
        payload.append(nmea);
        return sentence(payload);
    }

    private static int xor(CharSequence text) {
        int sum = 0;
        int start = text.length() > 0 && text.charAt(0) == '$' ? 1 : 0;
        for (int i = start; i < text.length(); i++) {
            sum ^= text.charAt(i);
        }
        return sum;
    }

    private static void appendHex(StringBuilder target, int value) {
        target.append(HEX[(value >> 4) & 0xF]).append(HEX[value & 0xF]);
    }

    /** Formats degrees + decimal minutes exactly like {@code %0Nd%07.4f}. */
    private static String formatCoordinate(double value, int degreeWidth) {
        double absolute = Math.abs(value);
        int degrees = (int) absolute;
        double minutes = (absolute - degrees) * 60.0d;
        long scaledMinutes = Math.round(minutes * 10000.0d);
        if (scaledMinutes >= 600000L) {
            degrees++;
            scaledMinutes = 0L;
        }
        StringBuilder text = new StringBuilder(degreeWidth + 8);
        appendPadded(text, degrees, degreeWidth);
        appendPadded(text, scaledMinutes / 10000L, 2);
        text.append('.');
        appendPadded(text, scaledMinutes % 10000L, 4);
        return text.toString();
    }

    private static void appendPadded(StringBuilder target, long value, int width) {
        int digits = 1;
        for (long limit = 10L; digits < width && value >= limit; limit *= 10L) {
            digits++;
        }
        for (int i = digits; i < width; i++) {
            target.append('0');
        }
        target.append(value);
    }
}
