# -*- coding: utf-8 -*-
"""「火花守护」APK 构建脚本（不依赖 Gradle / Android Studio）。

流程：
  aapt2 compile  → aapt2 link（产出资源 APK + R.java）
  javac          → d8（dex）
  合并 classes.dex → zipalign → apksigner（v1+v2）
最后产出 dist/sparkflow-<ver>.apk 与 dist/app-version.json（供 App 自更新拉取）。

用法：
  python tools/build.py            # 用 app/AndroidManifest.xml 里的版本号
  python tools/build.py --base http://x.x.x.x:16080
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, 'app')
BUILD = os.path.join(ROOT, 'build')
DIST = os.path.join(ROOT, 'dist')
TOOLS = os.path.join(ROOT, 'tools')
KEYS = os.path.join(ROOT, 'keystore')

TOOLCHAIN = os.environ.get('ANDROID_TOOLCHAIN', r'F:/WorkBuddy/android-toolchain')
JDK = os.path.join(TOOLCHAIN, 'jdk17', 'jdk-17.0.20.1+1')
BT = os.path.join(TOOLCHAIN, 'build-tools', 'android-14')
ANDROID_JAR = os.path.join(TOOLCHAIN, 'platform33', 'android-13', 'android.jar')

KEYSTORE = os.path.join(KEYS, 'sparkflow.jks')
# 签名口令从环境变量读，不落库（keystore 本体也在 .gitignore 里，换机器自行补）。
# 本地构建：set SPARKFLOW_KS_PASS=xxx && set SPARKFLOW_KEY_PASS=xxx && python tools/build.py
KS_PASS = os.environ.get('SPARKFLOW_KS_PASS', '')
KEY_ALIAS = 'sparkflow'
KEY_PASS = os.environ.get('SPARKFLOW_KEY_PASS', '')

DEFAULT_BASE = 'http://121.36.38.180:16080'
PAGE = '/static/console4.html'


def run(cmd, **kw):
    p = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', **kw)
    if p.returncode != 0:
        print('CMD FAILED:', ' '.join(str(c) for c in cmd))
        print('--- stdout ---\n' + (p.stdout or ''))
        print('--- stderr ---\n' + (p.stderr or ''))
        raise SystemExit(1)
    return p


def java(*args):
    return [os.path.join(JDK, 'bin', 'java.exe')] + list(args)


def javac(*args):
    return [os.path.join(JDK, 'bin', 'javac.exe')] + list(args)


def check_keystore():
    """签名前校验：keystore 与口令都必须在本机（仓库里只有代码，不含密钥）。"""
    if not os.path.isfile(KEYSTORE):
        raise SystemExit('缺少 keystore/sparkflow.jks（仓库不含签名密钥，请从备份恢复）')
    if not KS_PASS or not KEY_PASS:
        raise SystemExit('缺少签名口令：请设置环境变量 SPARKFLOW_KS_PASS / SPARKFLOW_KEY_PASS')


def read_manifest_version():
    txt = open(os.path.join(APP, 'AndroidManifest.xml'), encoding='utf-8').read()
    vc = re.search(r'android:versionCode="(\d+)"', txt)
    vn = re.search(r'android:versionName="([^"]+)"', txt)
    return int(vc.group(1)), vn.group(1)


def read_java_version():
    txt = open(os.path.join(APP, 'src', 'com', 'sparkflow', 'guard', 'AppConfig.java'),
               encoding='utf-8').read()
    vc = re.search(r'VERSION_CODE\s*=\s*(\d+)', txt)
    vn = re.search(r'VERSION_NAME\s*=\s*"([^"]+)"', txt)
    return int(vc.group(1)), vn.group(1)


def ensure_keystore():
    check_keystore()
    os.makedirs(KEYS, exist_ok=True)
    if os.path.exists(KEYSTORE):
        return
    print('>> 生成签名密钥库', KEYSTORE)
    run([os.path.join(JDK, 'bin', 'keytool.exe'), '-genkeypair',
         '-keystore', KEYSTORE, '-alias', KEY_ALIAS,
         '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10950',
         '-storepass', KS_PASS, '-keypass', KEY_PASS,
         '-dname', 'CN=SparkFlow Guard, OU=Panel, O=SparkFlow, L=Beijing, ST=Beijing, C=CN'])


def main():
    base = DEFAULT_BASE
    if '--base' in sys.argv:
        base = sys.argv[sys.argv.index('--base') + 1]

    for p in (JDK, BT, ANDROID_JAR):
        if not os.path.exists(p):
            raise SystemExit('缺少工具链: ' + p + '（可用环境变量 ANDROID_TOOLCHAIN 指定）')

    mc, mn = read_manifest_version()
    jc, jn = read_java_version()
    if (mc, mn) != (jc, jn):
        raise SystemExit('版本号不一致：manifest=%s/%s  AppConfig=%s/%s' % (mc, mn, jc, jn))
    print('>> 版本 %s (%d)' % (mn, mc))

    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    for d in (BUILD, os.path.join(BUILD, 'gen'), os.path.join(BUILD, 'classes'),
              os.path.join(BUILD, 'dex'), DIST):
        os.makedirs(d, exist_ok=True)

    aapt2 = os.path.join(BT, 'aapt2.exe')
    res_zip = os.path.join(BUILD, 'res.zip')
    print('>> aapt2 compile')
    run([aapt2, 'compile', '--dir', os.path.join(APP, 'res'), '-o', res_zip])

    unaligned = os.path.join(BUILD, 'app-unsigned.apk')
    gen = os.path.join(BUILD, 'gen')
    print('>> aapt2 link')
    run([aapt2, 'link', '-o', unaligned,
         '-I', ANDROID_JAR,
         '--manifest', os.path.join(APP, 'AndroidManifest.xml'),
         '--java', gen,
         '--min-sdk-version', '21',
         '--target-sdk-version', '33',
         '--no-version-vectors',
         res_zip])

    srcs = []
    for root, _dirs, files in os.walk(os.path.join(APP, 'src')):
        srcs += [os.path.join(root, f) for f in files if f.endswith('.java')]
    for root, _dirs, files in os.walk(gen):
        srcs += [os.path.join(root, f) for f in files if f.endswith('.java')]
    print('>> javac (%d 个源文件)' % len(srcs))
    run(javac('--release', '8', '-encoding', 'UTF-8', '-nowarn',
              '-classpath', ANDROID_JAR,
              '-d', os.path.join(BUILD, 'classes')) + srcs)

    classes = []
    for root, _dirs, files in os.walk(os.path.join(BUILD, 'classes')):
        classes += [os.path.join(root, f) for f in files if f.endswith('.class')]
    print('>> d8 (%d 个 class)' % len(classes))
    run(java('-cp', os.path.join(BT, 'lib', 'd8.jar'), 'com.android.tools.r8.D8',
             '--min-api', '21',
             '--lib', ANDROID_JAR,
             '--output', os.path.join(BUILD, 'dex')) + classes)

    dex = os.path.join(BUILD, 'dex', 'classes.dex')
    if not os.path.exists(dex):
        raise SystemExit('d8 没有产出 classes.dex')

    merged = os.path.join(BUILD, 'app-withdex.apk')
    print('>> 合并 classes.dex')
    with zipfile.ZipFile(unaligned, 'r') as zin:
        with zipfile.ZipFile(merged, 'w', zipfile.ZIP_DEFLATED) as zout:
            zout.writestr('classes.dex', open(dex, 'rb').read())
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))

    aligned = os.path.join(BUILD, 'app-aligned.apk')
    print('>> zipalign')
    run([os.path.join(BT, 'zipalign.exe'), '-f', '-p', '4', merged, aligned])

    ensure_keystore()
    apk_name = 'sparkflow-%s.apk' % mn
    out_apk = os.path.join(DIST, apk_name)
    print('>> apksigner')
    run(java('-jar', os.path.join(BT, 'lib', 'apksigner.jar'), 'sign',
             '--ks', KEYSTORE,
             '--ks-pass', 'pass:' + KS_PASS,
             '--key-pass', 'pass:' + KEY_PASS,
             '--ks-key-alias', KEY_ALIAS,
             '--min-sdk-version', '21',
             '--v1-signing-enabled', 'true',
             '--v2-signing-enabled', 'true',
             '--out', out_apk, aligned))

    print('>> 校验签名')
    run(java('-jar', os.path.join(BT, 'lib', 'apksigner.jar'), 'verify', '--print-certs', out_apk))

    data = open(out_apk, 'rb').read()
    md5 = hashlib.md5(data).hexdigest()
    info = {
        'versionCode': mc,
        'versionName': mn,
        'url': base.rstrip('/') + '/static/' + apk_name,
        'size': len(data),
        'md5': md5,
        'notes': '火花守护 ' + mn,
        'force': False,
        'page': base.rstrip('/') + PAGE,
    }
    with open(os.path.join(DIST, 'app-version.json'), 'w', encoding='utf-8') as f:
        json.dump(info, f, ensure_ascii=False, indent=2)

    print('')
    print('=' * 62)
    print('APK   : %s' % out_apk)
    print('大小  : %.2f MB' % (len(data) / 1048576.0))
    print('md5   : %s' % md5)
    print('清单  : %s' % os.path.join(DIST, 'app-version.json'))
    print('地址  : %s' % info['url'])
    print('=' * 62)


if __name__ == '__main__':
    main()
