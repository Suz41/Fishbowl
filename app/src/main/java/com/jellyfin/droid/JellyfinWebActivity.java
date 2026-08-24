package com.jellyfin.droid;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

/** Isolated local WebView: no JS bridge, file access, or navigation away from localhost. */
public final class JellyfinWebActivity extends AppCompatActivity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled") @Override public void onCreate(Bundle state) {
        super.onCreate(state); 
        web = new WebView(this); 
        web.getSettings().setJavaScriptEnabled(true); 
        web.getSettings().setDomStorageEnabled(true); 
        web.getSettings().setAllowFileAccess(false); 
        web.getSettings().setAllowContentAccess(false); 
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { 
                return !"127.0.0.1".equals(request.getUrl().getHost()); 
            }
        }); 
        web.loadUrl("http://127.0.0.1:8096/web/"); 
        setContentView(web);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.stopLoading();
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
