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
                if (info != null && info.versionCode > AppConfig.VERSION_CODE) {
                    maybePromptUpdate(info);
                }
            }
        });
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
    protected void onPause() {
        tokenLoopRunning = false;
        ui.removeCallbacks(tokenLoop);
        super.onPause();
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

    /** 有下载完待安装的包 → 提示安装。 */
    private void checkPendingApk() {
        int code = AppConfig.pendingCode(this);
        if (code <= AppConfig.VERSION_CODE) {
            return;
        }
        File apk = new File(UpdateManager.updateDir(this), AppConfig.APK_TMP_NAME);
        if (!apk.exists()) {
            AppConfig.setPendingCode(this, 0);
            return;
        }
        if (AppConfig.promptedCode(this) == code) {
            return;
        }
        AppConfig.setPromptedCode(this, code);
        promptInstall(apk);
    }

    private void promptInstall(final File apk) {
        new AlertDialog.Builder(this)
                .setTitle("安装新版本")
                .setMessage("新版本已下载完成，点「安装」继续（系统会再确认一次）。")
                .setPositiveButton("安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (!UpdateManager.install(MainActivity.this, apk)) {
                            toast("请先允许「安装未知应用」，再回到通知里点一次安装");
                        }
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    private void maybePromptUpdate(final UpdateManager.Info info) {
        if (AppConfig.promptedCode(this) == info.versionCode) {
            return;
        }
        AppConfig.setPromptedCode(this, info.versionCode);
        String note = info.notes == null || info.notes.length() == 0 ? "" : "\n\n" + info.notes;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage("当前 " + AppConfig.VERSION_NAME + " → 最新 " + info.versionName + note)
                .setCancelable(!info.force);
        if (AppConfig.autoUpdate(this)) {
            b.setPositiveButton("下载更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    startDownload(info);
                }
            });
            b.setNegativeButton("稍后", null);
        } else {
            b.setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    startDownload(info);
                }
            });
            b.setNegativeButton("稍后", null);
        }
        try {
            b.show();
        } catch (Throwable ignore) {
        }
    }

    private void startDownload(final UpdateManager.Info info) {
        toast("开始下载新版本…");
        UpdateManager.download(this, info, new UpdateManager.DlCb() {
            public void onProgress(int percent) {
            }

            public void onDone(File apk) {
                AppConfig.setPendingCode(MainActivity.this, info.versionCode);
                promptInstall(apk);
            }

            public void onError(String msg) {
                toast("下载失败：" + msg);
            }
        });
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
                            AppConfig.setPromptedCode(MainActivity.this, 0);
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
