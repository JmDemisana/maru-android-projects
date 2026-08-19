package io.maru.applets;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

public abstract class LinkBoundActivity extends AppCompatActivity {
    private static final String LINK_AUTHORITY = "io.maru.helper.sharedstate";
    private static final Uri LINK_SHARED_STATE_URI = Uri.parse("content://" + LINK_AUTHORITY);
    private static final String LINK_ACCOUNT_TOKEN_METHOD = "getAccountToken";
    private static final String LINK_SHARED_AUTH_METHOD = "getSharedAuthUser";
    private static final String LINK_SERVER_ORIGIN_METHOD = "getServerOrigin";
    private static final String LINK_RESULT_KEY = "value";
    private static final String LINK_SCHEME = "maruhelper";
    private static final String LINK_HOST = "helper";
    private static final String LINK_PACKAGE = "io.maru.helper";
    private static final String LINK_RELEASES_URL =
        "https://github.com/JmDemisana/maru-mobile/releases/latest";
    private static final int BG_TOP = 0xFF102042;
    private static final int BG_MID = 0xFF0B1224;
    private static final int BG_BOTTOM = 0xFF070B15;
    private static final int CARD_TOP = 0xFF162749;
    private static final int CARD_MID = 0xFF111C31;
    private static final int CARD_BOTTOM = 0xFF0B1220;
    private static final int CARD_BORDER = 0xFF28406A;
    private static final int TEXT_PRIMARY = 0xFFF4F7FF;
    private static final int TEXT_SECONDARY = 0xFFA2B3D3;

    public static final class SharedAuthUser {
        public final String userId;
        public final String email;
        public final String fullName;

        SharedAuthUser(String userId, String email, String fullName) {
            this.userId = safeTrim(userId);
            this.email = safeTrim(email);
            this.fullName = safeTrim(fullName);
        }

        public boolean isValid() {
            return !userId.isEmpty() && !email.isEmpty() && !fullName.isEmpty();
        }
    }

    protected boolean ensureLinkAvailable(String appName) {
        if (isLinkInstalled()) {
            return true;
        }
        showLinkGate(appName);
        return false;
    }

