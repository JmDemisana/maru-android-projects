package io.maru.helper;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

public class AppletHostActivity extends AppCompatActivity {
    public static final String EXTRA_APPLET_ID = "io.maru.helper.extra.APPLET_ID";
    public static final String EXTRA_APPLET_NAME = "io.maru.helper.extra.APPLET_NAME";
    public static final String EXTRA_APPLET_PATH = "io.maru.helper.extra.APPLET_PATH";

    private static final int BG_DARK = 0xFF0B1020;

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG_DARK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setBackgroundColor(BG_DARK);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setDatabaseEnabled(true);
        webView.addJavascriptInterface(new AppletNativeBridge(), "AppletNativeBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null) {
                    return false;
                }
                String scheme = safeTrim(uri.getScheme()).toLowerCase();
                if (!("http".equals(scheme) || "https".equals(scheme))) {
                    try {
                        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri);
                        intent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {
                        return true;
                    }
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        root.addView(webView);

        setContentView(root);
        applyWindowInsets(root);
        webView.loadUrl(resolveAppletUrl());
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private String resolveAppletUrl() {
        String origin = HelperStorage.resolvePublicSiteOrigin(this);
        String path = safeTrim(getIntent().getStringExtra(EXTRA_APPLET_PATH));
        String name = safeTrim(getIntent().getStringExtra(EXTRA_APPLET_NAME));
        if (path.isEmpty()) {
            path = "/mobile-app";
        }

        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return origin + "/applet-shell.html?name=" + Uri.encode(name) + "&route=" + Uri.encode(path);
    }

    private void applyWindowInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, systemBars.top, 0, systemBars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private final class AppletNativeBridge {
        @android.webkit.JavascriptInterface
        public String getSharedAuthUser() {
            return HelperStorage.getSharedAuthUser(AppletHostActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void setSharedAuthUser(String rawAuthUser) {
            HelperStorage.persistSharedAuthUser(AppletHostActivity.this, rawAuthUser);
        }

        @android.webkit.JavascriptInterface
        public void clearSharedAuthUser() {
            HelperStorage.persistSharedAuthUser(AppletHostActivity.this, "");
        }

        @android.webkit.JavascriptInterface
        public String getServerOrigin() {
            return HelperStorage.resolveDetectorServerOrigin(AppletHostActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void openLinkAccountSync() {
            launchLinkSettingsPanel("account-sync");
        }

        @android.webkit.JavascriptInterface
        public void openLinkSettingsPanel(String rawPanel) {
            launchLinkSettingsPanel(rawPanel);
        }

        @android.webkit.JavascriptInterface
        public String getElevationToken() {
            return HelperStorage.getElevationToken(AppletHostActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void setElevationToken(String token) {
            HelperStorage.persistElevationToken(AppletHostActivity.this, token);
        }

        @android.webkit.JavascriptInterface
        public void clearElevationToken() {
            HelperStorage.clearElevationState(AppletHostActivity.this);
        }
    }

    private void launchLinkSettingsPanel(String rawPanel) {
        String serverOrigin = HelperStorage.resolveDetectorServerOrigin(this);
        String panel = rawPanel == null ? "" : rawPanel.trim();
        Uri.Builder builder = new Uri.Builder()
            .scheme("maruhelper")
            .authority("helper")
            .appendQueryParameter("action", "open-settings");

        if (!panel.isEmpty()) {
            builder.appendQueryParameter("panel", panel);
        }

        if (serverOrigin != null && !serverOrigin.trim().isEmpty()) {
            builder.appendQueryParameter("siteOrigin", serverOrigin.trim());
        }

        android.content.Intent launchIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW, builder.build());
        launchIntent.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            startActivity(launchIntent);
        } catch (Exception ignored) {
        }
    }
}
