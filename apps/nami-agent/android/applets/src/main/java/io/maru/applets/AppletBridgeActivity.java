package io.maru.applets;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.getcapacitor.BridgeActivity;

/**
 * Base activity for all standalone Maru applet APKs.
 * Blocks launch if Maru Link (io.maru.helper) is not installed.
 */
public abstract class AppletBridgeActivity extends BridgeActivity {
    private static final String LINK_SETTINGS_PANEL_ACCOUNT_SYNC = "account-sync";
    private static final String LINK_AUTHORITY = "io.maru.helper.sharedstate";
    private static final Uri LINK_SHARED_STATE_URI = Uri.parse("content://" + LINK_AUTHORITY);
    private static final String LINK_SHARED_AUTH_METHOD = "getSharedAuthUser";
    private static final String LINK_SERVER_ORIGIN_METHOD = "getServerOrigin";
    private static final String LINK_RESULT_KEY = "value";
    private static final String LINK_SCHEME = "maruhelper";
    private static final String LINK_HOST = "helper";
    private static final String LINK_PACKAGE = "io.maru.helper";
    private static final int BG_TOP = 0xFF102042;
    private static final int BG_MID = 0xFF0B1224;
    private static final int BG_BOTTOM = 0xFF070B15;
    private static final int CARD_TOP = 0xFF162749;
    private static final int CARD_MID = 0xFF111C31;
    private static final int CARD_BOTTOM = 0xFF0B1220;
    private static final int CARD_BORDER = 0xFF28406A;
    private static final int TEXT_PRIMARY = 0xFFF4F7FF;
    private static final int TEXT_SECONDARY = 0xFFA2B3D3;

    protected abstract String getAppletDisplayName();

    protected boolean isAppletSupportedOnDevice() {
        return true;
    }

    protected String getUnsupportedTitle() {
        return getAppletDisplayName() + " works best on desktop";
    }

    protected String getUnsupportedMessage() {
        return getAppletDisplayName() + " is currently set up for desktop use, so this Android build stays as a lightweight handoff only.";
    }

