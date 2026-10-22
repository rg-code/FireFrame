package com.canvashome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * Art Mode in the evening (18:00 to 20:00 by default), for when someone is home.
 *
 * At the start of the evening, if the TV is off, it is turned on into Art Mode, once. Whether anyone
 * is home is left to Hisense: the CanvasTV's radar is only readable by Hisense's own apps (SELinux
 * keeps sideloaded apps away from its service), and Art Mode's own "Motion detector" setting already
 * blanks the screen when the room is empty and brings it back when someone walks in. At the end of
 * the evening, Art Mode is turned off.
 *
 * It never takes over the TV while it's in use (another app on screen), and never turns it on a
 * second time in the same evening, so a TV turned off with the remote stays off.
 *
 * Runs on an exact alarm: once a minute during the evening, else at the next start. Also receives
 * BOOT_COMPLETED to set the alarm again after a restart.
 */
public class Schedule extends BroadcastReceiver {
    private static final String TICK = "com.canvashome.TICK";

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (TICK.equals(a)) tick(c);
        else ArtJob.schedule(c);
        arm(c);
    }

    /** Sets the alarm for the next check. */
    static void arm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Schedule.class).setAction(TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int start = Config.startMinute(c);
        if (start < 0) {
            am.cancel(pi);
            Config.prefs(c).edit().putLong("next_at", 0).apply();
            return;
        }
        Calendar t = Calendar.getInstance();
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        if (Config.inWindow(c, Calendar.getInstance()) || Config.prefs(c).getString("evening", "").length() > 0) {
            t.add(Calendar.MINUTE, 1);
        } else {
            t.set(Calendar.HOUR_OF_DAY, start / 60);
            t.set(Calendar.MINUTE, start % 60);
            if (t.getTimeInMillis() <= System.currentTimeMillis()) t.add(Calendar.DAY_OF_MONTH, 1);
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
        Config.prefs(c).edit().putLong("next_at", t.getTimeInMillis()).apply();
    }

    /** The evening this moment belongs to, named by the date it started on. */
    private static String evening(Context c, Calendar now) {
        Calendar k = (Calendar) now.clone();
        k.add(Calendar.MINUTE, -Math.max(0, Config.startMinute(c)));
        return String.format(Locale.US, "%tF", k);
    }

    static synchronized void tick(Context c) {
        SharedPreferences p = Config.prefs(c);
        Calendar cal = Calendar.getInstance();
        Overlay o = Overlay.get();
        boolean art = o != null && o.artShowing();
        if (o != null) o.update();

        if (!Config.inWindow(c, cal)) {
            if (p.getString("evening", "").length() > 0) {
                p.edit().putString("evening", "").apply();
                if (art) sleep(c, "the evening is over");
            }
            return;
        }
        String day = evening(c, cal);
        if (!day.equals(p.getString("evening", ""))) {
            p.edit().putString("evening", day).apply();
            Log.i(Config.TAG, "evening starts");
        }
        if (day.equals(p.getString("woke", ""))) return;
        if (o == null) {
            Log.w(Config.TAG, "the overlay service is off, so Art Mode can't be started");
            return;
        }
        p.edit().putString("woke", day).apply();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        if (!pm.isInteractive()) wake(c, "the evening started");
        // Else the TV is already on (Art Mode, or someone using it), so leave it be.
    }

    /** From {@link Overlay}: logged, to tell Hisense's motion blanking from the remote in logcat. */
    static void screenChanged(Context c, boolean on) {
        if (Config.prefs(c).getString("evening", "").length() == 0) return;
        Log.i(Config.TAG, "screen " + (on ? "on" : "off") + " during the evening; top: "
                + (Overlay.get() == null ? "?" : Overlay.get().topPackage()));
    }

    /** Turns the screen on and opens Art Mode. */
    static void wake(Context c, String why) {
        Log.i(Config.TAG, "starting Art Mode: " + why);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "canvas:wake");
        wl.acquire(15_000);
        c.startActivity(new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Opens Art Mode. False if it hasn't been learned yet or won't open. */
    static boolean startArt(Context c) {
        ComponentName art = Config.artComponent(c);
        if (art == null) {
            Log.w(Config.TAG, "Art Mode's activity isn't known yet: open Art Mode and run ./install.sh learn");
            return false;
        }
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).setComponent(art).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            Log.w(Config.TAG, "couldn't open Art Mode (" + art.flattenToShortString() + "): " + e);
            return false;
        }
    }

    /**
     * Turns the screen off. With the device admin on (./install.sh sets it), that's Android's
     * lockNow, which sleeps the TV like the power button; else the accessibility lock action.
     */
    static void sleep(Context c, String why) {
        Log.i(Config.TAG, "turning Art Mode off: " + why);
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm.isAdminActive(new ComponentName(c, Admin.class))) {
            dpm.lockNow();
            return;
        }
        Overlay o = Overlay.get();
        if (o == null || !o.lockScreen()) Log.w(Config.TAG, "couldn't turn the screen off: no device admin and no overlay service");
    }

    static String describe(Context c) {
        int start = Config.startMinute(c);
        if (start < 0) return "Schedule: off.";
        SharedPreferences p = Config.prefs(c);
        StringBuilder b = new StringBuilder("Schedule: Art Mode from ").append(Config.hhmm(start)).append(" to ")
                .append(Config.hhmm(Config.endMinute(c)))
                .append("; whether anyone is home is left to Art Mode's Motion detector setting.");
        if (Config.inWindow(c, Calendar.getInstance()) && evening(c, Calendar.getInstance()).equals(p.getString("woke", ""))) {
            b.append(" Already turned on this evening.");
        }
        long next = p.getLong("next_at", 0);
        if (next > 0) b.append(String.format(Locale.US, " Next check %tR.", next));
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        b.append(dpm.isAdminActive(new ComponentName(c, Admin.class))
                ? " Turns the screen off with lockNow (device admin on)."
                : " Device admin off, so it turns the screen off with the accessibility lock action.");
        return b.toString();
    }
}
