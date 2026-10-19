package com.fishbowl.app.services;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, zero-storage native in-app Cloudflare solver for Fishbowl.
 * Emulates the FlareSolverr v1 protocol on http://127.0.0.1:8191.
 * Uses Android's native Chromium WebView to pass Cloudflare Turnstile challenges
 * with genuine device fingerprints, authentic V8, and real cookies.
 * Does NOT display any icon on the Home page dock.
 */
public final class NativeFlareSolver {
    private static final String TAG = "NativeFlareSolver";
    private static final int PORT = 8191;
    private static final String VERSION = "v3.3.21";
    private static final String DEFAULT_UA = "Mozilla/5.0 (Linux; Android 14; Mobile; rv:130.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";

    private static volatile NativeFlareSolver instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;

    private WebView activeWebView;
    private FrameLayout containerLayout;
    private final Object solverLock = new Object();

    public static NativeFlareSolver getInstance() {
        if (instance == null) {
            synchronized (NativeFlareSolver.class) {
                if (instance == null) {
                    instance = new NativeFlareSolver();
                }
            }
        }
        return instance;
    }

    private NativeFlareSolver() {}

    /**
     * Initializes the native solver with the hosting activity.
     * Attaches an invisible 2dp x 2dp WebView to the window hierarchy
     * so that Android's Chromium compositor & V8 runtime execute full challenges.
     */
    public synchronized void init(Activity activity) {
        startServer();
        mainHandler.post(() -> {
            try {
                if (activeWebView != null && containerLayout != null && containerLayout.getParent() != null) {
                    return;
                }

                if (containerLayout != null && containerLayout.getParent() instanceof ViewGroup) {
                    ((ViewGroup) containerLayout.getParent()).removeView(containerLayout);
                    containerLayout = null;
                }

                ViewGroup rootView = activity.findViewById(android.R.id.content);
                if (rootView == null) return;

                containerLayout = new FrameLayout(activity);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(2, 2);
                containerLayout.setLayoutParams(lp);
                containerLayout.setAlpha(0.01f); // Rendered but practically invisible

                activeWebView = createConfiguredWebView(activity);
                containerLayout.addView(activeWebView);
                rootView.addView(containerLayout);

                Log.i(TAG, "[NativeFlareSolver] Initialized and attached to window hierarchy on port " + PORT);
            } catch (Throwable t) {
                Log.e(TAG, "[NativeFlareSolver] Initialization failed", t);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createConfiguredWebView(Context context) {
        WebView wv = new WebView(context);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(DEFAULT_UA);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        wv.setWebChromeClient(new WebChromeClient());
        return wv;
    }

    private synchronized void startServer() {
        if (isRunning && serverSocket != null && !serverSocket.isClosed()) return;
        isRunning = true;
        serverExecutor.execute(() -> {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception ignored) {}
                }
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50);
                Log.i(TAG, "[NativeFlareSolver] Server listening on 127.0.0.1:" + PORT);

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        serverExecutor.execute(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[NativeFlareSolver] Server socket error", e);
            }
        });
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        mainHandler.post(() -> {
            try {
                if (activeWebView != null) {
                    activeWebView.stopLoading();
                    activeWebView.destroy();
                    activeWebView = null;
                }
                if (containerLayout != null && containerLayout.getParent() instanceof ViewGroup) {
                    ((ViewGroup) containerLayout.getParent()).removeView(containerLayout);
                    containerLayout = null;
                }
            } catch (Throwable ignored) {}
        });
    }

    private void handleClient(Socket client) {
        try (InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String path = parts[1];

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = reader.read(buf, totalRead, contentLength - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
                body = new String(buf, 0, totalRead);
            }

            if ("GET".equals(method)) {
                if ("/health".equals(path)) {
                    sendJsonResponse(out, 200, "{\"status\":\"ok\"}");
                } else {
                    JSONObject resp = new JSONObject();
                    resp.put("msg", "FlareSolverr is ready!");
                    resp.put("version", VERSION);
                    resp.put("userAgent", DEFAULT_UA);
                    sendJsonResponse(out, 200, resp.toString());
                }
            } else if ("POST".equals(method)) {
                handlePostRequest(out, body);
            } else {
                sendJsonResponse(out, 405, "{\"status\":\"error\",\"message\":\"Method Not Allowed\"}");
            }
        } catch (Throwable t) {
            Log.e(TAG, "[NativeFlareSolver] Client handle error: " + t.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void handlePostRequest(OutputStream out, String body) throws Exception {
        JSONObject req = new JSONObject(body.isEmpty() ? "{}" : body);
        String cmd = req.optString("cmd", "");
        long startTime = System.currentTimeMillis();

        if ("sessions.create".equalsIgnoreCase(cmd)) {
            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("message", "Session created successfully.");
            resp.put("session", "default");
            resp.put("startTimestamp", startTime);
            resp.put("endTimestamp", System.currentTimeMillis());
            resp.put("version", VERSION);
            sendJsonResponse(out, 200, resp.toString());
            return;
        }

        if ("sessions.destroy".equalsIgnoreCase(cmd)) {
            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("message", "The session has been removed.");
            resp.put("startTimestamp", startTime);
            resp.put("endTimestamp", System.currentTimeMillis());
            resp.put("version", VERSION);
            sendJsonResponse(out, 200, resp.toString());
            return;
        }

        if ("sessions.list".equalsIgnoreCase(cmd)) {
            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("message", "");
            resp.put("sessions", new JSONArray().put("default"));
            resp.put("startTimestamp", startTime);
            resp.put("endTimestamp", System.currentTimeMillis());
            resp.put("version", VERSION);
            sendJsonResponse(out, 200, resp.toString());
            return;
        }

        if ("request.get".equalsIgnoreCase(cmd) || "request.post".equalsIgnoreCase(cmd)) {
            String targetUrl = req.optString("url", "");
            String postData = req.optString("postData", null);
            long maxTimeout = req.optLong("maxTimeout", 60000);
            if (maxTimeout <= 0) maxTimeout = 60000;

            // Transparent subdomain resolver for 52BT (subdomains rotate every 30 mins)
            targetUrl = resolveDynamicSubdomains(targetUrl);
            Log.i(TAG, "[NativeFlareSolver] Solving (" + cmd + "): " + targetUrl);

            synchronized (solverLock) {
                SolveResult result = solveWithWebView(targetUrl, postData, maxTimeout);
                JSONObject resp = new JSONObject();
                if (result.success) {
                    resp.put("status", "ok");
                    resp.put("message", "Challenge solved!");
                    resp.put("startTimestamp", startTime);
                    resp.put("endTimestamp", System.currentTimeMillis());
                    resp.put("version", VERSION);

                    JSONObject sol = new JSONObject();
                    sol.put("url", result.url);
                    sol.put("status", result.statusCode);
                    sol.put("headers", new JSONObject());
                    sol.put("response", result.html);
                    sol.put("cookies", result.cookies);
                    sol.put("userAgent", DEFAULT_UA);
                    resp.put("solution", sol);

                    sendJsonResponse(out, 200, resp.toString());
                } else {
                    resp.put("status", "error");
                    resp.put("message", result.errorMessage != null ? result.errorMessage : "Challenge timeout or unresolvable");
                    resp.put("startTimestamp", startTime);
                    resp.put("endTimestamp", System.currentTimeMillis());
                    resp.put("version", VERSION);
                    sendJsonResponse(out, 500, resp.toString());
                }
            }
            return;
        }

        sendJsonResponse(out, 400, "{\"status\":\"error\",\"message\":\"Unknown command: " + cmd + "\"}");
    }

    /**
     * Resolves rotating subdomains (e.g. 52BT / 529075.xyz / 529076.xyz)
     * using the official 30-minute seeded algorithm from 52btbt.icu.
     */
    private String resolveDynamicSubdomains(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host != null) {
                if (host.endsWith(".529075.xyz")) {
                    String sub = compute52BtSubdomain("529075.xyz", 0);
                    return url.replace(host, sub + ".529075.xyz");
                } else if (host.endsWith(".529076.xyz")) {
                    String sub = compute52BtSubdomain("529076.xyz", 1);
                    return url.replace(host, sub + ".529076.xyz");
                }
            }
        } catch (Throwable ignored) {}
        return url;
    }

    private static String compute52BtSubdomain(String domainHost, int index) {
        long slot = System.currentTimeMillis() / (30 * 60 * 1000L);
        String seedText = "address-page-2026|" + domainHost + "|" + slot + "|" + index;
        long hash = 2166136261L;
        for (int i = 0; i < seedText.length(); i++) {
            hash ^= (int) seedText.charAt(i);
            hash = (hash * 16777619L) & 0xFFFFFFFFL;
        }
        long state = (hash == 0) ? 1 : hash;
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            state ^= (state << 13) & 0xFFFFFFFFL;
            state ^= (state >>> 17);
            state ^= (state << 5) & 0xFFFFFFFFL;
            sb.append(alphabet.charAt((int) (state % alphabet.length())));
        }
        return sb.toString();
    }

    private static class SolveResult {
        boolean success = false;
        int statusCode = 200;
        String url = "";
        String html = "";
        JSONArray cookies = new JSONArray();
        String errorMessage = null;
    }

    private SolveResult solveWithWebView(String targetUrl, String postData, long timeoutMs) {
        SolveResult res = new SolveResult();
        res.url = targetUrl;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<String> currentTitle = new AtomicReference<>("");

        mainHandler.post(() -> {
            try {
                if (activeWebView == null && containerLayout != null && containerLayout.getContext() != null) {
                    try {
                        activeWebView = createConfiguredWebView(containerLayout.getContext());
                        containerLayout.addView(activeWebView);
                        Log.i(TAG, "[NativeFlareSolver] Recreated WebView runtime after render process recovery");
                    } catch (Throwable t) {
                        Log.e(TAG, "[NativeFlareSolver] Failed to recreate WebView: " + t.getMessage());
                    }
                }
                if (activeWebView == null) {
                    res.errorMessage = "WebView runtime is not initialized";
                    latch.countDown();
                    return;
                }

                // Stop any leftover previous loading
                activeWebView.stopLoading();

                activeWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                        Log.w(TAG, "[NativeFlareSolver] WebView render process killed by OS (crashed=" + (detail != null && detail.didCrash()) + ")");
                        if (view != null) {
                            if (view.getParent() instanceof ViewGroup) {
                                ((ViewGroup) view.getParent()).removeView(view);
                            }
                            try {
                                view.destroy();
                            } catch (Throwable ignored) {}
                        }
                        activeWebView = null;
                        res.success = false;
                        res.errorMessage = "WebView render process killed by OS in background";
                        if (completed.compareAndSet(false, true)) {
                            latch.countDown();
                        }
                        return true; // Crucial: prevents Android OS from terminating host application process
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        super.onPageFinished(view, url);
                        if (url != null && !url.equals("about:blank")) {
                            currentTitle.set(view.getTitle() != null ? view.getTitle() : "");
                            checkChallengeSolved(view, targetUrl, res, completed, latch, true);
                        }
                    }

                    @Override
                    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                        super.onReceivedError(view, request, error);
                        if (request != null && request.isForMainFrame()) {
                            int errCode = error.getErrorCode();
                            String desc = String.valueOf(error.getDescription());
                            Log.w(TAG, "[NativeFlareSolver] Main frame error: " + desc + " (" + errCode + ")");
                            res.success = false;
                            res.errorMessage = "Failed to load " + targetUrl + ": " + desc + " (net error " + errCode + ")";
                            if (completed.compareAndSet(false, true)) {
                                latch.countDown();
                            }
                        }
                    }
                });

                if (postData != null && !postData.isEmpty()) {
                    activeWebView.postUrl(targetUrl, postData.getBytes(StandardCharsets.UTF_8));
                } else {
                    activeWebView.loadUrl(targetUrl);
                }

                // Polling loop to detect Turnstile resolution while page executes JS
                Runnable checker = new Runnable() {
                    int checks = 0;
                    @Override
                    public void run() {
                        if (completed.get() || activeWebView == null) return;
                        checks++;
                        checkChallengeSolved(activeWebView, targetUrl, res, completed, latch, false);
                        if (!completed.get() && checks < (timeoutMs / 500)) {
                            mainHandler.postDelayed(this, 500);
                        }
                    }
                };
                mainHandler.postDelayed(checker, 750);

            } catch (Throwable t) {
                res.errorMessage = t.getMessage();
                latch.countDown();
            }
        });

        try {
            boolean done = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                res.errorMessage = "Timed out waiting for challenge resolution (" + (timeoutMs / 1000) + "s)";
            }
        } catch (InterruptedException e) {
            res.errorMessage = "Interrupted during solve";
        }
        return res;
    }

    private void checkChallengeSolved(WebView view, String targetUrl, SolveResult res, AtomicBoolean completed, CountDownLatch latch, boolean isPageFinished) {
        if (completed.get() || view == null) return;

        String currentUrl = view.getUrl();
        if (currentUrl == null || currentUrl.isEmpty() || "about:blank".equals(currentUrl)) return;

        // Ensure the WebView has navigated to the requested domain (prevent inspecting previous page DOM)
        String targetHost = "";
        try {
            targetHost = new URL(targetUrl).getHost();
        } catch (Exception ignored) {}
        if (!targetHost.isEmpty() && !currentUrl.contains(targetHost)) {
            return;
        }

        String title = view.getTitle();
        if (title == null) title = "";

        boolean isCloudflare = title.contains("Just a moment...") || title.contains("Attention Required! | Cloudflare") || title.contains("Cloudflare");

        if (isCloudflare) {
            // Attempt Turnstile auto-click script across iframes and shadow root
            view.evaluateJavascript(
                    "(function(){" +
                    "try {" +
                    "  var iframes = document.querySelectorAll('iframe');" +
                    "  for (var i = 0; i < iframes.length; i++) {" +
                    "    try {" +
                    "      var doc = iframes[i].contentDocument || iframes[i].contentWindow.document;" +
                    "      if (doc) {" +
                    "        var cb = doc.querySelector('input[type=checkbox], .ctp-checkbox-label, #challenge-stage input');" +
                    "        if (cb) { cb.click(); return 'CLICKED_IFRAME'; }" +
                    "      }" +
                    "    } catch(e) {}" +
                    "  }" +
                    "  var cb2 = document.querySelector('input[type=checkbox]');" +
                    "  if (cb2 && cb2.offsetParent !== null) { cb2.click(); return 'CLICKED_MAIN'; }" +
                    "  var wrapper = document.querySelector('.cf-turnstile, #cf-turnstile, [data-sitekey]');" +
                    "  if (wrapper) { wrapper.click(); return 'CLICKED_WRAPPER'; }" +
                    "} catch(e) {}" +
                    "return 'WAITING';" +
                    "})();", null);
            return;
        }

        // Check for clearance cookies or successful challenge pass
        CookieManager cm = CookieManager.getInstance();
        String cookieStr = cm.getCookie(currentUrl);
        boolean hasClearance = cookieStr != null && cookieStr.contains("cf_clearance");

        // Solved only if cf_clearance cookie is present OR (page has finished loading, title is non-empty, and not an error page)
        if (hasClearance || (isPageFinished && !title.isEmpty() && !title.contains("Webpage not available") && !title.contains("403 Forbidden") && !title.contains("Blocked") && !title.contains("Error"))) {
            if (completed.compareAndSet(false, true)) {
                final String finalCurrentUrl = currentUrl;
                final String finalTargetHost = targetHost;
                final String finalTitle = title;
                view.evaluateJavascript("document.documentElement.outerHTML", (ValueCallback<String>) rawHtml -> {
                    try {
                        String html = "";
                        if (rawHtml != null) {
                            Object tok = new JSONTokener(rawHtml).nextValue();
                            html = tok != null ? tok.toString() : rawHtml;
                        }
                        res.html = html;
                        res.url = view.getUrl() != null ? view.getUrl() : targetUrl;
                        res.cookies = parseCookies(cm.getCookie(finalCurrentUrl), finalCurrentUrl);
                        res.success = true;
                        Log.i(TAG, "[NativeFlareSolver] Challenge solved for " + finalTargetHost + " (title: " + finalTitle + ")");
                    } catch (Throwable t) {
                        res.html = rawHtml != null ? rawHtml : "";
                        res.success = true;
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }
    }

    private JSONArray parseCookies(String cookieString, String fallbackUrl) {
        JSONArray arr = new JSONArray();
        String host = "localhost";
        try {
            host = new URL(fallbackUrl).getHost();
            if (host.contains(":")) host = host.split(":")[0];
        } catch (Exception ignored) {}

        if (cookieString != null && !cookieString.trim().isEmpty()) {
            String[] parts = cookieString.split(";");
            for (String part : parts) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0) {
                    String name = p.substring(0, eq).trim();
                    String val = p.substring(eq + 1).trim();
                    try {
                        JSONObject c = new JSONObject();
                        c.put("name", name);
                        c.put("value", val);
                        c.put("domain", host);
                        c.put("path", "/");
                        arr.put(c);
                    } catch (Exception ignored) {}
                }
            }
        }

        if (arr.length() == 0) {
            try {
                JSONObject c = new JSONObject();
                c.put("name", "cf_clearance");
                c.put("value", "native_bypass_" + System.currentTimeMillis());
                c.put("domain", host);
                c.put("path", "/");
                arr.put(c);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String statusText = (statusCode == 200) ? "OK" : (statusCode == 400) ? "Bad Request" : (statusCode == 405) ? "Method Not Allowed" : "Internal Server Error";
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }
}
