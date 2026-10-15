package com.sparkflow.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** 通知渠道与发送。只用平台 API。 */
public final class NotifyHelper {

    public static final String CH_MAIN = "spark_main";
    public static final int ID_FOREGROUND_HINT = 1001;
    public static final int ID_UPDATE = 1002;
    /** [1.0.3] 下载中那条带进度条的通知（下完会被 ID_UPDATE 顶掉）。 */
    public static final int ID_UPDATE_PROGRESS = 1003;
    private static final int ID_BASE = 2000;

    private NotifyHelper() {
    }

    public static NotificationManager mgr(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager m = mgr(c);
        if (m == null) {
            return;
        }
        if (m.getNotificationChannel(CH_MAIN) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_MAIN, c.getString(R.string.notify_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(c.getString(R.string.notify_channel_desc));
            ch.enableVibration(true);
            m.createNotificationChannel(ch);
        }
    }

    /** Android 13+ 需要用户在运行时授予通知权限。 */
    public static boolean canNotify(Context c) {
        if (Build.VERSION.SDK_INT >= 33) {
            return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * 点通知回到 App，并带上 from_notification=id。
     * MainActivity 的 onNewIntent / onCreate 靠这个 extra 判断「该直接去装新版本」。
     */
    public static PendingIntent piFor(Context c, int id) {
        Intent it = new Intent(c, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        it.putExtra("from_notification", id);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(c, id, it, piFlags);
    }

    public static Notification build(Context c, String title, String body, boolean ongoing, int id) {
        ensureChannel(c);
        PendingIntent pi = piFor(c, id);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CH_MAIN);
        } else {
            b = new Notification.Builder(c);
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        b.setSmallIcon(R.drawable.ic_stat_spark)
                .setContentTitle(title == null ? c.getString(R.string.app_name) : title)
                .setContentText(body == null ? "" : body)
                .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                .setAutoCancel(true)
                .setOngoing(ongoing)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setColor(0xFFFF7A45);
        }
        return b.build();
    }

    public static void show(Context c, int id, String title, String body) {
        if (!canNotify(c)) {
            return;
        }
        NotificationManager m = mgr(c);
        if (m == null) {
            return;
        }
        ensureChannel(c);
        try {
            m.notify(id, build(c, title, body, false, id));
        } catch (Throwable ignore) {
        }
    }

    /**
     * [1.0.3] 带进度条的通知（用于 App 后台下载新版本）。
     * percent < 0 或 indeterminate=true 时显示不确定进度（服务端没给 Content-Length 的兜底）。
     */
    public static void showProgress(Context c, int id, String title, String body,
                                    int percent, boolean indeterminate) {
        if (!canNotify(c)) {
            return;
        }
        NotificationManager m = mgr(c);
        if (m == null) {
            return;
        }
        ensureChannel(c);
        int p = percent < 0 ? 0 : (percent > 100 ? 100 : percent);
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(c, CH_MAIN);
            } else {
                b = new Notification.Builder(c);
                b.setPriority(Notification.PRIORITY_LOW);
            }
            b.setSmallIcon(R.drawable.ic_stat_spark)
                    .setContentTitle(title == null ? c.getString(R.string.app_name) : title)
                    .setContentText(body == null ? "" : body)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setAutoCancel(false)
                    .setProgress(100, p, indeterminate)
                    .setContentIntent(piFor(c, id));
            if (Build.VERSION.SDK_INT >= 21) {
                b.setColor(0xFFFF7A45);
            }
            m.notify(id, b.build());
        } catch (Throwable ignore) {
        }
    }

    /** 撤掉某条通知（例如下载进度条完成后换内容通知）。 */
    public static void cancel(Context c, int id) {
        NotificationManager m = mgr(c);
        if (m == null) {
            return;
        }
        try {
            m.cancel(id);
        } catch (Throwable ignore) {
        }
    }

    /** 内容相关的稳定 id：同一条通知（按时间戳）不会重复堆叠。 */
    public static int idFor(long tSec) {
        return ID_BASE + (int) (tSec % 100000);
    }

    /** 去掉通知正文里的分隔线噪音，让系统通知里读起来清爽一些。 */
    public static String tidy(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("\r", "");
        t = t.replaceAll("(?m)^[-─=]{3,}\\s*$", "");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }
}
