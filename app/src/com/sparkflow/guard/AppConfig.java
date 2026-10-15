package com.sparkflow.guard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局常量与本地偏好（SharedPreferences）。
 * 没有引入任何第三方库，纯平台 API。
 */
public final class AppConfig {

    /** 当前构建的版本号，必须与 AndroidManifest 的 versionCode 一致（打包脚本会校验）。 */
    public static final int VERSION_CODE = 4;
    public static final String VERSION_NAME = "1.0.3";

    /** 面板地址与默认页面。App 内可在「客户端设置」里改。 */
    public static final String DEFAULT_BASE = "http://121.36.38.180:16080";
    public static final String PAGE_PATH = "/static/console4.html";
    /** 版本清单与 APK 都放在面板的 /static/ 下。 */
    public static final String VERSION_PATH = "/static/app-version.json";
    public static final String APK_TMP_NAME = "sparkflow-update.apk";

    public static final String AUTHORITY = "com.sparkflow.guard.fileprovider";

    private static final String SP = "sparkflow";

    /** 通知相关默认值：任务类提醒只在白天（08:00 ~ 22:00）。 */
    public static final int DEFAULT_QUIET_START = 22;  // 22 点起不再推「任务」
    public static final int DEFAULT_QUIET_END = 8;     // 次日 8 点恢复推送

    private AppConfig() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* ---------- 面板地址 / 登录态 ---------- */

    public static String base(Context c) {
        String v = sp(c).getString("base", DEFAULT_BASE);
        if (v == null || v.length() == 0) {
            v = DEFAULT_BASE;
        }
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public static void setBase(Context c, String v) {
        sp(c).edit().putString("base", v == null ? DEFAULT_BASE : v.trim()).apply();
    }

    public static String token(Context c) {
        String v = sp(c).getString("token", "");
        return v == null ? "" : v;
    }

    /** 由 MainActivity 从网页 localStorage 里取回来（网页登录后即写入）。 */
    public static void setToken(Context c, String v) {
        sp(c).edit().putString("token", v == null ? "" : v.trim()).apply();
    }

    /* ---------- 通知开关 ---------- */

    public static boolean notifyOn(Context c) {
        return sp(c).getBoolean("notify_on", true);
    }

    public static void setNotifyOn(Context c, boolean v) {
        sp(c).edit().putBoolean("notify_on", v).apply();
    }

    /** 日报（早报 / 晚报）。 */
    public static boolean notifyDaily(Context c) {
        return sp(c).getBoolean("notify_daily", true);
    }

    public static void setNotifyDaily(Context c, boolean v) {
        sp(c).edit().putBoolean("notify_daily", v).apply();
    }

    /** 定时任务结果。 */
    public static boolean notifyTask(Context c) {
        return sp(c).getBoolean("notify_task", true);
    }

    public static void setNotifyTask(Context c, boolean v) {
        sp(c).edit().putBoolean("notify_task", v).apply();
    }

    public static int quietStart(Context c) {
        return sp(c).getInt("quiet_start", DEFAULT_QUIET_START);
    }

    public static int quietEnd(Context c) {
        return sp(c).getInt("quiet_end", DEFAULT_QUIET_END);
    }

    public static void setQuiet(Context c, int start, int end) {
        sp(c).edit().putInt("quiet_start", start).putInt("quiet_end", end).apply();
    }

    /** 任务类通知当前是否处于免打扰时段。 */
    public static boolean inQuietHours(Context c) {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int s = quietStart(c), e = quietEnd(c);
        if (s == e) {
            return false;
        }
        if (s < e) {
            return h >= s && h < e;
        }
        return h >= s || h < e;   // 跨零点
    }

    /* ---------- 自更新 ---------- */

    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean("auto_update", true);
    }

    public static void setAutoUpdate(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_update", v).apply();
    }

    /** 已经推送过的最后一条通知时间戳（秒）。 */
    public static long lastSeenT(Context c) {
        return sp(c).getLong("last_seen_t", 0L);
    }

    public static void setLastSeenT(Context c, long sec) {
        sp(c).edit().putLong("last_seen_t", sec).apply();
    }

    /*
     * [1.0.3] 提示节流：不要再按「版本号」记「已提示过」，那样只要提示过一次
     * （哪怕用户点的是「稍后」、哪怕下载/安装失败）就永远不会再提醒。
     * 改成按时间节流：该提醒就提醒，间隔到了继续提醒，直到用户真的升级上去。
     */

    /** 「发现新版本」提示 / 通知的间隔（秒）。 */
    public static final long PROMPT_INTERVAL = 6L * 3600L;
    /** 「已下载待安装」提示的间隔（秒）—— 有包没装就一直提醒，但别太频繁。 */
    public static final long INSTALL_PROMPT_INTERVAL = 300L;

    /** 上次提示「发现新版本」的时间戳（秒）。 */
    public static long updatePromptAt(Context c) {
        return sp(c).getLong("update_prompt_at", 0L);
    }

    public static void setUpdatePromptAt(Context c, long sec) {
        sp(c).edit().putLong("update_prompt_at", sec).apply();
    }

    /**
     * [1.0.3] 上次后台检查更新的时间戳（秒）。
     * 通知轮询每 15 分钟跑一次，顺手搭车查更新，避免 6 小时的 update job 被 ROM 冻死后
     * 用户永远不知道自己有新版本。
     */
    public static long lastUpdateCheckAt(Context c) {
        return sp(c).getLong("update_check_at", 0L);
    }

    public static void setLastUpdateCheckAt(Context c, long sec) {
        sp(c).edit().putLong("update_check_at", sec).apply();
    }

    /** 上次提示「有已下载的包待安装」的时间戳（秒）。 */
    public static long installPromptAt(Context c) {
        return sp(c).getLong("install_prompt_at", 0L);
    }

    public static void setInstallPromptAt(Context c, long sec) {
        sp(c).edit().putLong("install_prompt_at", sec).apply();
    }

    /** 已经下载完、等着安装的新版本号（0 = 没有待安装包）。 */
    public static int pendingCode(Context c) {
        return sp(c).getInt("pending_code", 0);
    }

    public static void setPendingCode(Context c, int v) {
        sp(c).edit().putInt("pending_code", v).apply();
    }

    /* ---------- 后台存活（电池优化白名单） ---------- */

    /** 是否已经提示过「允许后台运行」，避免每次启动都弹。 */
    public static boolean batteryAsked(Context c) {
        return sp(c).getBoolean("battery_asked", false);
    }

    public static void setBatteryAsked(Context c, boolean v) {
        sp(c).edit().putBoolean("battery_asked", v).apply();
    }
}
