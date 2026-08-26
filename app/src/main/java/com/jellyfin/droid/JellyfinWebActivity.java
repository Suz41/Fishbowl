package com.jellyfin.droid;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.BuildConfig;

import java.net.InetAddress;

/**
 * Phase 12: Playback & Streaming Performance Hardened Jellyfin Activity.
 *
 * Enhancements:
 *  - Media playback without user gesture constraint (instant play start)
 *  - Full-screen HTML5 video support via WebChromeClient.CustomViewCallback
 *  - Offscreen pre-rastering & hardware window acceleration
 *  - Session cookie persistence via CookieManager
 *  - Native loading overlay until server is genuinely RUNNING (READY)
 */
public final class JellyfinWebActivity extends AppCompatActivity implements JellyfinController.Listener {
    private static final String TAG = "JellyfinWebActivity";

    private WebView web;
    private FrameLayout rootLayout;
    private View loadingView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private JellyfinController controller;
    private boolean loaded = false;

    @SuppressLint("SetJavaScriptEnabled") 
    @Override 
    public void onCreate(Bundle state) {
        super.onCreate(state);
        controller = JellyfinController.getInstance();

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.rgb(18, 24, 31));

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
                return !isLocalOrLanHost(host);
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
        });

        // Create Native Loading View
        loadingView = createLoadingView();

        rootLayout.addView(web);
        rootLayout.addView(loadingView);
        setContentView(rootLayout);

        controller.addListener(this);
        checkServerState(controller.getState());
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
        layout.setBackgroundColor(Color.rgb(18, 24, 31));

        ProgressBar bar = new ProgressBar(this);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        layout.addView(bar, barParams);

        TextView txt = new TextView(this);
        txt.setText("Starting Jellyfin...");
        txt.setTextColor(Color.WHITE);
        txt.setTextSize(16);
        FrameLayout.LayoutParams txtParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        txtParams.bottomMargin = dp(60);
        layout.addView(txt, txtParams);

        return layout;
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
