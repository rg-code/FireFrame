package com.canvashome;

import android.content.ComponentName;
import android.content.Context;

import java.io.File;

/** The status lines for ./install.sh status and the app's own screen. */
final class Status {
    private Status() {
    }

    static String describe(Context c, boolean sensors) {
        StringBuilder b = new StringBuilder();
        b.append(Overlay.describe(c)).append('\n');
        ComponentName art = Config.artComponent(c);
        b.append(art == null ? "Art Mode: not learned yet (open Art Mode on the TV, then ./install.sh learn)."
                : "Art Mode: " + art.flattenToShortString() + ".").append('\n');
        ArtStore store = ArtStore.get(c);
        File dir = Config.photoDir(c);
        b.append(store.status()).append(" Folder: ").append(dir.getPath()).append('.');
        if (store.enabled() && !writable(dir)) b.append(" Can't write there: run ./install.sh again (it grants storage).");
        b.append('\n').append(Schedule.describe(c)).append('\n').append(Presence.describe(c, sensors));
        return b.toString();
    }

    private static boolean writable(File dir) {
        dir.mkdirs();
        File probe = new File(dir, ".canvas-probe");
        try {
            boolean ok = probe.createNewFile() || probe.exists();
            probe.delete();
            return ok;
        } catch (java.io.IOException | SecurityException e) {
            return false;
        }
    }
}
