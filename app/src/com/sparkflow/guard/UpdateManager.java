package com.sparkflow.guard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;

/**
 * 自更新：向面板要 app-version.json → 比 versionCode → 下载 APK → 拉起系统安装器。
 *
 * 系统不允许普通应用静默安装，所以「自动」的边界是：
 *   自动发现 + 自动下载（后台，带进度通知）+ 自动拉起安装界面；
 *   最后一步的「安装」按钮由用户在系统弹窗里点一下（这是安卓的安全限制，绕不过去）。
 */
public final class UpdateManager {

    private static final String TAG = "SparkUpdate";

    private UpdateManager() {
    }

    public static final class Info {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String notes = "";
        public String md5 = "";
        public boolean force;
    }

    /** 供 UI 回调。 */
    public interface CheckCb {
        void onResult(Info info, String error);
    }

    public interface DlCb {
        void onProgress(int percent);

        void onDone(File apk);

        void onError(String msg);
    }

    public static File updateDir(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) {
            d = c.getFilesDir();
        }
        File u = new File(d, "update");
        if (!u.exists()) {
            u.mkdirs();
        }
        return u;
    }

    /** 后台线程调用。 */
    public static Info fetch(Context ctx) throws Exception {
        String url = AppConfig.base(ctx) + AppConfig.VERSION_PATH
                + "?_=" + System.currentTimeMillis();
        String body = Http.get(url, AppConfig.token(ctx));
        JSONObject o = new JSONObject(body);
        Info info = new Info();
        info.versionCode = o.optInt("versionCode", 0);
        info.versionName = o.optString("versionName", "");
        info.url = o.optString("url", "");
        info.notes = o.optString("notes", "");
        info.md5 = o.optString("md5", "");
        info.force = o.optBoolean("force", false);
        if (info.url.length() == 0 && info.versionCode > AppConfig.VERSION_CODE) {
            throw new Exception("版本清单缺少 url");
        }
        return info;
    }

    public static void check(final Context ctx, final CheckCb cb) {
        new Thread(new Runnable() {
            public void run() {
                Info info = null;
                String err = null;
                try {
                    info = fetch(ctx);
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage());
                    Log.w(TAG, "check: " + t);
                }
                if (cb != null) {
                    cb.onResult(info, err);
                }
            }
        }, "spark-update-check").start();
    }

    public static void download(final Context ctx, final Info info, final DlCb cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final File dest = new File(updateDir(ctx), AppConfig.APK_TMP_NAME);
                    if (dest.exists()) {
                        dest.delete();
                    }
                    long total = -1;
                    Http.download(info.url, dest, new Http.Progress() {
                        public void onProgress(long got, long totalBytes) {
                            if (cb != null && totalBytes > 0) {
                                cb.onProgress((int) (got * 100 / totalBytes));
                            }
                        }
                    });
                    if (!dest.exists() || dest.length() < 1024) {
                        cb.onError("下载文件异常");
                        return;
                    }
                    if (info.md5 != null && info.md5.length() > 0) {
                        String real = md5(dest);
                        if (!info.md5.equalsIgnoreCase(real)) {
                            dest.delete();
                            cb.onError("校验失败（md5 不一致）");
                            return;
                        }
                    }
                    if (cb != null) {
                        cb.onDone(dest);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "download: " + t);
                    if (cb != null) {
                        cb.onError(String.valueOf(t.getMessage()));
                    }
                }
            }
        }, "spark-update-dl").start();
    }

    /** 是否已获得「安装未知应用」权限（API 26+）。 */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 跳到「安装未知应用」授权页。 */
    public static void openInstallPermission(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
            i.setData(Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 拉起系统安装器。返回 false 表示需要先授权。 */
    public static boolean install(Context c, File apk) {
        if (!apk.exists()) {
            return false;
        }
        if (!canInstall(c)) {
            openInstallPermission(c);
            return false;
        }
        try {
            Uri uri = Uri.parse("content://" + AppConfig.AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "install: " + t);
            return false;
        }
    }

    public static String md5(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
