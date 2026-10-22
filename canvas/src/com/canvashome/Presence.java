package com.canvashome;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whether someone is in the room, from the TV's motion (presence) sensor. The CanvasTV's sensor
 * is Hisense's, so how it shows up varies: a standard proximity sensor, or a vendor sensor
 * named for motion or presence. "auto" takes the first of those; ./install.sh sensors lists them
 * all and ./install.sh sensor NAME picks one. A reading counts as someone there when a proximity
 * sensor reads nearer than its range, or any other sensor reads non-zero.
 *
 * The last time someone was seen is kept in the settings, so the schedule can read it when the
 * service isn't the one asking. Listening is started and stopped by {@link Overlay}.
 */
final class Presence implements SensorEventListener {
    private static final Pattern NAME = Pattern.compile("motion|presence|pir|human|body|occupan|proximity|radar");
    // Android's own "motion" sensors are about the device moving, not the room.
    private static final int[] DEVICE_MOTION = {Sensor.TYPE_SIGNIFICANT_MOTION, Sensor.TYPE_MOTION_DETECT,
            Sensor.TYPE_STATIONARY_DETECT, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER};
    private static final long SAVE_EVERY_MS = 20_000;

    private final Context context;
    private final SensorManager sm;
    private Sensor sensor;
    private boolean listening;
    private long lastSeen;
    private long savedAt;

    private final TriggerEventListener trigger = new TriggerEventListener() {
        @Override
        public void onTrigger(TriggerEvent event) {
            seen(true);
            // One-shot sensors have to be asked again after each trigger.
            if (listening && sensor != null) sm.requestTriggerSensor(this, sensor);
        }
    };

    Presence(Context context) {
        this.context = context;
        sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    /** The sensor the settings name, or null if there is none (or it's turned off). */
    static Sensor choose(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        String want = Config.sensor(c);
        if (sm == null || "off".equals(want)) return null;
        List<Sensor> all = sm.getSensorList(Sensor.TYPE_ALL);
        if (!"auto".equals(want)) {
            for (Sensor s : all) if (s.getName().equals(want)) return s;
            return null;
        }
        Sensor proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximity != null) return proximity;
        for (Sensor s : all) {
            boolean device = false;
            for (int t : DEVICE_MOTION) device |= s.getType() == t;
            String label = (s.getName() + " " + s.getStringType()).toLowerCase(Locale.US);
            if (!device && NAME.matcher(label).find()) return s;
        }
        return null;
    }

    static boolean available(Context c) {
        return choose(c) != null;
    }

    /** When someone was last seen, in wall-clock ms, or 0. */
    static long seenAt(Context c) {
        return Config.prefs(c).getLong("seen_at", 0);
    }

    void listen(boolean on) {
        if (on == listening) return;
        if (on) {
            sensor = choose(context);
            if (sensor == null) return;
            if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) sm.requestTriggerSensor(trigger, sensor);
            else sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            listening = true;
            Log.i(Config.TAG, "listening to " + sensor.getName());
        } else {
            sm.unregisterListener(this);
            if (sensor != null) sm.cancelTriggerSensor(trigger, sensor);
            listening = false;
            // The latest sighting, even if it came within SAVE_EVERY_MS of the last save.
            if (lastSeen > savedAt) save();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.values.length == 0) return;
        boolean someone = e.sensor.getType() == Sensor.TYPE_PROXIMITY
                ? e.values[0] < e.sensor.getMaximumRange()
                : e.values[0] != 0f;
        seen(someone);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void seen(boolean someone) {
        if (!someone) return;
        lastSeen = System.currentTimeMillis();
        if (lastSeen - savedAt >= SAVE_EVERY_MS) save();
    }

    private void save() {
        savedAt = lastSeen;
        Config.prefs(context).edit().putLong("seen_at", lastSeen).apply();
    }

    /** For ./install.sh status and sensors. */
    static String describe(Context c, boolean listAll) {
        StringBuilder b = new StringBuilder();
        Sensor s = choose(c);
        if (s == null) {
            b.append("Presence: no sensor").append("off".equals(Config.sensor(c)) ? " (turned off)" : " found")
                    .append(", so the schedule keeps Art Mode on until the evening ends.");
        } else {
            long at = seenAt(c);
            b.append("Presence: ").append(s.getName()).append(" (").append(s.getStringType()).append("), ")
                    .append(at == 0 ? "nobody seen yet." : "someone seen " + ago(at) + ".");
        }
        if (listAll) {
            SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            b.append("\nSensors on this TV:");
            for (Sensor x : sm.getSensorList(Sensor.TYPE_ALL)) {
                b.append("\n  ").append(x.getName()).append("  type=").append(x.getStringType())
                        .append(" vendor=").append(x.getVendor()).append(" range=").append(x.getMaximumRange());
            }
        }
        return b.toString();
    }

    static String ago(long at) {
        long min = (System.currentTimeMillis() - at) / 60_000;
        if (min < 1) return "just now";
        if (min < 120) return min + " min ago";
        return (min / 60) + " h ago";
    }
}
