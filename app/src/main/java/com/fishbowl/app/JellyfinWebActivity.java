package com.fishbowl.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.fishbowl.app.services.MediaServiceManager;
import com.termux.BuildConfig;
import com.termux.R;

import java.net.InetAddress;

/**
 * Phase 12: Playback & Streaming Performance Hardened Jellyfin Activity.
 * Also supports generic in-app service Web UI loading (Radarr, Sonarr, Prowlarr, Transmission, Seerr).
 */
public final class JellyfinWebActivity extends AppCompatActivity implements JellyfinController.Listener {
    private static final String TAG = "JellyfinWebActivity";

    public static final String EXTRA_URL = "extra_url";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_SERVICE_ID = "extra_service_id";

    private WebView web;
    private FrameLayout rootLayout;
    private View loadingView;
    private TextView loadingText;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private JellyfinController controller;
    private boolean loaded = false;
    private String customUrl = null;
    private String customTitle = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean isDestroyed = false;

    private static final int COLOR_BG = Color.BLACK;
    private static final int COLOR_SURFACE = Color.BLACK;
    private static final int COLOR_BORDER = Color.argb(20, 255, 255, 255);
    private static final int COLOR_TEXT_PRIMARY = Color.parseColor("#F8FAFC");
    private static final int COLOR_TEXT_MUTED = Color.parseColor("#8A9198");
    private static final int COLOR_ACCENT = Color.parseColor("#10B981");

    @SuppressLint("SetJavaScriptEnabled") 
    @Override 
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        controller = JellyfinController.getInstance();

        customUrl = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        customTitle = getIntent() != null ? getIntent().getStringExtra(EXTRA_TITLE) : null;

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(COLOR_BG);

        // Create WebView (invisible initially until server is RUNNING)
        web = new WebView(this);
        web.setVisibility(View.GONE);

        // Configure CookieManager for session/cookie persistence
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        // Configure WebSettings for playback & UI performance
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            settings.setMediaPlaybackRequiresUserGesture(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Enable responsive mobile viewport: respects <meta name="viewport" content="width=device-width">
        // so Radarr and Sonarr automatically detect and render their sleek mobile UI on phones,
        // while continuing to render the desktop UI when accessed from a desktop PC browser.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        String defaultUa = settings.getUserAgentString();
        if (defaultUa != null && !defaultUa.contains("Mobile")) {
            settings.setUserAgentString(defaultUa + " Mobile Safari/537.36");
        }

        // Set WebChromeClient for HTML5 full-screen video playback & console diagnostics
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (BuildConfig.DEBUG) {
                    Log.d("JellyfinWebJS", message.message() + " -- From line "
                            + message.lineNumber() + " of " + message.sourceId());
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    onHideCustomView();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                customView.setBackgroundColor(Color.BLACK);
                rootLayout.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                rootLayout.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                web.setVisibility(View.VISIBLE);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });

