package com.sparkflow.guard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 后台轮询面板 /api/notifications，把「日报」和「定时任务」两类推成系统通知。
 *
 * - 日报（标题 早报/晚报）：随时推。
 * - 定时任务（标题 定时任务…）：只在白天推（默认 08:00~22:00，可在 App 内改）。
 * - 其它（火花抓取心跳异常之类）：不推系统通知，面板里本来就有。
 *
 * 用 JobScheduler（平台 API，无需第三方库）以 15 分钟为周期执行，系统会合并调度省电。
 */
public class NotifyJobService extends JobService {

    private static final String TAG = "SparkNotify";
    static final int JOB_ID = 9001;
    private static final long PERIOD_MS = 15L * 60L * 1000L;
    private static final int MAX_PER_ROUND = 4;   // 一轮最多推几条，避免刷屏

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, NotifyJobService.class))
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build();
            js.schedule(info);
        } catch (Throwable t) {
            Log.w(TAG, "schedule failed: " + t);
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

    /* ------------------------------------------------------------------
       [1.0.2] AlarmManager 兜底通道
       JobScheduler 的周期任务只是「系统有空时跑」：Doze 深睡时会被拖到维护窗口，
       部分 ROM 的省电策略更会把它冻结 —— 表现就是「只有打开 App 才收到通知」。
       这里额外排一个 15 分钟后的 RTC_WAKEUP 闹钟（setAndAllowWhileIdle 在
       Doze 下也能唤醒），跑完由 receiver 再排下一次（自续），与 JobScheduler 双保险。
       ------------------------------------------------------------------ */

    static final int ALARM_REQ = 9003;
    static final long ALARM_PERIOD_MS = 15L * 60L * 1000L;

    private static PendingIntent alarmPi(Context c) {
        Intent i = new Intent(c, NotifyAlarmReceiver.class);
        i.setAction("com.sparkflow.guard.POLL");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, ALARM_REQ, i, flags);
    }

    /** 排下一次唤醒（每次执行后都要重新排，闹钟是一次性的）。 */
    public static void scheduleAlarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            PendingIntent pi = alarmPi(c);
            long at = System.currentTimeMillis() + ALARM_PERIOD_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "scheduleAlarm: " + t);
        }
    }

    public static void cancelAlarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(alarmPi(c));
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    poll(getApplicationContext());
                    UpdateJobService.checkIfDue(getApplicationContext());   // [1.0.3] 搭车查更新（6h 节流）
                } catch (Throwable t) {
                    Log.w(TAG, "poll failed: " + t);
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "spark-notify").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /* ------------------------------------------------------------------ */

    /** 拉一次通知并把新条目推出去。供 JobService 与 App 前台手动刷新共用。 */
    public static void poll(Context ctx) {
        if (!AppConfig.notifyOn(ctx)) {
            return;
        }
        String token = AppConfig.token(ctx);
        if (token.length() == 0) {
            return;   // 还没登录过网页，拿不到 token
        }
        if (!Http.online(ctx)) {
            return;
        }
        String body;
        try {
            body = Http.get(AppConfig.base(ctx) + "/api/notifications", token);
        } catch (Throwable t) {
            Log.w(TAG, "http: " + t);
            return;
        }

        List<Item> fresh = new ArrayList<Item>();
        long newest = -1;
        try {
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("notifications");
            if (arr == null) {
                return;
            }
            long last = AppConfig.lastSeenT(ctx);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject n = arr.optJSONObject(i);
                if (n == null) {
                    continue;
                }
                double td = n.optDouble("t", 0);
                long t = (long) td;
                if (t <= 0) {
                    continue;
                }
                if (t > newest) {
                    newest = t;
                }
                if (last <= 0 || t <= last) {
                    continue;   // 首次运行或已推过
                }
                Item it = new Item();
                it.t = t;
                it.title = n.optString("title", "");
                it.msg = n.optString("msg", "");
                it.type = n.optString("type", "info");
                it.kind = classify(it.title);
                if (it.kind != KIND_OTHER) {
                    fresh.add(it);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "parse: " + t);
            return;
        }

        // 首次运行：只对齐水位，不补推历史
        if (AppConfig.lastSeenT(ctx) <= 0) {
            AppConfig.setLastSeenT(ctx, newest < 0 ? 0 : newest);
            return;
        }
        if (fresh.isEmpty()) {
            if (newest > AppConfig.lastSeenT(ctx)) {
                AppConfig.setLastSeenT(ctx, newest);
            }
            return;
        }

        Collections.sort(fresh, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                return a.t < b.t ? -1 : (a.t > b.t ? 1 : 0);
            }
        });
        int from = Math.max(0, fresh.size() - MAX_PER_ROUND);
        boolean quiet = AppConfig.inQuietHours(ctx);
        for (int i = from; i < fresh.size(); i++) {
            Item it = fresh.get(i);
            if (it.kind == KIND_DAILY && !AppConfig.notifyDaily(ctx)) {
                continue;
            }
            if (it.kind == KIND_TASK) {
                if (!AppConfig.notifyTask(ctx)) {
                    continue;
                }
                if (quiet) {
                    continue;   // 夜间不提醒任务
                }
            }
            NotifyHelper.show(ctx, NotifyHelper.idFor(it.t), it.title, NotifyHelper.tidy(it.msg));
        }
        AppConfig.setLastSeenT(ctx, newest);
    }

    static final int KIND_DAILY = 1;
    static final int KIND_TASK = 2;
    static final int KIND_OTHER = 0;

    static int classify(String title) {
        String s = title == null ? "" : title.trim();
        if (s.startsWith("早报") || s.startsWith("晚报")) {
            return KIND_DAILY;
        }
        if (s.startsWith("定时任务")) {
            return KIND_TASK;
        }
        return KIND_OTHER;
    }

    static final class Item {
        long t;
        String title;
        String msg;
        String type;
        int kind;
    }
}
