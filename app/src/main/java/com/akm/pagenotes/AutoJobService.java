package com.akm.pagenotes;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** প্রতি ~৬ ঘণ্টায় সিস্টেম নিজেই চালায় (ফোন রিস্টার্ট হলেও) — অ্যাপ খোলা লাগে না। */
public class AutoJobService extends JobService {

    static final int JOB_ID = 7001;

    static void start(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        JobInfo ji = new JobInfo.Builder(JOB_ID, new ComponentName(c, AutoJobService.class))
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        js.schedule(ji);
    }

    static void stop(Context c) {
        ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AutoPost.run(getApplicationContext());
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