        // Set WebViewClient with smart LAN/loopback host validation and playback diagnostic logging
        web.setWebViewClient(new WebViewClient() {
            private String currentAttemptId = "";
            private long lastAttemptTime = 0;

            @Override 
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { 
                if (request == null || request.getUrl() == null) return false;
                String host = request.getUrl().getHost();
                if (!isLocalOrLanHost(host)) {
                    try {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                        startActivity(browserIntent);
                    } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (BuildConfig.DEBUG && request != null && request.getUrl() != null) {
                    String urlPath = request.getUrl().getPath();
                    if (urlPath != null && (urlPath.contains("/Items/") || urlPath.contains("/Videos/") || urlPath.contains("/hls/") || urlPath.contains("/Trailers"))) {
                        long now = System.currentTimeMillis();
                        if (now - lastAttemptTime > 4000 || currentAttemptId.isEmpty()) {
                            currentAttemptId = java.util.UUID.randomUUID().toString().substring(0, 8);
                        }
                        lastAttemptTime = now;

                        // Sanitize URL to ensure passwords, tokens, and secrets are NEVER logged
                        android.net.Uri uri = request.getUrl();
                        android.net.Uri.Builder cleanBuilder = uri.buildUpon().clearQuery();
                        for (String paramName : uri.getQueryParameterNames()) {
                            if (!"api_key".equalsIgnoreCase(paramName) && !"token".equalsIgnoreCase(paramName) && !"X-Emby-Token".equalsIgnoreCase(paramName)) {
                                cleanBuilder.appendQueryParameter(paramName, uri.getQueryParameter(paramName));
                            } else {
                                cleanBuilder.appendQueryParameter(paramName, "[REDACTED]");
                            }
                        }
                        String sanitizedUrl = cleanBuilder.build().toString();

                        String actionType = "OTHER_MEDIA_REQ";
                        if (urlPath.contains("/PlaybackInfo")) actionType = "PLAYBACK_INFO";
                        else if (urlPath.contains("/Trailers")) actionType = "TRAILER_REQ";
                        else if (urlPath.contains("/stream") || urlPath.contains("/master.m3u8") || urlPath.contains("/main.m3u8")) actionType = "STREAM_REQ";
                        else if (urlPath.contains("/hls/")) actionType = "HLS_TRANSCODE_REQ";

                        Log.d("JellyfinPlaybackDiag", String.format(
                                "[PLAYBACK_DIAG] [AttemptID: %s] [Timestamp: %d] [Action: %s] [Path: %s] [Method: %s]",
                                currentAttemptId, now, actionType, sanitizedUrl, request.getMethod()));
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Guarantee responsive mobile viewport for any UI missing meta tag
                view.evaluateJavascript(
                    "(function() {" +
                    "  if (!document.querySelector('meta[name=\"viewport\"]')) {" +
                    "    var meta = document.createElement('meta');" +
                    "    meta.name = 'viewport';" +
                    "    meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';" +
                    "    document.getElementsByTagName('head')[0].appendChild(meta);" +
                    "  }" +
                    "})();", null);
            }
        });

        // Create Native Loading View
        loadingView = createLoadingView();

        rootLayout.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(loadingView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(COLOR_BG);

        View header = createHeaderBar(customUrl);
        container.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        container.addView(rootLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(container);

        if (customUrl != null && !customUrl.isEmpty()) {
            connectToCustomService();
        } else {
            controller.addListener(this);
            checkServerState(controller.getState());
        }
    }

    private boolean isLocalOrLanHost(String host) {
        if (host == null || host.isEmpty()) return true;
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) return true;

        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private View createLoadingView() {
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(COLOR_BG);

        LinearLayout centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER);

        ProgressBar bar = new ProgressBar(this);
        centerBox.addView(bar);

        loadingText = new TextView(this);
        loadingText.setText("Starting Jellyfin...");
        loadingText.setTextColor(Color.WHITE);
        loadingText.setTextSize(15);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams txtParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txtParams.topMargin = dp(16);
        centerBox.addView(loadingText, txtParams);

        FrameLayout.LayoutParams boxParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        layout.addView(centerBox, boxParams);

        return layout;
    }

    private void connectToCustomService() {
        if (customUrl == null) return;
        loadingView.setVisibility(View.VISIBLE);
        web.setVisibility(View.GONE);
        if (loadingText != null) {
            loadingText.setText("Connecting to " + (customTitle != null ? customTitle : "service") + "...");
        }

        String serviceId = getIntent() != null ? getIntent().getStringExtra(EXTRA_SERVICE_ID) : null;
        if (serviceId == null) {
            if (customUrl.contains(":8096")) serviceId = "jellyfin";
            else if (customUrl.contains(":7878")) serviceId = "radarr";
            else if (customUrl.contains(":8989")) serviceId = "sonarr";
            else if (customUrl.contains(":9696")) serviceId = "prowlarr";
            else if (customUrl.contains(":9091")) serviceId = "transmission";
            else if (customUrl.contains(":5055")) serviceId = "seerr";
        }

        if (serviceId != null) {
            MediaServiceManager.getInstance(this).ensureServiceReadyAndGetUrl(serviceId, 35000, new MediaServiceManager.ServiceReadyCallback() {
                @Override
                public void onReady(String url) {
                    if (isDestroyed) return;
                    loaded = true;
                    loadingView.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    web.loadUrl(url != null ? url : customUrl);
                }

                @Override
                public void onError(String errorReason) {
                    if (isDestroyed) return;
                    if (loadingText != null) {
                        loadingText.setText("Failed to start service:\n" + errorReason);
                    }
                }
            });
        } else {
            // Direct load if no service ID mapped
            loaded = true;
            loadingView.setVisibility(View.GONE);
            web.setVisibility(View.VISIBLE);
            web.loadUrl(customUrl);
        }
    }

    private void checkServerState(JellyfinController.State state) {
        if (state == JellyfinController.State.RUNNING) {
            runOnUiThread(() -> {
                if (!loaded) {
                    loaded = true;
                    loadingView.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                    web.loadUrl("http://127.0.0.1:8096/web/");
                }
            });
        } else {
            runOnUiThread(() -> {
                if (loaded) {
                    loaded = false;
                    web.setVisibility(View.GONE);
                    loadingView.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private View createHeaderBar(String initialUrl) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.BLACK);
        bar.setPadding(dp(8), 0, dp(8), 0);

        // 1. Back button (Icon only, no text)
        ImageView btnBack = new ImageView(this);
        btnBack.setImageResource(R.drawable.ic_back);
        btnBack.setColorFilter(Color.WHITE);
        btnBack.setContentDescription("Back");
        btnBack.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnBack.setClickable(true);
        btnBack.setFocusable(true);
        btnBack.setOnClickListener(v -> {
            if (web != null && web.canGoBack()) {
                web.goBack();
            } else {
                finish();
            }
        });
        bar.addView(btnBack, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // Flexible spacer between Back button and right-hand action controls
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));

        // 2. Reload / Refresh button (Icon only, no text)
        ImageView btnReload = new ImageView(this);
        btnReload.setImageResource(R.drawable.ic_restart);
        btnReload.setColorFilter(Color.WHITE);
        btnReload.setContentDescription("Refresh");
        btnReload.setPadding(dp(11), dp(11), dp(11), dp(11));
        btnReload.setClickable(true);
        btnReload.setFocusable(true);
        btnReload.setOnClickListener(v -> {
            if (web != null) web.reload();
        });
        bar.addView(btnReload, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // 3. Open in external browser button (Icon only, no text)
        ImageView btnExternal = new ImageView(this);
        btnExternal.setImageResource(R.drawable.ic_open_in_browser);
        btnExternal.setColorFilter(Color.WHITE);
        btnExternal.setContentDescription("Open in Browser");
        btnExternal.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnExternal.setClickable(true);
        btnExternal.setFocusable(true);
        btnExternal.setOnClickListener(v -> {
            try {
                String target = (web != null && web.getUrl() != null && !web.getUrl().isEmpty())
                        ? web.getUrl()
                        : (initialUrl != null ? initialUrl : "http://127.0.0.1:8096/web/");
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target));
                startActivity(i);
            } catch (Exception ignored) {}
        });
        bar.addView(btnExternal, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // 4. Dedicated Exit / Close button (Icon only, no text, instant finish())
        ImageView btnExit = new ImageView(this);
        btnExit.setImageResource(R.drawable.ic_close);
        btnExit.setColorFilter(Color.WHITE);
        btnExit.setContentDescription("Close");
        btnExit.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnExit.setClickable(true);
        btnExit.setFocusable(true);
        btnExit.setOnClickListener(v -> finish());
        bar.addView(btnExit, new LinearLayout.LayoutParams(dp(44), dp(44)));

        wrapper.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));

        // Hairline bottom border
        View hairline = new View(this);
        hairline.setBackgroundColor(COLOR_BORDER);
        wrapper.addView(hairline, new LinearLayout.LayoutParams(-1, dp(1)));

        return wrapper;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
                customViewCallback = null;
            }
            if (rootLayout != null) {
                rootLayout.removeView(customView);
            }
            customView = null;
            if (web != null) web.setVisibility(View.VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override 
    public void onServerChanged(JellyfinController.State state) {
        checkServerState(state);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        }
    }

    @Override
    protected void onDestroy() {
        isDestroyed = true;
        handler.removeCallbacksAndMessages(null);
        controller.removeListener(this);
        if (web != null) {
            web.loadUrl("about:blank");
            web.stopLoading();
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
