package com.sparkflow.guard;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** 极简 HTTP 工具：够用就好，不引第三方库。 */
public final class Http {

    private static final int CONNECT_MS = 8000;
    private static final int READ_MS = 12000;

    private Http() {
    }

    /**
     * GET 文本。带上 X-Panel-Token（面板后端 @require_token 认这个头）。
     * 面板正式站是自签证书 → https 时放宽校验（仅用于本应用自己的后台）。
     */
    public static String get(String url, String token) throws IOException {
        return request(url, token, null);
    }

    /** 下载到文件，返回字节数；cb 可为 null。 */
    public static long download(String url, File dest, Progress cb) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        long total = conn.getContentLength();
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[16384];
            long got = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                got += n;
                if (cb != null) {
                    cb.onProgress(got, total);
                }
            }
            out.flush();
            return got;
        } finally {
            try {
                out.close();
            } catch (Exception ignore) {
            }
            try {
                in.close();
            } catch (Exception ignore) {
            }
            conn.disconnect();
        }
    }

    private static String request(String url, String token, String body) throws IOException {
        HttpURLConnection conn = open(url);
        conn.setRequestMethod(body == null ? "GET" : "POST");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setRequestProperty("Accept", "application/json");
        if (token != null && token.length() > 0) {
            conn.setRequestProperty("X-Panel-Token", token);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.getOutputStream().write(body.getBytes("UTF-8"));
        }
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String text = in == null ? "" : readAll(in);
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + " " + text);
        }
        return text;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setUseCaches(false);
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection h = (HttpsURLConnection) conn;
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{TRUST_ALL}, new java.security.SecureRandom());
                h.setSSLSocketFactory(sc.getSocketFactory());
                h.setHostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            } catch (Exception ignore) {
            }
        }
        return conn;
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            try {
                in.close();
            } catch (Exception ignore) {
            }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    public interface Progress {
        void onProgress(long got, long total);
    }

    /** 网络是否可用。 */
    public static boolean online(Context c) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }
}
