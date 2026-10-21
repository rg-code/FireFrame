package com.framehome;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Chooses which screensaver Fire OS runs: Frame's (Immich photos and paintings) when it has
 * something to show, a fallback when it doesn't. With paintings saved, Frame always has something. Switching is done ahead of time, by writing the system setting (needs
 * WRITE_SECURE_SETTINGS, granted once over ADB), so whatever starts at idle is an ordinary
 * screensaver. If Frame's screensaver starts anyway with nothing to show, {@link ImmichDream} hands
 * over to Aerial Views on the spot and switches the setting for next time.
 *
 * Fallback modes (ConfigReceiver extra "fallback"):
 *   auto    the screensaver that was set before Frame, else Aerial Views if installed, else Amazon's
 *   aerial  Aerial Views (Amazon's if it isn't installed)
 *   amazon  Amazon's screensaver
 *   sleep   no screensaver: the stick goes to sleep, stops the HDMI signal and the TV takes over
 *   none    Frame stays on and says what's wrong
 *   or a component name, e.g. com.example/.MyDream
 */
final class ScreensaverRouter {
    static final String FRAME = "com.framehome/com.framehome.ImmichDream";
    static final String AERIAL_PKG = "com.neilturner.aerialviews";
    static final String AERIAL = AERIAL_PKG + "/.ui.screensaver.DreamActivity";
    static final String AMAZON = "com.amazon.ftv.screensaver/.app.services.ScreensaverService";
    static final int MIN_SAVED = 10;

    // Fire OS 7.6+ reads screensaver_default_component as well as screensaver_components
    // (documented by Aerial Views for Fire TV), so both are written.
    private static final String[] KEYS = {"screensaver_components", "screensaver_default_component"};

    static final class Route {
        final String component; // what the system screensaver should be
        final String reason;    // null when Frame is healthy

        Route(String component, String reason) {
            this.component = component;
            this.reason = reason;
        }

        boolean isFrame() {
            return FRAME.equals(component);
        }
    }

    private ScreensaverRouter() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("frame", Context.MODE_PRIVATE);
    }

    static String mode(Context c) {
        return prefs(c).getString("fallback", "auto").trim();
    }

    /**
     * @param reachable whether Immich answered a ping just now; null if not checked
     */
    static Route decide(Context c, Boolean reachable) {
        PhotoStore s = PhotoStore.get(c);
        if (reachable == null) reachable = s.lastPing();
        String why = null;
        boolean art = ArtStore.get(c).savedCount() >= ArtStore.MIN_SAVED;
        if (s.recentFailure(Slideshow.isRunning())) {
            why = "Frame's screensaver failed in the last hour";
        } else if (art) {
            why = null; // paintings cover whatever Immich can't show
        } else if (!s.configured()) {
            why = "Immich isn't set up yet and no paintings are saved yet";
        } else if (Boolean.FALSE.equals(reachable) && "fallback".equals(s.offlinePolicy())) {
            why = "Immich is offline";
        } else if (s.savedCount() < MIN_SAVED && !(Boolean.TRUE.equals(reachable) && s.poolSize() > 0)) {
            why = "fewer than " + MIN_SAVED + " photos are saved and Immich isn't reachable";
        }
        if (why == null) return new Route(FRAME, null);
        String fallback = fallbackComponent(c);
        return new Route(fallback == null ? FRAME : fallback, why);
    }

    /** The component to switch to, or null when the fallback is "sleep" or "none" (Frame stays set). */
    static String fallbackComponent(Context c) {
        String mode = mode(c);
        if ("none".equals(mode) || "sleep".equals(mode)) return null;
        if ("amazon".equals(mode)) return AMAZON;
        if ("aerial".equals(mode)) return installed(c, AERIAL_PKG) ? AERIAL : AMAZON;
        if (mode.contains("/")) return mode;
        String previous = prefs(c).getString("previous", "").trim();
        if (previous.contains("/") && !FRAME.equals(previous) && !previous.startsWith("null")
                && installed(c, previous.substring(0, previous.indexOf('/')))) {
            return previous;
        }
        return installed(c, AERIAL_PKG) ? AERIAL : AMAZON;
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Writes the route into the system settings if it differs. Returns a sentence about the result. */
    static String apply(Context c, Route r) {
        String what = name(r.component) + (r.reason == null ? "" : ", because " + r.reason);
        if (r.reason != null && r.isFrame()) {
            what = "Frame (fallback is " + mode(c) + "), but " + r.reason;
        }
        if (!canWrite(c)) {
            String msg = "Can't switch screensavers: run ./install.sh again to grant the permission. Wanted: " + what + ".";
            prefs(c).edit().putString("route", msg).apply();
            return msg;
        }
        ContentResolver cr = c.getContentResolver();
        boolean changed = false;
        try {
            for (String key : KEYS) {
                if (!r.component.equals(Settings.Secure.getString(cr, key))) {
                    Settings.Secure.putString(cr, key, r.component);
                    changed = true;
                }
            }
            if (Settings.Secure.getInt(cr, "screensaver_enabled", 1) != 1) {
                Settings.Secure.putInt(cr, "screensaver_enabled", 1);
            }
        } catch (SecurityException e) {
            String msg = "Fire OS refused the screensaver setting: " + e.getMessage();
            prefs(c).edit().putString("route", msg).apply();
            return msg;
        }
        String msg = "Screensaver: " + what + ".";
        if (changed) Log.i(PhotoStore.TAG, "switched. " + msg);
        prefs(c).edit().putString("route", msg).apply();
        return msg;
    }

    static String lastRoute(Context c) {
        return prefs(c).getString("route", "");
    }

    /** Starts Aerial Views' screensaver activity, which handles taking over from a dream. */
    static boolean startAerial(Context c) {
        if (!installed(c, AERIAL_PKG)) return false;
        Intent i = new Intent()
                .setClassName(AERIAL_PKG, AERIAL_PKG + ".ui.screensaver.TestActivity")
                .putExtra("from_dream", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(PhotoStore.TAG, "couldn't start Aerial Views: " + e);
            return false;
        }
    }

    static String name(String component) {
        if (FRAME.equals(component)) return "Frame";
        if (component.startsWith(AERIAL_PKG + "/")) return "Aerial Views";
        if (component.startsWith("com.amazon.ftv.screensaver/")) return "Amazon's screensaver";
        return component;
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
