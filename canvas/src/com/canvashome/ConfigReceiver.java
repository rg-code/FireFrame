package com.canvashome;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Settings over ADB, so nothing has to be typed with the remote. Shell-only (DUMP permission).
 *
 *   am broadcast -n com.canvashome/.ConfigReceiver
 *       [--es art_component pkg/cls]       Art Mode's activity (./install.sh learn finds it)
 *       [--es photo_dir /sdcard/DIR]       the folder Art Mode's photo collection reads
 *       [--es art on|off] [--ez sync true] paintings in that folder; sync saves some now
 *       [--es mat off|#RRGGBB]             the mat around each painting (existing ones are redone)
 *       [--es schedule 18:00-20:00|off]
 *       [--es key DPAD_DOWN]               the key that moves focus onto the icons
 *       [--ez start true] [--ez sleep true] [--ez tick true]   try Art Mode on, off, or a schedule check
 *       [--ez unadmin true]                gives up the device admin, so the app can be uninstalled
 *
 * Changing the folder or the mat removes the paintings saved so far (only Canvas's own files), and
 * art off removes them all. The result is printed by am as the broadcast's data.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        final ArtStore art = ArtStore.get(app);
        SharedPreferences.Editor e = Config.prefs(app).edit();
        String error = "";

        String component = intent.getStringExtra("art_component");
        if (component != null) {
            if (ComponentName.unflattenFromString(component.trim()) == null) error += "Not a component: " + component + ". ";
            else e.putString("art_component", component.trim());
        }
        boolean redo = false;
        String dir = intent.getStringExtra("photo_dir");
        if (dir != null && !dir.trim().equals(Config.photoDir(app).getPath())) {
            art.clear();
            e.putString("photo_dir", dir.trim());
            redo = true;
        }
        String mat = intent.getStringExtra("mat");
        if (mat != null) {
            art.clear();
            e.putString("mat", mat.trim());
            redo = true;
        }
        String key = intent.getStringExtra("key");
        if (key != null) e.putString("key", key.trim().toUpperCase(java.util.Locale.US).replace("KEYCODE_", ""));
        String schedule = intent.getStringExtra("schedule");
        if (schedule != null) {
            if ("off".equals(schedule.trim())) {
                e.putInt("start", -1);
            } else {
                String[] p = schedule.split("-");
                int start = p.length == 2 ? Config.parseMinute(p[0]) : -1;
                int end = p.length == 2 ? Config.parseMinute(p[1]) : -1;
                if (start < 0 || end < 0 || start == end) error += "Schedule should look like 18:00-20:00. ";
                else e.putInt("start", start).putInt("end", end).putString("woke", "");
            }
        }
        e.commit();

        String artMode = intent.getStringExtra("art");
        if (artMode != null) {
            boolean on = !"off".equals(artMode.trim());
            art.setEnabled(on);
            if (!on) art.clear();
        }
        Schedule.arm(app);
        ArtJob.schedule(app);
        Overlay o = Overlay.get();
        if (o != null) o.update();
        if (intent.getBooleanExtra("start", false)) Schedule.wake(app, "asked over ADB");
        if (intent.getBooleanExtra("sleep", false)) Schedule.sleep(app, "asked over ADB");
        if (intent.getBooleanExtra("tick", false)) Schedule.tick(app);
        if (intent.getBooleanExtra("unadmin", false)) {
            DevicePolicyManager dpm = (DevicePolicyManager) app.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.removeActiveAdmin(new ComponentName(app, Admin.class));
        }

        final boolean sync = intent.getBooleanExtra("sync", false) || redo || "on".equals(artMode);
        final String prefix = error;
        final PendingResult result = goAsync();
        new Thread("canvas-config") {
            @Override
            public void run() {
                String message = prefix;
                if (sync && art.enabled()) {
                    // Stay well inside the 60 s a background broadcast may take; the job saves the rest.
                    int saved = art.refresh(12, 40_000);
                    message += "Saved " + saved + " paintings now. ";
                }
                message += Status.describe(app);
                Log.i(Config.TAG, message.replace('\n', ' '));
                result.setResultData(message);
                result.finish();
            }
        }.start();
    }
}
