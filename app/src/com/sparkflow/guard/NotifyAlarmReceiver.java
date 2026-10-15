package com.sparkflow.guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * [1.0.2] AlarmManager 兜底通道的接收器。
 *
 * 背景：JobScheduler 的周期任务在 Doze 深睡、或厂商省电策略压制下会被大幅推迟甚至冻结，
 * 用户看到的现象就是「App 只有打开后才会通知」。这里用 RTC_WAKEUP 闹钟每 15 分钟
 * 唤醒一次（setAndAllowWhileIdle 在 Doze 下也能触发），跑完再排下一次，和 JobScheduler
 * 形成双保险 —— 任何一路活着都能把通知推出来。
 *
 * 注意：闹钟同样扛不住「强行停止」（Android 的系统规则：force-stop 会清掉全部 alarm 与 job），
 * 所以长期稳定还需要把 App 加进电池优化白名单（见 MainActivity 的入口与首次提示）。
 */
public class NotifyAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "SparkAlarm";

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (!AppConfig.notifyOn(app)) {
            return;   // 用户在设置里关了通知，就不要再自续了
        }
        final PendingResult pr = goAsync();   // 把进程多留一会儿，直到下面的线程跑完
        new Thread(new Runnable() {
            public void run() {
                try {
                    NotifyJobService.poll(app);
                } catch (Throwable t) {
                    Log.w(TAG, "poll: " + t);
                } finally {
                    try {
                        NotifyJobService.scheduleAlarm(app);   // 自续下一次
                    } catch (Throwable ignore) {
                    }
                    try {
                        pr.finish();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }, "spark-alarm").start();
    }
}
