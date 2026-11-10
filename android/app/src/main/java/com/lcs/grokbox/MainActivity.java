package com.lcs.grokbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GrokBox —— 单 Activity WebView 壳。
 *
 * 三种连接方式全部由前端 index.html 决定，这里只提供两类能力：
 *   1) 原生 HTTP 通道（AndroidBridge.chat），无 CORS 限制，可连本地 http 服务；
 *   2) 内置浏览器（AndroidBridge.openWeb），用于直接打开 grok.com / x.com 网页版。
 *
 * 不依赖 AndroidX / GMS / Firebase，只用系统 WebView + HttpURLConnection，
 * 因此没有 Google 账号校验，缺少 GMS 的机器也能正常运行。
 */
public class MainActivity extends Activity {

    private static final String HOME = "file:///android_asset/index.html";

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile HttpURLConnection conn;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private String defaultUA = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            getWindow().setStatusBarColor(Color.parseColor("#0B0B0F"));
            getWindow().setNavigationBarColor(Color.parseColor("#0B0B0F"));
        } catch (Throwable ignored) { }

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadsImagesAutomatically(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        try {
            // 允许 file:// 页面里的 http 请求走到本地服务
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Throwable ignored) { }

        defaultUA = s.getUserAgentString();

        // 网页版需要保留登录态
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                cm.setAcceptThirdPartyCookies(web, true);
            }
        } catch (Throwable ignored) { }

        web.setBackgroundColor(Color.parseColor("#0B0B0F"));
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (url != null && url.startsWith("http")) {
                    // 减轻 WebView 特征，降低被判定为「不支持的浏览器」的概率
                    view.evaluateJavascript(
                            "(function(){try{" +
                            "Object.defineProperty(navigator,'webdriver',{get:function(){return undefined}});" +
                            "if(!window.chrome){window.chrome={runtime:{}};}" +
                            "}catch(e){}})();", null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl(HOME);
    }

    private void js(final String code) {
        ui.post(new Runnable() {
            @Override public void run() {
                try { web.evaluateJavascript(code, null); } catch (Throwable ignored) { }
            }
        });
    }

    /** 调用前端全局函数；arg 为 null 时表示无参 */
    private void call(String fn, String arg) {
        js("window." + fn + " && window." + fn + "("
                + (arg == null ? "" : JSONObject.quote(arg)) + ");");
    }

    public class Bridge {

        @JavascriptInterface
        public boolean available() { return true; }

        @JavascriptInterface
        public void stop() {
            cancelled.set(true);
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
        }

        @JavascriptInterface
        public void chat(final String reqJson) {
            new Thread(new Runnable() {
                @Override public void run() { doChat(reqJson); }
            }, "grokbox-net").start();
        }

        /** 网页模式：在内置浏览器里打开官方页面 */
        @JavascriptInterface
        public void openWeb(final String url) {
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        WebSettings s = web.getSettings();
                        String ua = defaultUA == null ? "" : defaultUA;
                        // 去掉 "wv" 标记，否则 x.com 会拒绝在 WebView 里登录
                        ua = ua.replace("; wv", "").replace(" wv", "");
                        s.setUserAgentString(ua);
                        web.loadUrl(url);
                    } catch (Throwable ignored) { }
                }
            });
        }

        /** 从网页回到主界面 */
        @JavascriptInterface
        public void backToApp() {
            ui.post(new Runnable() {
                @Override public void run() {
                    try { web.loadUrl(HOME); } catch (Throwable ignored) { }
                }
            });
        }

        /** 清除网页登录态与缓存 */
        @JavascriptInterface
        public void clearWebData() {
            ui.post(new Runnable() {
                @Override public void run() {
                    try {
                        CookieManager cm = CookieManager.getInstance();
                        cm.removeAllCookies(null);
                        cm.flush();
                        WebStorage.getInstance().deleteAllData();
                        web.clearCache(true);
                        web.clearFormData();
                        web.clearHistory();
                        android.webkit.WebViewDatabase db = android.webkit.WebViewDatabase.getInstance(MainActivity.this);
                        db.clearHttpAuthUsernamePassword();
                    } catch (Throwable ignored) { }
                }
            });
        }
    }

    private void doChat(String reqJson) {
        cancelled.set(false);
        HttpURLConnection c = null;
        try {
            JSONObject req = new JSONObject(reqJson);

            String base = req.optString("base", "https://api.x.ai/v1").trim();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

            String key = req.optString("key", "").trim();
            String model = req.optString("model", "local-model").trim();
            String sys = req.optString("sys", "");
            boolean stream = req.optBoolean("stream", true);
            double temp = req.optDouble("temp", 0.8);

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("stream", stream);
            body.put("temperature", temp);

            JSONArray msgs = new JSONArray();
            if (sys != null && sys.trim().length() > 0) {
                msgs.put(new JSONObject().put("role", "system").put("content", sys));
            }
            JSONArray hist = req.optJSONArray("messages");
            if (hist != null) {
                for (int i = 0; i < hist.length(); i++) {
                    JSONObject m = hist.optJSONObject(i);
                    if (m == null) continue;
                    String role = m.optString("role", "user");
                    String content = m.optString("content", "");
                    if (content.length() == 0) continue;
                    msgs.put(new JSONObject().put("role", role).put("content", content));
                }
            }
            body.put("messages", msgs);

            URL url = new URL(base + "/chat/completions");
            c = (HttpURLConnection) url.openConnection();
            conn = c;
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(300000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // 本地服务通常不校验 key，所以空 key 就不发 Authorization
            if (key.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + key);
            }
            c.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
            c.setRequestProperty("User-Agent", "GrokBox/1.1 (Android)");

            byte[] payload = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(c.getErrorStream());
                if (err.length() > 600) err = err.substring(0, 600);
                call("__gbError", "HTTP " + code + " " + err);
                return;
            }

            if (!stream) {
                String all = readAll(c.getInputStream());
                String content = "";
                try {
                    JSONObject j = new JSONObject(all);
                    content = j.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "");
                } catch (Throwable ignored) { }
                if (content.length() == 0) {
                    call("__gbError", "空响应：检查模型名 / 额度 / 接口地址");
                    return;
                }
                call("__gbDelta", content);
                call("__gbDone", null);
                return;
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"), 8192);
            String line;
            while ((line = br.readLine()) != null) {
                if (cancelled.get()) break;
                line = line.trim();
                if (line.length() == 0 || !line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) break;
                try {
                    JSONObject j = new JSONObject(data);
                    JSONArray ch = j.optJSONArray("choices");
                    if (ch == null || ch.length() == 0) continue;
                    JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;
                    String piece = delta.optString("content", "");
                    if (piece.length() > 0) call("__gbDelta", piece);
                } catch (Throwable ignored) {
                    // 心跳 / 注释 / 不完整行
                }
            }
            br.close();
            call("__gbDone", null);

        } catch (Throwable t) {
            if (!cancelled.get()) {
                String m = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
                call("__gbError", m);
            }
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) { }
            conn = null;
        }
    }

    private String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void onBackPressed() {
        try {
            String u = web == null ? null : web.getUrl();
            boolean atHome = (u == null) || u.startsWith("file:///android_asset/index.html");
            if (!atHome) {
                if (web.canGoBack()) { web.goBack(); return; }
                web.loadUrl(HOME);
                return;
            }
        } catch (Throwable ignored) { }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try {
            if (conn != null) conn.disconnect();
        } catch (Throwable ignored) { }
        if (web != null) {
            try {
                web.removeJavascriptInterface("AndroidBridge");
                web.destroy();
            } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }
}
