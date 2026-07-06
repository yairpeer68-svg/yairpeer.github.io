package io.github.yairpeer.tzel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#071324"));
        setContentView(webView);
        getWindow().setStatusBarColor(Color.parseColor("#071324"));
        getWindow().setNavigationBarColor(Color.parseColor("#071324"));

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        // צל דיגיטלי מדבר עם שרתים חיצוניים (Supabase, HIBP) — לכן נטען מהאתר החי,
        // כך שכל עדכון באתר מגיע אוטומטית גם לאפליקציה.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri url = request.getUrl();
                String host = url.getHost();
                // קישורים חיצוניים (haveibeenpwned, gov.il וכו') נפתחים בדפדפן המערכת
                if (host != null && !host.contains("yairpeer68-svg.github.io")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                    return true;
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.loadUrl("https://yairpeer68-svg.github.io/yairpeer.github.io/tzel/");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
