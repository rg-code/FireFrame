package com.framehome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Every 15 minutes (and after boot): checks Immich, saves photos while the fallback is in use so
 * Frame can take over again, saves or rotates paintings, refreshes the weather, and points the
 * system screensaver at whichever one should run.
 */
public class HealthJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(ID) != null) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, HealthJob.class))
                .setPeriodic(15L * 60 * 1000)
                .setPersisted(true)
                .build());
    }

    /** Runs the check now. Network and disk: call off the main thread. */
    static String check(Context c, boolean allowDownloads) {
        PhotoStore s = PhotoStore.get(c);
        Boolean reachable = s.configured() ? Boolean.valueOf(s.ping()) : null;
        if (allowDownloads && Boolean.TRUE.equals(reachable) && s.savedCount() < 24) {
            s.syncIfStale();
            s.prefetch(24, 120_000);
        }
        if (allowDownloads) {
            ArtStore.get(c).refresh(8, 120_000);
            Weather.refreshIfStale(c);
        }
        String result = ScreensaverRouter.apply(c, ScreensaverRouter.decide(c, reachable));
        Log.i(PhotoStore.TAG, "health: " + result);
        return result;
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread("frame-health") {
            @Override
            public void run() {
                check(getApplicationContext(), true);
                jobFinished(params, false);
            }
        }.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