    protected boolean isLinkInstalled() {
        try {
            PackageInfo ignored;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ignored = getPackageManager().getPackageInfo(
                    LINK_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0L)
                );
            } else {
                ignored = getPackageManager().getPackageInfo(LINK_PACKAGE, 0);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    protected String getAccountToken() {
        return callLinkSharedState(LINK_ACCOUNT_TOKEN_METHOD);
    }

    protected SharedAuthUser getSharedAuthUser() {
        String rawValue = callLinkSharedState(LINK_SHARED_AUTH_METHOD);
        if (rawValue.isEmpty()) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(rawValue);
            SharedAuthUser user = new SharedAuthUser(
                json.optString("userId", ""),
                json.optString("email", ""),
                json.optString("fullName", "")
            );
            return user.isValid() ? user : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    protected String getServerOrigin() {
        return callLinkSharedState(LINK_SERVER_ORIGIN_METHOD);
    }

    protected void openLinkAccountSync() {
        openLinkSettingsPanel("account-sync");
    }

    protected void openLinkSettingsPanel(@Nullable String rawPanel) {
        Uri.Builder builder = new Uri.Builder()
            .scheme(LINK_SCHEME)
            .authority(LINK_HOST)
            .appendQueryParameter("action", "open-settings");

        String panel = safeTrim(rawPanel);
        if (!panel.isEmpty()) {
            builder.appendQueryParameter("panel", panel);
        }

        String serverOrigin = getServerOrigin();
        if (!serverOrigin.isEmpty()) {
            builder.appendQueryParameter("siteOrigin", serverOrigin);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, builder.build());
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            launchLinkInstaller();
        }
    }

    protected void stylePrimaryButton(Button button) {
        styleButton(button, 0xFF5E86F8, 0xFF3858B3, 0xFF7EA0FF, TEXT_PRIMARY);
    }

    protected void styleSecondaryButton(Button button) {
        styleButton(button, 0xFF223253, 0xFF16243E, 0xFF3A5487, TEXT_PRIMARY);
    }

    protected void styleDangerButton(Button button) {
        styleButton(button, 0xFF6B3946, 0xFF47202B, 0xFFBF7385, TEXT_PRIMARY);
    }

    protected TextView makeSectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_PRIMARY);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(20f);
        return view;
    }

    protected TextView makeBodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_SECONDARY);
        view.setTextSize(14.5f);
        view.setLineSpacing(0f, 1.3f);
        return view;
    }

    protected TextView makeEyebrowText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.parseColor("#8FA6D5"));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(11.5f);
        view.setLetterSpacing(0.08f);
        return view;
    }

    protected LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(makeGlassDrawable(22));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(16);
        card.setLayoutParams(params);
        return card;
    }

    protected ScrollView createScrollableRoot() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { BG_TOP, BG_MID, BG_BOTTOM }
        );
        scrollView.setBackground(background);
        return scrollView;
    }

    protected LinearLayout createPageColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(24), dp(20), dp(30));
        return column;
    }

    protected void setPageContent(LinearLayout column) {
        final ScrollView scrollView = createScrollableRoot();
        final int baseLeft = column.getPaddingLeft();
        final int baseTop = column.getPaddingTop();
        final int baseRight = column.getPaddingRight();
        final int baseBottom = column.getPaddingBottom();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        scrollView.addView(column);
        setContentView(scrollView);

        ViewCompat.setOnApplyWindowInsetsListener(scrollView, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            column.setPadding(
                baseLeft,
                baseTop + systemBars.top,
                baseRight,
                baseBottom + systemBars.bottom
            );
            return insets;
        });
        ViewCompat.requestApplyInsets(scrollView);
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showLinkGate(String appName) {
        ScrollView scroll = createScrollableRoot();
        LinearLayout column = createPageColumn();
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText(appName + " needs Maru Link");
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(28f);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(52);
        title.setLayoutParams(titleParams);

        TextView body = makeBodyText(
            "Maru Link handles installs, updates, shared sign-in, and phone-side services for this app. Install it first, then reopen " + appName + "."
        );
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bodyParams.topMargin = dp(14);
        bodyParams.bottomMargin = dp(24);
        body.setLayoutParams(bodyParams);

        Button installButton = new Button(this);
        installButton.setText("Get Maru Link");
        installButton.setTransformationMethod(null);
        stylePrimaryButton(installButton);
        installButton.setOnClickListener(view -> launchLinkInstaller());

        Button retryButton = new Button(this);
        retryButton.setText("Try Again");
        retryButton.setTransformationMethod(null);
        styleSecondaryButton(retryButton);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        retryParams.topMargin = dp(10);
        retryButton.setLayoutParams(retryParams);
        retryButton.setOnClickListener(view -> recreate());

        TextView eyebrow = makeEyebrowText("NATIVE APPLET");
        eyebrow.setGravity(Gravity.CENTER);
        column.addView(eyebrow);
        column.addView(title);
        column.addView(body);
        column.addView(installButton);
        column.addView(retryButton);
        scroll.addView(column);
        setContentView(scroll);
    }

    private void styleButton(
        Button button,
        int topColor,
        int bottomColor,
        int strokeColor,
        int textColor
    ) {
        button.setAllCaps(false);
        button.setTransformationMethod(null);
        button.setTextColor(textColor);
        button.setTextSize(16f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(58));
        button.setPadding(dp(18), dp(15), dp(18), dp(15));
        button.setBackground(makeButtonDrawable(topColor, bottomColor, strokeColor));
    }

    private GradientDrawable makeGlassDrawable(int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { CARD_TOP, CARD_MID, CARD_BOTTOM }
        );
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), CARD_BORDER);
        return drawable;
    }

    private GradientDrawable makeButtonDrawable(int topColor, int bottomColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { topColor, bottomColor }
        );
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(20));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void launchLinkInstaller() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(LINK_RELEASES_URL)));
        } catch (Exception ignored) {
        }
    }

    private String callLinkSharedState(String method) {
        try {
            ContentResolver resolver = getContentResolver();
            Bundle result = resolver.call(LINK_SHARED_STATE_URI, method, null, null);
            if (result == null) {
                return "";
            }
            return safeTrim(result.getString(LINK_RESULT_KEY, ""));
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
