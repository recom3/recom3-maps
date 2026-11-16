package com.recom3.maps;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

final class HudPosition implements LocationListener, SensorEventListener {
    interface Listener {
        void positionChanged();
    }

    private static final long FIX_AGE_MS = 30000;
    private final LocationManager locations;
    private final SensorManager sensors;
    private final WindowManager windows;
    private final Listener listener;
    private final float[] gravity = new float[3];
    private final float[] magnetic = new float[3];
    private final float[] matrix = new float[9];
    private final float[] screenMatrix = new float[9];
    private final float[] angles = new float[3];

    private Location location;
    private boolean receivedGravity;
    private boolean receivedMagnetic;
    private boolean usingRotationVector;
    private boolean gpsEnabled;
    private boolean gpsAvailable;
    private boolean loggedFix;
    private boolean loggedHeading;
    private float magneticHeading = Float.NaN;
    private float declination;
    private String gpsError;

    HudPosition(Context context, Listener listener) {
        this.listener = listener;
        locations = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    void start() {
        try {
            gpsError = null;
            gpsAvailable = locations.getAllProviders().contains(LocationManager.GPS_PROVIDER);
            if (gpsAvailable) {
                gpsEnabled = locations.isProviderEnabled(LocationManager.GPS_PROVIDER);
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
                Location last = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (gpsEnabled && isFresh(last)) onLocationChanged(last);
            }
        } catch (SecurityException denied) {
            gpsError = "GPS permission";
        }

        Sensor rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        usingRotationVector = rotation != null
                && sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI);
        if (!usingRotationVector) {
            Sensor accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor compass = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (accelerometer != null && compass != null) {
                sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
                sensors.registerListener(this, compass, SensorManager.SENSOR_DELAY_UI);
            }
        }
    }

    void stop() {
        try {
            locations.removeUpdates(this);
        } catch (SecurityException ignored) {
        }
        sensors.unregisterListener(this);
        magneticHeading = Float.NaN;
        receivedGravity = false;
        receivedMagnetic = false;
    }

    Location fix() {
        return gpsEnabled && isFresh(location) ? location : null;
    }

    String gpsLabel() {
        if (gpsError != null) return gpsError;
        if (!gpsAvailable) return "GPS unavailable";
        if (!gpsEnabled) return "GPS off";
        Location fix = fix();
        if (fix == null) return location == null ? "GPS searching" : "GPS signal lost";
        if (!fix.hasAccuracy()) return "GPS fix";
        return "GPS ±" + Math.round(fix.getAccuracy()) + " m";
    }

    float heading() {
        Location fix = fix();
        if (!Float.isNaN(magneticHeading)) {
            return (magneticHeading + (fix == null ? 0 : declination) + 360) % 360;
        }
        if (fix != null && fix.hasBearing() && fix.hasSpeed() && fix.getSpeed() >= 1.5f) {
            return (fix.getBearing() + 360) % 360;
        }
        return Float.NaN;
    }

    boolean magneticOnly() {
        return fix() == null && !Float.isNaN(magneticHeading);
    }

    @Override public void onLocationChanged(Location update) {
        if (!isFresh(update)) return;
        location = new Location(update);
        GeomagneticField field = new GeomagneticField((float) update.getLatitude(),
                (float) update.getLongitude(), (float) update.getAltitude(), update.getTime());
        declination = field.getDeclination();
        if (!loggedFix && BuildConfig.DEBUG) {
            loggedFix = true;
            Log.i(TileTransport.TAG, "GPS fix received"
                    + (update.hasAccuracy() ? " accuracy=" + Math.round(update.getAccuracy()) + "m" : ""));
        }
        listener.positionChanged();
    }

    @Override public void onProviderEnabled(String provider) {
        gpsEnabled = true;
        listener.positionChanged();
    }

    @Override public void onProviderDisabled(String provider) {
        gpsEnabled = false;
        location = null;
        listener.positionChanged();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (usingRotationVector) {
            SensorManager.getRotationMatrixFromVector(matrix, event.values);
        } else {
            if (type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, gravity, 0, 3);
                receivedGravity = true;
            } else {
                System.arraycopy(event.values, 0, magnetic, 0, 3);
                receivedMagnetic = true;
            }
            if (!receivedGravity || !receivedMagnetic
                    || !SensorManager.getRotationMatrix(matrix, null, gravity, magnetic)) return;
        }
        int axisX = SensorManager.AXIS_X;
        int axisY = SensorManager.AXIS_Y;
        switch (windows.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            default:
                break;
        }
        if (!SensorManager.remapCoordinateSystem(matrix, axisX, axisY, screenMatrix)) return;
        SensorManager.getOrientation(screenMatrix, angles);
        float next = (float) ((Math.toDegrees(angles[0]) + 360) % 360);
        if (Float.isNaN(next)) return;
        float diff = Math.abs(next - magneticHeading);
        if (!Float.isNaN(magneticHeading) && Math.min(diff, 360 - diff) < 3) return;
        magneticHeading = next;
        if (!loggedHeading && BuildConfig.DEBUG) {
            loggedHeading = true;
            Log.i(TileTransport.TAG, usingRotationVector ? "Heading source: rotation vector"
                    : "Heading source: accelerometer + magnetometer");
        }
        listener.positionChanged();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private static boolean isFresh(Location sample) {
        if (sample == null) return false;
        long age = System.currentTimeMillis() - sample.getTime();
        return age >= -2000 && age <= FIX_AGE_MS;
    }
}
