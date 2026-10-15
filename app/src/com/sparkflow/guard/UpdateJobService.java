package com.sparkflow.guard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 周期性检查新版本（默认 6 小时一次）。发现新版本：
 *  - 开了「自动更新」→ 后台静默下载，下完发通知，点通知进 App 直接拉起安装界面；
 *  - 没开 → 只发一条「发现新版本」通知。
 */
public class UpdateJobService extends JobService {

    private static final String TAG = "SparkUpdateJob";
    static final int JOB_ID = 9002;
    private static final long PERIOD_MS = 6L * 60L * 60L * 1000L;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJobService.class))
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            js.schedule(info);
        } catch (Throwable t) {
            Log.w(TAG, "schedule: " + t);
        }
    }

    public static void cancel(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                js.cancel(JOB_ID);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 每安装/更新一次 App 都重新排一遍，避免升级后 job 丢失。 */
    public static void rescheduleAll(Context c) {
        NotifyJobService.schedule(c);
        schedule(c);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    UpdateJobService.checkAndDownload(getApplicationContext());
                } catch (Throwable t) {
                    Log.w(TAG, "run: " + t);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "spark-update-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    static void checkAndDownload(final Context ctx) {
        if (!Http.online(ctx)) {
            return;
        }
        UpdateManager.Info info;
        try {
            info = UpdateManager.fetch(ctx);
        } catch (Throwable t) {
            return;
        }
        if (info == null || info.versionCode <= AppConfig.VERSION_CODE) {
            return;
        }
        if (!AppConfig.autoUpdate(ctx)) {
            if (AppConfig.promptedCode(ctx) != info.versionCode) {
                AppConfig.setPromptedCode(ctx, info.versionCode);
                NotifyHelper.show(ctx, NotifyHelper.ID_UPDATE, "发现新版本 " + info.versionName,
                        "点此进入应用更新");
            }
            return;
        }
        final UpdateManager.Info fi = info;
        UpdateManager.download(ctx, fi, new UpdateManager.DlCb() {
            public void onProgress(int percent) {
            }

            public void onDone(File apk) {
                AppConfig.setPendingCode(ctx, fi.versionCode);
                NotifyHelper.show(ctx, NotifyHelper.ID_UPDATE,
                        "火花守护 " + fi.versionName + " 已下载完成",
                        "点此安装新版本（系统会再确认一次）");
            }

            public void onError(String msg) {
                Log.w(TAG, "download: " + msg);
            }
        });
    }
}
