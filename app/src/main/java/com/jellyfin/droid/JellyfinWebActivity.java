package com.jellyfin.droid;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Hardened Jellyfin WebView activity.
 * Handles startup races by showing a native loader until the server is genuinely RUNNING (READY).
 * Configures WebView settings for local responsiveness without breaking security.
 */
public final class JellyfinWebActivity extends AppCompatActivity implements JellyfinController.Listener {
    private WebView web;
    private FrameLayout rootLayout;
    private View loadingView;
    private JellyfinController controller;
    private boolean loaded = false;

    @SuppressLint("SetJavaScriptEnabled") 
    @Override 
    public void onCreate(Bundle state) {
        super.onCreate(state);
        controller = JellyfinController.getInstance();

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.rgb(18, 24, 31));

        // Create WebView (invisible initially)
        web = new WebView(this);
        web.setVisibility(View.GONE);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override 
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { 
                String host = request.getUrl().getHost();
                return !"127.0.0.1".equals(host) && !"localhost".equals(host); 
            }
        });

        // Create Loading View
        loadingView = createLoadingView();

        rootLayout.addView(web);
        rootLayout.addView(loadingView);
        setContentView(rootLayout);

        controller.addListener(this);
        checkServerState(controller.getState());
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
    protected void onDestroy() {
        controller.removeListener(this);
        if (web != null) {
            web.loadUrl("about:blank");
            web.stopLoading();
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
