package com.canvashome;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/** Every 3 hours on a network: fills the photo collection, and rotates a few paintings each day. */
public class ArtJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(ID) != null) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, ArtJob.class))
                .setPeriodic(3L * 60 * 60 * 1000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread("canvas-art") {
            @Override
            public void run() {
                int n = ArtStore.get(getApplicationContext()).refresh(12, 8 * 60_000);
                Log.i(Config.TAG, "art job: " + n + " new");
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
