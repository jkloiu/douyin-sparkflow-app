package com.sparkflow.guard;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.Button;

import org.json.JSONObject;

import java.io.File;

public class MainActivity extends Activity {

    private static final String TAG = "SparkMain";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIFY = 1002;

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> fileCb;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tokenLoopRunning;

    /* [1.0.3] 更新流程的 UI：下载进度对话框 + 安装提示（用字段防重复弹出） */
    private AlertDialog dlDlg;
    private ProgressBar dlBar;
    private TextView dlTxt;
    private AlertDialog installDlg;

    /** 每 20 秒把网页 localStorage 里的 panel_token 同步到本地，供后台任务用。 */
    private final Runnable tokenLoop = new Runnable() {
        public void run() {
            pullToken();
            if (tokenLoopRunning) {
                ui.postDelayed(this, 20000);
            }
        }
    };

    /* ================================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#0E131D"));
            getWindow().setNavigationBarColor(Color.parseColor("#0E131D"));
        }
        NotifyHelper.ensureChannel(this);
        UpdateJobService.rescheduleAll(this);
        maybeAskBattery();   // [1.0.2] 未加电池优化白名单时提示一次（只提示一次）

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0E131D"));

        web = new PullWebView(this, new PullWebView.Listener() {
            public void onPull(float p) {
                showPull(p);
            }

            public void onRelease(float p) {
                if (p >= 1f) {
                    web.reload();
                }
                showPull(0f);
            }
        });
        setupWeb();
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        bp.gravity = Gravity.TOP;
        root.addView(bar, bp);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setVisibility(View.GONE);

        setContentView(root);

        askNotificationPermission();
        web.loadUrl(AppConfig.base(this) + AppConfig.PAGE_PATH);
        UpdateManager.check(this, new UpdateManager.CheckCb() {
            public void onResult(UpdateManager.Info info, String error) {
                if (info == null || info.versionCode <= AppConfig.VERSION_CODE) {
                    return;
                }
                // 已经下好待安装 → 直接提示装；否则才提示「发现新版本」
                ui.post(new Runnable() {
                    public void run() {
                        checkPendingApk(false);
                    }
                });
                maybePromptUpdate(info);
            }
        });
        handleNotifyIntent(getIntent());   // [1.0.3] 进程被杀后从通知点进来也要能直接安装
    }

    private void showPull(float p) {
        if (bar == null) {
            return;
        }
        if (p <= 0f) {
            bar.setVisibility(View.GONE);
            bar.setProgress(0);
        } else {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress((int) (p * 100));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " SparkFlowApp/" + AppConfig.VERSION_NAME);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(Color.parseColor("#0E131D"));
        if (Build.VERSION.SDK_INT >= 19) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pullToken();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) {
                    showOffline();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(v.getUrl())) {
                    showOffline();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (p >= 100) {
                    bar.setVisibility(View.GONE);
                } else {
                    bar.setVisibility(View.VISIBLE);
                    bar.setProgress(p);
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, "web: " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCb != null) {
                    fileCb.onReceiveValue(null);
                }
                fileCb = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    toast("没有可用的文件选择器");
                    return false;
                }
            }
        });

        web.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String ua, String disposition,
                                        String mime, long len) {
                openExternally(url);
            }
        });
    }

    /** 站内链接留在 WebView，站外 / 非 http 交给系统处理。 */
    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        String base = AppConfig.base(this);
        if (url.startsWith(base) || url.startsWith("/") || url.startsWith("about:")) {
            return false;
        }
        openExternally(url);
        return true;
    }

    private void openExternally(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            toast("无法打开链接");
        }
    }

    private void showOffline() {
        if (web == null) {
            return;
        }
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#0e131d;color:#8b95a7;font:14px/1.7 -apple-system,'PingFang SC','Microsoft YaHei',sans-serif}"
                + ".b{text-align:center;padding:24px}h3{color:#e8ecf4;font-size:17px;margin:0 0 8px}"
                + "button{margin-top:18px;height:40px;padding:0 22px;border:0;border-radius:10px;"
                + "background:#ff7a45;color:#fff;font-size:14px;font-weight:600}</style></head><body><div class='b'>"
                + "<h3>连不上面板</h3><div>请检查手机网络，或稍后重试</div>"
                + "<button onclick=\"location.href='" + AppConfig.base(this) + AppConfig.PAGE_PATH + "'\">重新加载</button>"
                + "</div></body></html>";
        web.loadDataWithBaseURL(AppConfig.base(this), html, "text/html", "utf-8", null);
    }

    /* ================================================================== */

    /** 从网页 localStorage 取 panel_token 落到本地（后台任务靠它调 /api/notifications）。 */
    private void pullToken() {
        if (web == null) {
            return;
        }
        try {
            web.evaluateJavascript(
                    "(function(){try{return String(localStorage.getItem('panel_token')||'')}catch(e){return ''}})()",
                    new ValueCallback<String>() {
                        public void onReceiveValue(String value) {
                            String t = unquote(value);
                            if (t.length() > 0 && !t.equals(AppConfig.token(MainActivity.this))) {
                                AppConfig.setToken(MainActivity.this, t);
                            }
                        }
                    });
        } catch (Throwable ignore) {
        }
    }

    private static String unquote(String json) {
        if (json == null || json.length() < 2) {
            return "";
        }
        String s = json;
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        if ("null".equals(s)) {
            return "";
        }
        return s.replace("\\/", "/");
    }

    /* ================================================================== */

    @Override
    protected void onResume() {
        super.onResume();
        tokenLoopRunning = true;
        ui.postDelayed(tokenLoop, 800);
        checkPendingApk();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotifyIntent(intent);
    }

    @Override
    protected void onPause() {
        tokenLoopRunning = false;
        ui.removeCallbacks(tokenLoop);
        super.onPause();
    }

    /**
     * [1.0.3] 通知点击入口。
     * 之前 NotifyHelper 往 Intent 里塞了 from_notification，但没人读它 ——
     * 所以点「已下载完成，点此安装」只是把 App 打开，永远走不到安装那一步。
     */
    private void handleNotifyIntent(Intent it) {
        if (it == null) {
            return;
        }
        final int id = it.getIntExtra("from_notification", 0);
        if (id != NotifyHelper.ID_UPDATE && id != NotifyHelper.ID_UPDATE_PROGRESS) {
            return;
        }
        it.removeExtra("from_notification");
        ui.postDelayed(new Runnable() {
            public void run() {
                checkPendingApk(true);   // 从通知进来的：不受节流限制，立刻提示安装
            }
        }, 600);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        if (requestCode == REQ_NOTIFY) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) {
                toast("未授予通知权限，日报/任务提醒将不会弹出");
            }
        }
        super.onRequestPermissionsResult(requestCode, perms, results);
    }

    /* ================================================================== */

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !NotifyHelper.canNotify(this)) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIFY);
            } catch (Throwable ignore) {
            }
        }
    }

    private void checkPendingApk() {
        checkPendingApk(false);
    }

    /**
     * 有下载完待安装的包 → 提示安装。
     * [1.0.3 修] 原来这里被 AppConfig.promptedCode 卡住：弹「发现新版本」时就写了这个标记，
     * 导致下好包之后再也不提示安装。现在只做时间节流（force=true 时连节流都跳过）。
     */
    private void checkPendingApk(boolean force) {
        int code = AppConfig.pendingCode(this);
        if (code <= AppConfig.VERSION_CODE) {
            return;
        }
        File apk = new File(UpdateManager.updateDir(this), AppConfig.APK_TMP_NAME);
        if (!apk.exists() || apk.length() < 1024) {
            AppConfig.setPendingCode(this, 0);
            return;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (!force && now - AppConfig.installPromptAt(this) < AppConfig.INSTALL_PROMPT_INTERVAL) {
            return;
        }
        AppConfig.setInstallPromptAt(this, now);
        promptInstall(apk);
    }

    /**
     * 拉起安装。
     * [1.0.3 修] ① 统一 post 到主线程 —— 原来它是被下载线程直接调用的，
     * AlertDialog 在非 UI 线程 show() 会抛异常，异常又被 UpdateManager.download 的
     * catch 吞成 onError("下载失败…")，于是「明明下好了却提示失败、也没有安装提示」。
     * ② 先检查「安装未知应用」权限，没授权就直接引导去开，而不是拉起一个必然失败的 Intent。
     */
    private void promptInstall(final File apk) {
        ui.post(new Runnable() {
            public void run() {
                if (isFinishing() || installDlg != null) {
                    return;
                }
                if (!UpdateManager.canInstall(MainActivity.this)) {
                    try {
                        installDlg = new AlertDialog.Builder(MainActivity.this)
                                .setTitle("还差一步：允许安装")
                                .setMessage("新版本已经下载好了，但系统还要求你允许「火花守护」安装未知应用。\n\n"
                                        + "点「去授权」，打开开关后回来就会自动拉起安装界面。")
                                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        installDlg = null;
                                        UpdateManager.openInstallPermission(MainActivity.this);
                                    }
                                })
                                .setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface d, int w) {
                                        installDlg = null;
                                    }
                                })
                                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                    public void onCancel(DialogInterface d) {
                                        installDlg = null;
                                    }
                                })
                                .show();
                    } catch (Throwable ignore) {
                        installDlg = null;
                    }
                    return;
                }
                try {
                    installDlg = new AlertDialog.Builder(MainActivity.this)
                            .setTitle("安装新版本")
                            .setMessage("新版本已下载完成，点「安装」继续（系统会再确认一次）。")
                            .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    installDlg = null;
                                    if (!UpdateManager.install(MainActivity.this, apk)) {
                                        toast("拉起安装界面失败，请在文件管理里手动安装 "
                                                + apk.getAbsolutePath());
                                    }
                                }
                            })
                            .setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    installDlg = null;
                                }
                            })
                            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface d) {
                                    installDlg = null;
                                }
                            })
                            .show();
                } catch (Throwable ignore) {
                    installDlg = null;
                }
            }
        });
    }

    /**
     * 「发现新版本」弹窗。
     * [1.0.3 修] 两个致命问题：
     *  ① 它在 UpdateManager.check 的回调里被直接调用 —— 那是后台线程，AlertDialog.show()
     *     在非 UI 线程会抛异常，而异常被这里空的 catch 吞掉 → **用户永远看不到这个弹窗**。
     *     现在统一 post 到主线程。
     *  ② 原来用 promptedCode 做「只提示一次」，一旦提示过（哪怕点了「稍后」、哪怕下载失败）
     *     就再也不会提醒。改成 6 小时一次的时间节流，直到真的升级上去。
     */
    private void maybePromptUpdate(final UpdateManager.Info info) {
        ui.post(new Runnable() {
            public void run() {
                if (isFinishing()) {
                    return;
                }
                long now = System.currentTimeMillis() / 1000L;
                if (now - AppConfig.updatePromptAt(MainActivity.this) < AppConfig.PROMPT_INTERVAL) {
                    return;
                }
                AppConfig.setUpdatePromptAt(MainActivity.this, now);
                String note = info.notes == null || info.notes.length() == 0 ? "" : "\n\n" + info.notes;
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("发现新版本 " + info.versionName)
                            .setMessage("当前 " + AppConfig.VERSION_NAME + " → 最新 " + info.versionName + note)
                            .setCancelable(!info.force)
                            .setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    startDownload(info);
                                }
                            })
                            .setNegativeButton("稍后", null)
                            .show();
                } catch (Throwable t) {
                    Log.w(TAG, "prompt: " + t);
                }
            }
        });
    }

    /**
     * [1.0.3] 下载新版本：App 内一个带百分比的进度对话框 + 通知栏一条带进度条的通知
     * （切后台/息屏也能看到进展）。原来 onProgress 是空的，所以「看不了进度条」。
     */
    private void startDownload(final UpdateManager.Info info) {
        dismissDownloadDlg();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(12), pad, dp(2));
        dlTxt = new TextView(this);
        dlTxt.setText("正在连接服务器…");
        dlTxt.setTextSize(13);
        dlTxt.setTextColor(Color.parseColor("#8B95A7"));
        box.addView(dlTxt);
        dlBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlBar.setMax(100);
        dlBar.setProgress(0);
        dlBar.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        lp.topMargin = dp(14);
        box.addView(dlBar, lp);

        try {
            dlDlg = new AlertDialog.Builder(this)
                    .setTitle("更新到 " + info.versionName)
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton("后台下载", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            dismissDownloadDlg();
                            toast("已转入后台下载，通知栏可以看到进度");
                        }
                    })
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "dlg: " + t);
            dlDlg = null;
        }

        NotifyHelper.showProgress(this, NotifyHelper.ID_UPDATE_PROGRESS,
                "正在下载 火花守护 " + info.versionName, "0%", 0, true);

        UpdateManager.download(this, info, new UpdateManager.DlCb() {
            public void onProgress(final long got, final long total) {
                final int pct = total > 0 ? (int) (got * 100L / total) : -1;
                final String txt = total > 0
                        ? (mb(got) + " / " + mb(total) + "（" + pct + "%）")
                        : ("已下载 " + mb(got));
                ui.post(new Runnable() {
                    public void run() {
                        if (dlTxt != null) {
                            dlTxt.setText("正在下载 " + info.versionName + " … " + txt);
                        }
                        if (dlBar != null) {
                            if (pct >= 0) {
                                dlBar.setIndeterminate(false);
                                dlBar.setProgress(pct);
                            } else {
                                dlBar.setIndeterminate(true);
                            }
                        }
                        NotifyHelper.showProgress(MainActivity.this, NotifyHelper.ID_UPDATE_PROGRESS,
                                "正在下载 火花守护 " + info.versionName, txt,
                                pct < 0 ? 0 : pct, pct < 0);
                    }
                });
            }

            public void onDone(final File apk) {
                ui.post(new Runnable() {
                    public void run() {
                        dismissDownloadDlg();
                        AppConfig.setPendingCode(MainActivity.this, info.versionCode);
                        NotifyHelper.cancel(MainActivity.this, NotifyHelper.ID_UPDATE_PROGRESS);
                        NotifyHelper.show(MainActivity.this, NotifyHelper.ID_UPDATE,
                                "火花守护 " + info.versionName + " 已下载完成",
                                "点此安装新版本（系统会再确认一次）");
                        promptInstall(apk);
                    }
                });
            }

            public void onError(final String msg) {
                ui.post(new Runnable() {
                    public void run() {
                        dismissDownloadDlg();
                        NotifyHelper.cancel(MainActivity.this, NotifyHelper.ID_UPDATE_PROGRESS);
                        toast("下载失败：" + msg);
                    }
                });
            }
        });
    }

    private void dismissDownloadDlg() {
        if (dlDlg != null) {
            try {
                dlDlg.dismiss();
            } catch (Throwable ignore) {
            }
            dlDlg = null;
        }
        dlTxt = null;
        dlBar = null;
    }

    private String mb(long bytes) {
        if (bytes >= 1048576L) {
            return String.format(java.util.Locale.US, "%.2f MB", bytes / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
    }

    /* ================================================================== */

    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 暴露给网页的接口：window.AndroidApp */
    private class Bridge {

        @JavascriptInterface
        public void setToken(String t) {
            AppConfig.setToken(MainActivity.this, t);
        }

        @JavascriptInterface
        public String getVersion() {
            return AppConfig.VERSION_NAME + " (" + AppConfig.VERSION_CODE + ")";
        }

        @JavascriptInterface
        public String getConfig() {
            JSONObject o = new JSONObject();
            try {
                o.put("base", AppConfig.base(MainActivity.this));
                o.put("notifyOn", AppConfig.notifyOn(MainActivity.this));
                o.put("notifyDaily", AppConfig.notifyDaily(MainActivity.this));
                o.put("notifyTask", AppConfig.notifyTask(MainActivity.this));
                o.put("quietStart", AppConfig.quietStart(MainActivity.this));
                o.put("quietEnd", AppConfig.quietEnd(MainActivity.this));
                o.put("autoUpdate", AppConfig.autoUpdate(MainActivity.this));
                o.put("version", AppConfig.VERSION_NAME);
                o.put("notifyGranted", NotifyHelper.canNotify(MainActivity.this));
            } catch (Throwable ignore) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void setConfig(String json) {
            try {
                JSONObject o = new JSONObject(json);
                if (o.has("base")) {
                    AppConfig.setBase(MainActivity.this, o.optString("base"));
                }
                if (o.has("notifyOn")) {
                    AppConfig.setNotifyOn(MainActivity.this, o.optBoolean("notifyOn"));
                }
                if (o.has("notifyDaily")) {
                    AppConfig.setNotifyDaily(MainActivity.this, o.optBoolean("notifyDaily"));
                }
                if (o.has("notifyTask")) {
                    AppConfig.setNotifyTask(MainActivity.this, o.optBoolean("notifyTask"));
                }
                if (o.has("quietStart") && o.has("quietEnd")) {
                    AppConfig.setQuiet(MainActivity.this,
                            o.optInt("quietStart", AppConfig.DEFAULT_QUIET_START),
                            o.optInt("quietEnd", AppConfig.DEFAULT_QUIET_END));
                }
                if (o.has("autoUpdate")) {
                    AppConfig.setAutoUpdate(MainActivity.this, o.optBoolean("autoUpdate"));
                }
                if (o.has("notifyOn")) {
                    if (o.optBoolean("notifyOn")) {
                        UpdateJobService.rescheduleAll(MainActivity.this);
                    } else {
                        NotifyJobService.cancel(MainActivity.this);
                        NotifyJobService.cancelAlarm(MainActivity.this);   // [1.0.2] 连同兜底闹钟一起停
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "setConfig: " + t);
            }
        }

        @JavascriptInterface
        public void openSettings() {
            ui.post(new Runnable() {
                public void run() {
                    showSettings();
                }
            });
        }

        @JavascriptInterface
        public void checkUpdate() {
            ui.post(new Runnable() {
                public void run() {
                    doManualCheck();
                }
            });
        }

        @JavascriptInterface
        public void refreshNotify() {
            new Thread(new Runnable() {
                public void run() {
                    NotifyJobService.poll(MainActivity.this);
                }
            }, "spark-manual-poll").start();
            toast("已拉取一次最新通知");
        }

        @JavascriptInterface
        public void toast(String s) {
            MainActivity.this.toast(s);
        }

        @JavascriptInterface
        public int getVersionCode() {
            return AppConfig.VERSION_CODE;
        }
    }

    private void doManualCheck() {
        toast("正在检查更新…");
        UpdateManager.check(this, new UpdateManager.CheckCb() {
            public void onResult(final UpdateManager.Info info, final String error) {
                ui.post(new Runnable() {
                    public void run() {
                        if (info == null) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("检查更新失败")
                                    .setMessage("当前版本已是最新，或网络不可达。\n\n" + (error == null ? "" : error))
                                    .setPositiveButton("知道了", null).show();
                            return;
                        }
                        if (info.versionCode > AppConfig.VERSION_CODE) {
                            AppConfig.setUpdatePromptAt(MainActivity.this, 0);   // 手动检查 → 无视节流，必定弹
                            maybePromptUpdate(info);
                        } else {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("已是最新版本")
                                    .setMessage("当前版本 " + AppConfig.VERSION_NAME)
                                    .setPositiveButton("知道了", null).show();
                        }
                    }
                });
            }
        });
    }

    /* ---------- 原生设置面板 ---------- */

    private void showSettings() {
        ScrollView sc = new ScrollView(this);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        ll.setPadding(pad, dp(8), pad, dp(8));
        sc.addView(ll);

        final TextView verTv = new TextView(this);
        verTv.setText("火花守护 v" + AppConfig.VERSION_NAME + "（" + AppConfig.VERSION_CODE + "）");
        verTv.setTextColor(Color.parseColor("#8B95A7"));
        verTv.setTextSize(12);
        ll.addView(verTv);

        final EditText baseEt = new EditText(this);
        baseEt.setHint("面板地址，例如 http://121.36.38.180:16080");
        baseEt.setText(AppConfig.base(this));
        baseEt.setTextColor(Color.parseColor("#E8ECF4"));
        baseEt.setHintTextColor(Color.parseColor("#4B5568"));
        ll.addView(label("面板地址"));
        ll.addView(baseEt);

        final CheckBox chOn = new CheckBox(this);
        chOn.setText("开启通知（日报 / 任务）");
        chOn.setChecked(AppConfig.notifyOn(this));
        ll.addView(chOn);

        final CheckBox chDaily = new CheckBox(this);
        chDaily.setText("推日报（早报 / 晚报）");
        chDaily.setChecked(AppConfig.notifyDaily(this));
        ll.addView(chDaily);

        final CheckBox chTask = new CheckBox(this);
        chTask.setText("推定时任务结果");
        chTask.setChecked(AppConfig.notifyTask(this));
        ll.addView(chTask);

        ll.addView(label("任务提醒免打扰时段（此区间内不推任务通知）"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final Spinner spS = hourSpinner(AppConfig.quietStart(this));
        final Spinner spE = hourSpinner(AppConfig.quietEnd(this));
        row.addView(spS);
        TextView t = new TextView(this);
        t.setText(" 点  →  ");
        t.setTextColor(Color.parseColor("#8B95A7"));
        row.addView(t);
        row.addView(spE);
        TextView t2 = new TextView(this);
        t2.setText(" 点");
        t2.setTextColor(Color.parseColor("#8B95A7"));
        row.addView(t2);
        ll.addView(row);

        final CheckBox chAuto = new CheckBox(this);
        chAuto.setText("自动下载新版本（下载完再点一下系统弹窗安装）");
        chAuto.setChecked(AppConfig.autoUpdate(this));
        ll.addView(chAuto);

        final CheckBox chGrant = new CheckBox(this);
        chGrant.setText("已授予「安装未知应用」权限");
        chGrant.setChecked(UpdateManager.canInstall(this));
        chGrant.setEnabled(false);
        ll.addView(chGrant);

        /* [1.0.2] 后台常驻：不加白名单的话，息屏后系统会冻结后台轮询 → 只有打开 App 才收到通知 */
        final Button bBg = new Button(this);
        bBg.setText(ignoringBatteryOpt()
                ? "后台运行：已允许（点此复核）"
                : "允许后台运行（推荐，否则可能收不到通知）");
        bBg.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                requestIgnoreBatteryOpt();
            }
        });
        ll.addView(bBg);

        new AlertDialog.Builder(this)
                .setTitle("客户端设置")
                .setView(sc)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        AppConfig.setBase(MainActivity.this, baseEt.getText().toString());
                        AppConfig.setNotifyOn(MainActivity.this, chOn.isChecked());
                        AppConfig.setNotifyDaily(MainActivity.this, chDaily.isChecked());
                        AppConfig.setNotifyTask(MainActivity.this, chTask.isChecked());
                        AppConfig.setQuiet(MainActivity.this,
                                spS.getSelectedItemPosition(), spE.getSelectedItemPosition());
                        AppConfig.setAutoUpdate(MainActivity.this, chAuto.isChecked());
                        if (chOn.isChecked()) {
                            UpdateJobService.rescheduleAll(MainActivity.this);
                            new Thread(new Runnable() {
                                public void run() {
                                    NotifyJobService.poll(MainActivity.this);
                                }
                            }, "spark-poll-after-save").start();
                        } else {
                            NotifyJobService.cancel(MainActivity.this);
                            NotifyJobService.cancelAlarm(MainActivity.this);   // [1.0.2] 连同兜底闹钟一起停
                        }
                        String now = AppConfig.base(MainActivity.this) + AppConfig.PAGE_PATH;
                        if (!now.equals(web.getUrl())) {
                            web.loadUrl(now);
                        }
                        toast("已保存");
                    }
                })
                .setNeutralButton("检查更新", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        doManualCheck();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ==================================================================
       [1.0.2] 后台存活：电池优化白名单
       ROM 的深度省电会在息屏后冻结后台任务，JobScheduler / AlarmManager 都可能被压制，
       用户看到的就是「App 只有打开后才会通知」。把本应用加入白名单是最有效的一步。
       ================================================================== */

    private boolean ignoringBatteryOpt() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestIgnoreBatteryOpt() {
        if (ignoringBatteryOpt()) {
            toast("已经允许后台运行");
            return;
        }
        try {
            Intent it = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            it.setData(Uri.parse("package:" + getPackageName()));
            startActivity(it);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请到 系统设置 → 应用 → 火花守护 → 电池，允许后台运行");
            }
        }
    }

    /** 首次启动提示一次（选「以后再说」就不再打扰）。 */
    private void maybeAskBattery() {
        if (ignoringBatteryOpt() || AppConfig.batteryAsked(this)) {
            return;
        }
        AppConfig.setBatteryAsked(this, true);
        ui.postDelayed(new Runnable() {
            public void run() {
                if (isFinishing()) {
                    return;
                }
                try {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("让通知更可靠")
                            .setMessage("系统会限制后台运行，可能出现「只有打开 App 才收到通知」。\n\n"
                                    + "建议允许「火花守护」在后台常驻运行（加入电池优化白名单）。")
                            .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface d, int w) {
                                    requestIgnoreBatteryOpt();
                                }
                            })
                            .setNegativeButton("以后再说", null)
                            .show();
                } catch (Throwable ignore) {
                }
            }
        }, 2500);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.parseColor("#8B95A7"));
        t.setTextSize(12);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private Spinner hourSpinner(int sel) {
        Spinner sp = new Spinner(this);
        String[] items = new String[24];
        for (int i = 0; i < 24; i++) {
            items[i] = (i < 10 ? "0" : "") + i + ":00";
        }
        ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(23, sel)));
        return sp;
    }

    /* ================================================================== */

    /** 带下拉刷新的 WebView：只看手势，不拦截事件，尽量不影响网页自身滚动。 */
    public static class PullWebView extends WebView {

        public interface Listener {
            void onPull(float progress);

            void onRelease(float progress);
        }

        private final Listener listener;
        private float startY;
        private boolean tracking;
        private float progress;
        private final int threshold;

        public PullWebView(Context c, Listener l) {
            super(c);
            this.listener = l;
            this.threshold = (int) (72 * c.getResources().getDisplayMetrics().density);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startY = e.getY();
                    tracking = !canScrollVertically(-1);
                    progress = 0f;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tracking) {
                        float dy = e.getY() - startY;
                        if (dy > 0 && !canScrollVertically(-1)) {
                            progress = Math.min(1.4f, dy / threshold);
                            if (listener != null) {
                                listener.onPull(Math.min(1f, progress));
                            }
                        } else if (dy < -8) {
                            tracking = false;
                            if (listener != null) {
                                listener.onRelease(0f);
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (tracking && progress > 0f) {
                        if (listener != null) {
                            listener.onRelease(Math.min(1f, progress));
                        }
                    }
                    tracking = false;
                    progress = 0f;
                    break;
                default:
                    break;
            }
            return super.onTouchEvent(e);
        }
    }
}
