package com.framehome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Settings over ADB, so nothing has to be typed with the remote. Shell-only (DUMP permission).
 *
 *   am broadcast -n com.framehome/.ConfigReceiver --es url http://192.168.1.20:2283 --es key KEY
 *       [--es album ALBUM_UUID] [--ei interval 20] [--ez sync true] [--ez clear true]
 *       [--es fallback auto|aerial|amazon|sleep|none|<component>] [--es offline saved|fallback]
 *       [--es previous <component>] [--ez route true] [--es import /sdcard/DIR]
 *       [--es art on|off] [--es weather_lat 37.5 --es weather_lon -122.0] [--es weather_unit fahrenheit|celsius]
 *
 * An empty album ("--es album ''") means the whole library. With sync, the photo list is fetched
 * now and some photos are saved on the TV. With route (implied by sync), the system screensaver is
 * pointed at Frame or the fallback. With import, the JPEG and PNG files in DIR become the home
 * screen's backgrounds while no Immich photos are saved (needs READ_EXTERNAL_STORAGE, granted by
 * install.sh). With art on (the default) and route, some paintings are saved now. An empty
 * weather_lat turns the weather off. The result is printed by am as the broadcast's data.
 */
public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PhotoStore store = PhotoStore.get(context);
        if (intent.getBooleanExtra("clear", false)) store.clear();
        store.configure(intent.getStringExtra("url"), intent.getStringExtra("key"),
                intent.getStringExtra("album"), intent.getIntExtra("interval", 0));
        store.configureFallback(intent.getStringExtra("fallback"), intent.getStringExtra("offline"),
                intent.getStringExtra("previous"));
        final String importDir = intent.getStringExtra("import");
        final ArtStore art = ArtStore.get(context);
        final String artMode = intent.getStringExtra("art");
        if (artMode != null) art.setEnabled(!"off".equals(artMode.trim()));
        if (intent.hasExtra("weather_lat") || intent.hasExtra("weather_unit")) {
            Weather.configure(context, intent.getStringExtra("weather_lat"), intent.getStringExtra("weather_lon"),
                    intent.getStringExtra("weather_unit"));
        }
        final boolean sync = intent.getBooleanExtra("sync", false);
        final boolean route = sync || intent.getBooleanExtra("route", false);
        final Context app = context.getApplicationContext();
        HealthJob.schedule(app);
        final PendingResult result = goAsync();
        new Thread("frame-config") {
            @Override
            public void run() {
                String message = store.describe();
                if (importDir != null) {
                    try {
                        int n = store.importBackgrounds(new File(importDir));
                        message = "Imported " + n + " home screen backgrounds. " + message;
                    } catch (IOException e) {
                        message = "Import failed: " + e.getMessage() + " " + message;
                    }
                }
                if (sync && store.configured()) {
                    try {
                        int photos = store.sync();
                        // Stay well inside the 60 s limit of a background broadcast.
                        int saved = store.prefetch(24, 35_000);
                        message = "Synced " + photos + " photos and saved " + saved + " more on the TV. " + message;
                    } catch (Exception e) {
                        String why = e.getMessage() == null ? e.toString() : e.getMessage();
                        store.recordError(why);
                        message = "Sync failed: " + why + " " + message;
                    }
                }
                if (art.enabled() && (route || artMode != null)) {
                    // Whatever is left of the 60 s a background broadcast may take.
                    int saved = art.refresh(12, sync ? 15_000 : 35_000);
                    message = message + " " + art.status() + (saved > 0 ? " (" + saved + " new)" : "");
                }
                Weather.refreshIfStale(app);
                if (route || artMode != null) message = message + " " + HealthJob.check(app, false);
                else if (ScreensaverRouter.lastRoute(app).length() > 0) message = message + " " + ScreensaverRouter.lastRoute(app);
                Log.i(PhotoStore.TAG, message);
                message = message.trim();
                result.setResultData(message);
                result.finish();
            }
        }.start();
    }
}
