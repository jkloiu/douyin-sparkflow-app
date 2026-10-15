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
        NotifyJobService.scheduleAlarm(c);   // [1.0.2] AlarmManager 兜底：只靠 JobScheduler 会被 Doze/省电策略冻住
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

    /** 检查一次；有新版本就按设置走「通知」或「静默下载」。6 小时一次，也被通知轮询顺带触发。 */
    /**
     * [1.0.3] 搭在 15 分钟通知轮询上的「轻量检查」：6 小时内最多真查一次。
     * 为什么需要它：update 的 JobScheduler 周期是 6 小时，被 Doze / ROM 省电冻住后
     * 可能一整天都不跑 —— 而通知轮询有 AlarmManager 兜底，明显更容易活下来。
     */
    static void checkIfDue(final Context ctx) {
        long now = System.currentTimeMillis() / 1000L;
        if (now - AppConfig.lastUpdateCheckAt(ctx) < 6L * 3600L) {
            return;
        }
        AppConfig.setLastUpdateCheckAt(ctx, now);
        checkAndDownload(ctx);
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

        /*
         * [1.0.3 修] 以前这里只要 autoUpdate=true（默认值）就直接静默下载，而且
         * onProgress/onError 都是空的：进度看不见、失败也不吭声，用户感觉
         * 「有新版本从来没人告诉我」。现在两条路径都给可见的反馈。
         */
        final UpdateManager.Info fi = info;

        // 已经下好了还没装 → 每次都提醒去安装（不再重新下载一遍）
        File have = new File(UpdateManager.updateDir(ctx), AppConfig.APK_TMP_NAME);
        if (AppConfig.pendingCode(ctx) == info.versionCode && have.exists() && have.length() > 1024) {
            NotifyHelper.show(ctx, NotifyHelper.ID_UPDATE,
                    "火花守护 " + info.versionName + " 已下载完成",
                    "点此安装新版本（系统会再确认一次）");
            return;
        }

        if (!AppConfig.autoUpdate(ctx)) {
            long now = System.currentTimeMillis() / 1000L;
            if (now - AppConfig.updatePromptAt(ctx) < AppConfig.PROMPT_INTERVAL) {
                return;   // 同一波提示别太密（6 小时）
            }
            AppConfig.setUpdatePromptAt(ctx, now);
            NotifyHelper.show(ctx, NotifyHelper.ID_UPDATE, "发现新版本 " + info.versionName,
                    "点此打开「火花守护」进行更新");
            return;
        }

        NotifyHelper.showProgress(ctx, NotifyHelper.ID_UPDATE_PROGRESS,
                "正在下载 火花守护 " + fi.versionName, "0%", 0, true);
        UpdateManager.download(ctx, fi, new UpdateManager.DlCb() {
            public void onProgress(long got, long total) {
                int pct = total > 0 ? (int) (got * 100L / total) : 0;
                String txt = total > 0
                        ? (pct + "%（" + (total / 1024) + " KB）")
                        : ("已下载 " + (got / 1024) + " KB");
                NotifyHelper.showProgress(ctx, NotifyHelper.ID_UPDATE_PROGRESS,
                        "正在下载 火花守护 " + fi.versionName, txt, pct, total <= 0);
            }

            public void onDone(File apk) {
                AppConfig.setPendingCode(ctx, fi.versionCode);
                NotifyHelper.cancel(ctx, NotifyHelper.ID_UPDATE_PROGRESS);
                NotifyHelper.show(ctx, NotifyHelper.ID_UPDATE,
                        "火花守护 " + fi.versionName + " 已下载完成",
                        "点此安装新版本（系统会再确认一次）");
                // 能装就顺手拉起系统安装界面（后台启动 Activity 在部分 ROM 上会被拦，拦了就靠上面那条通知）
                if (UpdateManager.canInstall(ctx)) {
                    UpdateManager.install(ctx, apk);
                }
            }

            public void onError(String msg) {
                Log.w(TAG, "download: " + msg);
                NotifyHelper.cancel(ctx, NotifyHelper.ID_UPDATE_PROGRESS);
                NotifyHelper.show(ctx, NotifyHelper.ID_UPDATE,
                        "火花守护 " + fi.versionName + " 下载失败",
                        "点此打开 App 后重试（" + msg + "）");
            }
        });
    }
}
