package com.canvashome;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Environment;
import android.view.KeyEvent;

import java.io.File;
import java.util.Calendar;

/**
 * Settings, all set over ADB through {@link ConfigReceiver} (./install.sh). Nothing is typed with
 * the remote.
 */
final class Config {
    static final String TAG = "Canvas";

    private Config() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("canvas", Context.MODE_PRIVATE);
    }

    /**
     * Hisense's Art Mode activity, learned with ./install.sh learn while Art Mode is on screen:
     * "package/class". Its package decides when the icons show; the activity is what the evening
     * schedule starts.
     */
    static ComponentName artComponent(Context c) {
        String s = prefs(c).getString("art_component", "");
        return s.length() == 0 ? null : ComponentName.unflattenFromString(s);
    }

    static String artPackage(Context c) {
        ComponentName cn = artComponent(c);
        return cn == null ? "" : cn.getPackageName();
    }

    /** The folder Art Mode's photo collection reads; ./install.sh learn suggests it. */
    static File photoDir(Context c) {
        String s = prefs(c).getString("photo_dir", "");
        if (s.length() > 0) return new File(s);
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Canvas Art");
    }

    /** The mat's color, or null for no mat. */
    static Integer matColor(Context c) {
        String s = prefs(c).getString("mat", "#E8E3D9");
        if ("off".equals(s)) return null;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return Color.parseColor("#E8E3D9");
        }
    }

    /** The key that moves focus onto the icons while Art Mode shows. Others go to Art Mode. */
    static int focusKey(Context c) {
        int k = KeyEvent.keyCodeFromString("KEYCODE_" + prefs(c).getString("key", "DPAD_DOWN"));
        return k == KeyEvent.KEYCODE_UNKNOWN ? KeyEvent.KEYCODE_DPAD_DOWN : k;
    }

    // ---------------------------------------------------------------- the evening schedule

    /** Minutes after midnight, or -1 when the schedule is off. Default 18:00 to 20:00. */
    static int startMinute(Context c) {
        return prefs(c).getInt("start", 18 * 60);
    }

    static int endMinute(Context c) {
        return prefs(c).getInt("end", 20 * 60);
    }

    static boolean inWindow(Context c, Calendar now) {
        int start = startMinute(c), end = endMinute(c);
        if (start < 0) return false;
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return start <= end ? m >= start && m < end : m >= start || m < end;
    }

    static String hhmm(int minute) {
        return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    /** "18:00" to 1080, or -1. */
    static int parseMinute(String s) {
        String[] p = s.trim().split(":");
        try {
            int h = Integer.parseInt(p[0]), m = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
