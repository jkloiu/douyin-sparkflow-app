# 火花守护 · 安卓客户端

抖音火花守护面板的安卓壳：纯 Java、零第三方库、零 AndroidX，
不用 Gradle / Android Studio，一条 `python tools/build.py` 直接出签名 APK。
界面 = WebView 加载网页面板前端（见 `douyin-spark-panel-web` 仓库，不打包前端）。

## 构成

| 模块 | 说明 |
| --- | --- |
| `app/src/com/sparkflow/guard/MainActivity.java` | WebView 主界面 + JS 桥 `window.AndroidApp` + 下拉刷新 |
| `app/src/com/sparkflow/guard/NotifyJobService.java` | 15 分钟轮询 `/api/notifications`：日报随时推、任务仅 08:00–22:00 |
| `app/src/com/sparkflow/guard/UpdateJobService.java` | 6 小时检查自更新 |
| `app/src/com/sparkflow/guard/UpdateManager.java` | 发现新版本 → 自动下载 → 自动拉起安装器 |
| `app/src/com/sparkflow/guard/SimpleFileProvider.java` | FileProvider（Android 7+ 安装授权） |
| `app/src/com/sparkflow/guard/BootReceiver.java` | 开机自启轮询 |
| `app/src/com/sparkflow/guard/AppConfig.java` | 面板地址等配置 |
| `tools/build.py` | 构建链：aapt2 compile → link → javac --release 8 → d8 → zipalign → apksigner（v1+v2） |

## 构建

前置：本机工具链 `F:/WorkBuddy/android-toolchain/`（jdk17 + build-tools/android-14
+ platform33/android-13），可用环境变量 `ANDROID_TOOLCHAIN` 改路径。

```bash
# 1) 恢复签名密钥（仓库不含）：keystore/sparkflow.jks，alias=sparkflow
# 2) 配置签名口令（仓库不含明文）：
set SPARKFLOW_KS_PASS=<store 口令>
set SPARKFLOW_KEY_PASS=<key 口令>
# 3) 构建（可用 --base 覆盖面板地址）
python tools/build.py
```

产物：`dist/sparkflow-<version>.apk`（同时刷新 `dist/app-version.json`
供 App 自更新检查，部署到面板 `static/` 目录）。

**注意：换版必须用同一个 keystore 签名**，否则覆盖安装失败。

## 登录态

App 每 20s 把网页 `localStorage.panel_token` 同步进 SharedPreferences，
轮询接口直接带上，不依赖 WebView 存活。