    protected String getUnsupportedNote() {
        return "Open it from your desktop app or the website on a computer.";
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isLinkInstalled()) {
            showLinkGate();
            return;
        }
        if (!isAppletSupportedOnDevice()) {
            showUnsupportedGate();
            return;
        }
        attachAppletBridge();
    }

    private boolean isLinkInstalled() {
        try {
            getPackageManager().getPackageInfo(LINK_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void attachAppletBridge() {
        if (getBridge() == null || getBridge().getWebView() == null) {
            return;
        }
        getBridge().getWebView().addJavascriptInterface(
            new AppletNativeBridge(),
            "AppletNativeBridge"
        );
    }

    private String callLinkSharedState(String method) {
        try {
            ContentResolver resolver = getContentResolver();
            Bundle result = resolver.call(LINK_SHARED_STATE_URI, method, null, null);
            if (result == null) {
                return "";
            }
            String value = result.getString(LINK_RESULT_KEY, "");
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private void launchLinkSettingsPanel(String rawPanel) {
        String serverOrigin = callLinkSharedState(LINK_SERVER_ORIGIN_METHOD);
        String panel = rawPanel == null ? "" : rawPanel.trim();
        Uri.Builder builder = new Uri.Builder()
            .scheme(LINK_SCHEME)
            .authority(LINK_HOST)
            .appendQueryParameter("action", "open-settings");

        if (!panel.isEmpty()) {
            builder.appendQueryParameter("panel", panel);
        }

        if (serverOrigin != null && !serverOrigin.trim().isEmpty()) {
            builder.appendQueryParameter("siteOrigin", serverOrigin.trim());
        }

        Intent launchIntent = new Intent(Intent.ACTION_VIEW, builder.build());
        launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                startActivity(launchIntent);
            } catch (Exception ignored) {
                // Ignore handoff failures and leave the current app visible.
            }
        });
    }

    private final class AppletNativeBridge {
        @JavascriptInterface
        public String getSharedAuthUser() {
            return callLinkSharedState(LINK_SHARED_AUTH_METHOD);
        }

        @JavascriptInterface
        public String getServerOrigin() {
            return callLinkSharedState(LINK_SERVER_ORIGIN_METHOD);
        }

        @JavascriptInterface
        public void openLinkAccountSync() {
            launchLinkSettingsPanel(LINK_SETTINGS_PANEL_ACCOUNT_SYNC);
        }

        @JavascriptInterface
        public void openLinkSettingsPanel(String rawPanel) {
            launchLinkSettingsPanel(rawPanel);
        }

        @JavascriptInterface
        public void setSharedAuthUser(String rawAuthUser) {
            // Standalone applets mirror shared auth from Maru Link, but never own it.
        }

        @JavascriptInterface
        public void clearSharedAuthUser() {
            // Standalone applets mirror shared auth from Maru Link, but never own it.
        }
    }

    private void showLinkGate() {
        showInfoGate(
            "Maru Link Required",
            getAppletDisplayName() +
            " needs Maru Link to run. It handles installs, updates, shared sign-in, and cross-app phone services for all Maru apps.",
            "Install it, then reopen this app.",
            "Get Maru Link",
            v -> {
                try {
                    startActivity(new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/JmDemisana/maru-mobile/releases/latest")
                    ));
                } catch (Exception ignored) {
                }
            },
            "Try Again",
            v -> recreate()
        );
    }

    private void showUnsupportedGate() {
        showInfoGate(
            getUnsupportedTitle(),
            getUnsupportedMessage(),
            getUnsupportedNote(),
            "Open Website",
            v -> {
                String serverOrigin = callLinkSharedState(LINK_SERVER_ORIGIN_METHOD);
                String target = serverOrigin == null || serverOrigin.trim().isEmpty()
                    ? "https://maruchansquigle.vercel.app"
                    : serverOrigin.trim();
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                } catch (Exception ignored) {
                }
            },
            "Close",
            v -> finish()
        );
    }

    private void showInfoGate(
        String titleText,
        String bodyText,
        String noteText,
        String primaryLabel,
        android.view.View.OnClickListener primaryAction,
        String secondaryLabel,
        android.view.View.OnClickListener secondaryAction
    ) {
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scroll.setBackground(makeScreenGradient());

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        int pad = dp(28);
        layout.setPadding(pad, pad, pad, pad);
        layout.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        TextView eyebrow = new TextView(this);
        eyebrow.setText("NATIVE APPLET");
        eyebrow.setTextSize(11.5f);
        eyebrow.setTextColor(0xFF8FA6D5);
        eyebrow.setTypeface(eyebrow.getTypeface(), Typeface.BOLD);
        eyebrow.setLetterSpacing(0.08f);
        eyebrow.setGravity(Gravity.CENTER);
        eyebrow.setPadding(0, 0, 0, dp(10));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        card.setBackground(makeCardBackground());

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(28);
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(14));

        TextView body = new TextView(this);
        body.setText(bodyText);
        body.setTextSize(15);
        body.setTextColor(TEXT_SECONDARY);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 0, 0, dp(28));
        body.setLineSpacing(0, 1.45f);

        Button installBtn = new Button(this);
        installBtn.setText(primaryLabel);
        installBtn.setTextSize(16);
        installBtn.setTextColor(TEXT_PRIMARY);
        installBtn.setBackground(makePrimaryButtonBackground());
        int bp = dp(14);
        installBtn.setPadding(bp, bp, bp, bp);
        installBtn.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        installBtn.setAllCaps(false);
        installBtn.setOnClickListener(primaryAction);

        Button retryBtn = new Button(this);
        retryBtn.setText(secondaryLabel);
        retryBtn.setTextSize(16);
        retryBtn.setTextColor(TEXT_PRIMARY);
        retryBtn.setBackground(makeSecondaryButtonBackground());
        retryBtn.setAllCaps(false);
        retryBtn.setPadding(bp, bp, bp, bp);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        retryParams.topMargin = dp(10);
        retryBtn.setLayoutParams(retryParams);
        retryBtn.setOnClickListener(secondaryAction);

        TextView note = new TextView(this);
        note.setText(noteText);
        note.setTextSize(13);
        note.setTextColor(0xFF8595B8);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(18), 0, 0);

        card.addView(title);
        card.addView(body);
        card.addView(installBtn);
        card.addView(retryBtn);
        card.addView(note);
        layout.addView(eyebrow);
        layout.addView(card);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private GradientDrawable makeScreenGradient() {
        return new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { BG_TOP, BG_MID, BG_BOTTOM }
        );
    }

    private GradientDrawable makeCardBackground() {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { CARD_TOP, CARD_MID, CARD_BOTTOM }
        );
        drawable.setCornerRadius(dp(24));
        drawable.setStroke(dp(1), CARD_BORDER);
        return drawable;
    }

    private GradientDrawable makePrimaryButtonBackground() {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { 0xFF5E86F8, 0xFF3858B3 }
        );
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), 0xFF7EA0FF);
        return drawable;
    }

    private GradientDrawable makeSecondaryButtonBackground() {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { 0xFF223253, 0xFF16243E }
        );
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), 0xFF3A5487);
        return drawable;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
