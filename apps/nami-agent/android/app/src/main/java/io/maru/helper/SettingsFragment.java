package io.maru.helper;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

public class SettingsFragment extends Fragment {
    private static final String HELPER_APK = "maru.apk";

    private MainActivity activity;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (!isAdded() || activity == null) {
                return;
            }
            renderState();
            progressHandler.postDelayed(this, 900L);
        }
    };

    private TextView versionText;
    private TextView originText;
    private TextView installationIdText;
    private TextView secureStorageText;
    private TextView helperUpdateStatusText;
    private TextView helperUpdateNoteText;
    private TextView helperUpdateProgressText;
    private TextView sharedAccountStatusText;
    private TextView sharedAccountNoteText;
    private TextView accountTokenStatusText;
    private TextView accountTokenNoteText;
    private EditText accountTokenInput;
    private Button accountTokenVerifyButton;
    private Button accountTokenRemoveButton;
    private Button accountTokenToggleButton;
    private TextView stemStatusText;
    private TextView stemNoteText;
    private TextView schedEditNoteText;
    private TextView elevationStatusText;
    private TextView elevationNoteText;
    private TextView elevationMessageText;

    private Button checkHelperUpdateButton;
    private Button downloadHelperUpdateButton;
    private Button notificationButton;
    private Button sharedAccountButton;
    private Button schedEditButton;
    private Button openElevationButton;
    private Button toggleElevationLastFmButton;
    private Button removeElevationButton;
    private ProgressBar helperUpdateProgressBar;

    private boolean helperUpdateChecking = false;
    private String helperUpdateDownloadUrl = "";
    private String helperUpdateVersion = "";
    private String helperUpdateError = "";

    private boolean accountTokenVerifying = false;
    private String accountTokenMessage = "";
    private String accountTokenError = "";
    private boolean accountTokenExpanded = false;

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        activity = (MainActivity) requireActivity();

        versionText = view.findViewById(R.id.version_text);
        originText = view.findViewById(R.id.origin_text);
        installationIdText = view.findViewById(R.id.installation_id_text);
        secureStorageText = view.findViewById(R.id.secure_storage_text);
        helperUpdateStatusText = view.findViewById(R.id.helper_update_status_text);
        helperUpdateNoteText = view.findViewById(R.id.helper_update_note_text);
        helperUpdateProgressBar = view.findViewById(R.id.helper_update_progress_bar);
        helperUpdateProgressText = view.findViewById(R.id.helper_update_progress_text);
        sharedAccountStatusText = view.findViewById(R.id.shared_account_status_text);
        sharedAccountNoteText = view.findViewById(R.id.shared_account_note_text);
        accountTokenStatusText = view.findViewById(R.id.account_token_status_text);
        accountTokenNoteText = view.findViewById(R.id.account_token_note_text);
        accountTokenInput = view.findViewById(R.id.account_token_input);
        accountTokenVerifyButton = view.findViewById(R.id.btn_verify_account_token);
        accountTokenRemoveButton = view.findViewById(R.id.btn_remove_account_token);
        accountTokenToggleButton = view.findViewById(R.id.btn_toggle_account_token);
        stemStatusText = view.findViewById(R.id.stem_status_text);
        stemNoteText = view.findViewById(R.id.stem_note_text);
        schedEditNoteText = view.findViewById(R.id.schededit_note_text);
        elevationStatusText = view.findViewById(R.id.elevation_status_text);
        elevationNoteText = view.findViewById(R.id.elevation_note_text);
        elevationMessageText = view.findViewById(R.id.elevation_message_text);

        checkHelperUpdateButton = view.findViewById(R.id.btn_check_helper_update);
        downloadHelperUpdateButton = view.findViewById(R.id.btn_download_helper_update);
        notificationButton = view.findViewById(R.id.btn_open_notification_settings);
        sharedAccountButton = view.findViewById(R.id.btn_open_shared_account);
        schedEditButton = view.findViewById(R.id.btn_schededit);
        openElevationButton = view.findViewById(R.id.btn_open_elevation);
        toggleElevationLastFmButton = view.findViewById(R.id.btn_toggle_elevation_lastfm);
        removeElevationButton = view.findViewById(R.id.btn_remove_elevation);

        bindStaticDeviceInfo();
        bindActions();
        renderState();
        loadHelperUpdate(false);
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        progressHandler.removeCallbacks(progressTicker);
        progressHandler.post(progressTicker);
    }

    @Override
    public void onStop() {
        super.onStop();
        progressHandler.removeCallbacks(progressTicker);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (activity != null) {
            bindStaticDeviceInfo();
            renderState();
        }
    }

    private void bindStaticDeviceInfo() {
        versionText.setText(resolveVersion());
        originText.setText(firstNonEmpty(activity.getServerOrigin(), "Not saved yet"));
        installationIdText.setText(activity.getInstallationId());

        boolean secureStorageReady = activity.hasSecureElevationStorage();
        bindStatusValue(
            secureStorageText,
            secureStorageReady ? "Ready" : "Unavailable",
            secureStorageReady ? R.color.accent_green : R.color.accent_yellow
        );
    }

    private void bindActions() {
        notificationButton.setOnClickListener(view -> activity.openNotificationListenerSettings());
        sharedAccountButton.setOnClickListener(view -> activity.openSharedAccountSite());
        checkHelperUpdateButton.setOnClickListener(view -> loadHelperUpdate(true));
        downloadHelperUpdateButton.setOnClickListener(view -> {
            if (helperUpdateDownloadUrl.isEmpty()) {
                loadHelperUpdate(true);
                return;
            }

            boolean queued = activity.downloadApk(helperUpdateDownloadUrl, HELPER_APK);
            if (!queued) {
                Toast.makeText(
                    view.getContext(),
                    "Android could not start that helper download right now.",
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }
            renderState();
        });
        schedEditButton.setOnClickListener(view -> handleSchedEditClick());
        accountTokenVerifyButton.setOnClickListener(view -> handleVerifyAccountToken());
        accountTokenRemoveButton.setOnClickListener(view -> handleRemoveAccountToken());
        accountTokenToggleButton.setOnClickListener(view -> {
            accountTokenExpanded = !accountTokenExpanded;
            bindAccountTokenState();
        });
        openElevationButton.setOnClickListener(view -> activity.openElevationAuth());
        toggleElevationLastFmButton.setOnClickListener(view -> activity.toggleElevationLastFm());
        removeElevationButton.setOnClickListener(view -> activity.clearElevationAccess());
    }

    private void handleSchedEditClick() {
        boolean opened = activity.openApplet(
            "schededit",
            "SchedEdit",
            "/mobile-app?applet=schededit"
        );
        if (!opened) {
            Toast.makeText(
                requireContext(),
                "SchedEdit could not open right now.",
                Toast.LENGTH_SHORT
            ).show();
        }
    }

    private void loadHelperUpdate(boolean manualRefresh) {
        if (helperUpdateChecking) {
            return;
        }

        helperUpdateChecking = true;
        helperUpdateError = "";
        if (manualRefresh) {
            helperUpdateDownloadUrl = "";
            helperUpdateVersion = "";
        }
        renderState();

        new Thread(() -> {
            String latestVersion = "";
            String downloadUrl = "";
            String errorMessage = "";

            try {
                HelperReleaseManager.ReleaseAssetInfo asset =
                    HelperReleaseManager.fetchLatestHelperReleaseAsset();
                String currentVersion = resolveVersion();
                latestVersion = asset.getReleaseVersion();
                if (HelperReleaseManager.compareVersions(latestVersion, currentVersion) > 0) {
                    downloadUrl = asset.downloadUrl;
                }
            } catch (Exception error) {
                errorMessage =
                    error.getMessage() == null || error.getMessage().trim().isEmpty()
                        ? "Could not check the latest Maru release."
                        : error.getMessage().trim();
            }

            final String latestVersionFinal = latestVersion;
            final String downloadUrlFinal = downloadUrl;
            final String errorMessageFinal = errorMessage;
            if (!isAdded()) {
                return;
            }

            requireActivity().runOnUiThread(() -> {
                helperUpdateChecking = false;
                helperUpdateVersion = latestVersionFinal;
                helperUpdateDownloadUrl = downloadUrlFinal;
                helperUpdateError = errorMessageFinal;
                renderState();
            });
        }).start();
    }

    private void renderState() {
        bindHelperUpdateState();
        bindSharedAccountState();
        bindAccountTokenState();
        bindStemState();
        bindSchedEditState();
        bindElevationState();
    }

    private void bindHelperUpdateState() {
        String currentVersion = resolveVersion();
        HelperApkDownloadTracker.DownloadSnapshot downloadSnapshot =
            activity.getApkDownloadSnapshot(HELPER_APK);
        int publishedComparison = helperUpdateVersion.isEmpty()
            ? 0
            : HelperReleaseManager.compareVersions(helperUpdateVersion, currentVersion);
        bindProgress(helperUpdateProgressBar, helperUpdateProgressText, downloadSnapshot);
        if (downloadSnapshot.active) {
            bindStatusValue(
                helperUpdateStatusText,
                "Downloading " + currentVersion + " update...",
                R.color.accent_yellow
            );
            helperUpdateNoteText.setText(
                helperUpdateVersion.isEmpty()
                    ? "Android is downloading the Maru APK."
                    : "Install this download to move Maru to " + helperUpdateVersion + "."
            );
        } else if (downloadSnapshot.successful) {
            bindStatusValue(
                helperUpdateStatusText,
                "Ready to install",
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                helperUpdateVersion.isEmpty()
                    ? "The APK has finished downloading."
                    : "Finish Android's install prompt to apply Maru " + helperUpdateVersion + "."
            );
        } else if (downloadSnapshot.failed) {
            bindStatusValue(
                helperUpdateStatusText,
                "Download failed",
                R.color.red_status
            );
            helperUpdateNoteText.setText(
                firstNonEmpty(
                    downloadSnapshot.note,
                    "Try the Maru download again."
                )
            );
        } else if (helperUpdateChecking) {
            bindStatusValue(
                helperUpdateStatusText,
                "Checking GitHub release...",
                R.color.accent_yellow
            );
            helperUpdateNoteText.setText("Looking for a newer Maru APK on the mobile repo.");
        } else if (!helperUpdateError.isEmpty()) {
            bindStatusValue(
                helperUpdateStatusText,
                "Could not check right now",
                R.color.accent_yellow
            );
            helperUpdateNoteText.setText(helperUpdateError);
        } else if (!helperUpdateDownloadUrl.isEmpty()) {
            bindStatusValue(
                helperUpdateStatusText,
                "Update ready: " + helperUpdateVersion,
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                "This phone is on " + currentVersion + ". A newer Maru APK is ready in the mobile repo."
            );
        } else if (!helperUpdateVersion.isEmpty() && publishedComparison == 0) {
            bindStatusValue(
                helperUpdateStatusText,
                "Up to date",
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                "Maru already matches the latest mobile repo release (" +
                    helperUpdateVersion +
                    ")."
            );
        } else if (!helperUpdateVersion.isEmpty()) {
            bindStatusValue(
                helperUpdateStatusText,
                "Local build is newer",
                R.color.accent_green
            );
            helperUpdateNoteText.setText(
                "This phone is on " + currentVersion +
                    ", which is newer than the latest published mobile repo release (" +
                    helperUpdateVersion +
                    ")."
            );
        } else {
            bindStatusValue(
                helperUpdateStatusText,
                "Check for updates",
                R.color.text_secondary
            );
            helperUpdateNoteText.setText(
                "Check the mobile repo for a newer Maru APK."
            );
        }

        boolean helperDownloadBusy = downloadSnapshot.visible && !downloadSnapshot.failed;
        checkHelperUpdateButton.setText(helperUpdateChecking ? "Checking..." : "Check For Update");
        checkHelperUpdateButton.setEnabled(!helperUpdateChecking && !helperDownloadBusy);
        downloadHelperUpdateButton.setVisibility(
            helperUpdateDownloadUrl.isEmpty() && !downloadSnapshot.visible ? View.GONE : View.VISIBLE
        );
        if (downloadSnapshot.active) {
            downloadHelperUpdateButton.setText("Downloading...");
            downloadHelperUpdateButton.setEnabled(false);
        } else if (downloadSnapshot.successful) {
            downloadHelperUpdateButton.setText("Finish In Android Installer");
            downloadHelperUpdateButton.setEnabled(false);
        } else if (downloadSnapshot.failed) {
            downloadHelperUpdateButton.setText("Retry Download");
            downloadHelperUpdateButton.setEnabled(!helperUpdateDownloadUrl.isEmpty());
        } else {
            downloadHelperUpdateButton.setText("Download Update");
            downloadHelperUpdateButton.setEnabled(
                !helperUpdateChecking && !helperUpdateDownloadUrl.isEmpty()
            );
        }
    }

    private void bindSharedAccountState() {
        JSONObject sharedAccount = parseJsonObject(activity.getSharedAuthUserJson());
        String sharedFullName = optText(sharedAccount, "fullName");
        String sharedEmail = optText(sharedAccount, "email");
        boolean hasSharedAccount = !sharedFullName.isEmpty() || !sharedEmail.isEmpty();

        bindStatusValue(
            sharedAccountStatusText,
            hasSharedAccount ? "Website account ready" : "Website sign-in needed",
            hasSharedAccount ? R.color.accent_green : R.color.text_secondary
        );
        sharedAccountNoteText.setText(
            hasSharedAccount
                ? firstNonEmpty(sharedFullName, sharedEmail) + (
                    !sharedFullName.isEmpty() && !sharedEmail.isEmpty()
                        ? "  |  " + sharedEmail
                        : ""
                )
                : "Sign in from the website's Account Options. Maru mirrors the result here."
        );
    }

    private void bindAccountTokenState() {
        String storedToken = HelperStorage.getAccountToken(requireContext());
        String storedAuthUser = HelperStorage.getSharedAuthUser(requireContext());
        JSONObject authUser = parseJsonObject(storedAuthUser);
        String userName = optText(authUser, "fullName");
        String userEmail = optText(authUser, "email");
        boolean hasToken = !storedToken.isEmpty();
        boolean hasUser = !userName.isEmpty() || !userEmail.isEmpty();
        boolean hasError = !accountTokenError.isEmpty();

        bindStatusValue(
            accountTokenStatusText,
            hasToken && hasUser
                ? "Ready for app sync"
                : hasToken
                    ? "Token saved"
                    : "No token saved",
            hasToken ? R.color.accent_green : R.color.text_secondary
        );

        if (hasError) {
            accountTokenNoteText.setText(accountTokenError);
            accountTokenNoteText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.red_status)
            );
        } else if (!accountTokenMessage.isEmpty()) {
            accountTokenNoteText.setText(accountTokenMessage);
            accountTokenNoteText.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.accent_green)
            );
        } else if (hasUser) {
            String label = userName;
            if (!userEmail.isEmpty()) {
                label += "  |  " + userEmail;
            }
            accountTokenNoteText.setText(label);
        } else if (hasToken) {
            accountTokenNoteText.setText("The token is saved. Apps can use it for account sync.");
        } else {
            accountTokenNoteText.setText(
                "Copy the token from the website account panel, then paste it here."
            );
        }

        accountTokenToggleButton.setVisibility(hasToken ? View.GONE : View.VISIBLE);
        accountTokenToggleButton.setText(accountTokenExpanded ? "Hide Token Field" : "Paste Token");
        accountTokenToggleButton.setEnabled(!accountTokenVerifying);
        accountTokenInput.setVisibility(accountTokenExpanded && !hasToken ? View.VISIBLE : View.GONE);
        accountTokenVerifyButton.setVisibility(accountTokenExpanded && !hasToken ? View.VISIBLE : View.GONE);
        accountTokenRemoveButton.setVisibility(hasToken ? View.VISIBLE : View.GONE);

        accountTokenVerifyButton.setText(accountTokenVerifying ? "Verifying..." : "Verify Token");
        accountTokenVerifyButton.setEnabled(!accountTokenVerifying);
        accountTokenRemoveButton.setEnabled(!accountTokenVerifying);
    }

    private void handleVerifyAccountToken() {
        if (accountTokenVerifying || activity == null) return;

        String rawToken = accountTokenInput.getText().toString().trim();
        if (rawToken.isEmpty()) {
            accountTokenError = "Paste your account token first.";
            accountTokenMessage = "";
            bindAccountTokenState();
            return;
        }

        accountTokenVerifying = true;
        accountTokenError = "";
        accountTokenMessage = "Verifying token...";
        bindAccountTokenState();

        new Thread(() -> {
            JSONObject response = activity.verifyAccountToken(rawToken);
            boolean success = response.optBoolean("success", false);

            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                accountTokenVerifying = false;
                if (success) {
                    String userId = optText(response, "userId");
                    String email = optText(response, "email");
                    String fullName = optText(response, "fullName");
                    try {
                        JSONObject authUser = new JSONObject();
                        authUser.put("userId", userId);
                        authUser.put("email", email);
                        authUser.put("fullName", fullName);
                        HelperStorage.persistSharedAuthUser(requireContext(), authUser.toString());
                    } catch (Exception ignored) {}

                    HelperStorage.persistAccountToken(requireContext(), rawToken);
                    accountTokenExpanded = false;
                    accountTokenMessage = "Token verified for " + firstNonEmpty(fullName, email) + ".";
                    accountTokenError = "";
                    accountTokenInput.setText("");
                } else {
                    String error = optText(response, "error");
                    accountTokenError = error.isEmpty()
                        ? "Token verification failed. Make sure it's the full token from the website."
                        : error;
                    accountTokenMessage = "";
                }
                bindAccountTokenState();
            });
        }).start();
    }

    private void handleRemoveAccountToken() {
        if (activity == null) return;
        HelperStorage.persistAccountToken(requireContext(), "");
        HelperStorage.persistSharedAuthUser(requireContext(), "");
        accountTokenMessage = "Account token removed.";
        accountTokenError = "";
        accountTokenExpanded = false;
        bindAccountTokenState();
    }

    private void bindStemState() {
        JSONObject stemState = parseJsonObject(activity.getStemModelStateJson());
        boolean stemInstalled = stemState.optBoolean("installed", false);
        bindStatusValue(
            stemStatusText,
            firstNonEmpty(
                optText(stemState, "label"),
                stemInstalled ? "Installed" : "Unavailable"
            ),
            stemInstalled ? R.color.accent_green : R.color.text_secondary
        );
        stemNoteText.setText(
            firstNonEmpty(
                optText(stemState, "note"),
                "Marucast checks whether the local karaoke model is ready."
            )
        );
    }

    private void bindSchedEditState() {
        schedEditButton.setText("Open SchedEdit");
        schedEditButton.setEnabled(true);
        schedEditNoteText.setText(
            "SchedEdit now opens as a Maru applet instead of a separate app."
        );
    }

    private void bindElevationState() {
        boolean secureStorageReady = activity.hasSecureElevationStorage();
        boolean hasElevationAccess = activity.hasElevationAccess();
        boolean elevationBusy = activity.isElevationBusy();
        boolean lastFmEnabled = activity.isElevationLastFmEnabled();
        String elevationError = activity.getElevationStatusError();
        String elevationMessage = activity.getElevationStatusMessage();

        String elevationStatusLabel;
        int elevationStatusColor;
        if (!secureStorageReady) {
            elevationStatusLabel = "Encrypted storage needed";
            elevationStatusColor = R.color.accent_yellow;
        } else if (elevationBusy) {
            elevationStatusLabel = "Finishing in Maru";
            elevationStatusColor = R.color.accent_yellow;
        } else if (hasElevationAccess && lastFmEnabled) {
            elevationStatusLabel = "Last.fm alerts on";
            elevationStatusColor = R.color.accent_green;
        } else if (hasElevationAccess) {
            elevationStatusLabel = "Ready on this phone";
            elevationStatusColor = R.color.accent_green;
        } else {
            elevationStatusLabel = "Site PIN needed";
            elevationStatusColor = R.color.accent_yellow;
        }
        bindStatusValue(elevationStatusText, elevationStatusLabel, elevationStatusColor);

        elevationNoteText.setText(
            !secureStorageReady
                ? "This phone could not open encrypted helper storage, so elevated settings stay locked."
                : hasElevationAccess
                ? (
                        lastFmEnabled
                            ? "Site Admin access is ready here, and Last.fm alerts can mirror to this phone."
                            : "Site Admin access is ready here. Turn on Last.fm alerts if you want scrobble mirroring."
                    )
                    : "Open Elevation on the website, enter the Site Admin PIN there, then let Maru catch the handoff back here."
        );

        bindMessage(
            elevationMessageText,
            !elevationError.isEmpty() ? elevationError : elevationMessage,
            !elevationError.isEmpty()
        );

        openElevationButton.setText(hasElevationAccess ? "Reopen Elevation" : "Open Elevation");
        openElevationButton.setEnabled(secureStorageReady && !elevationBusy);

        toggleElevationLastFmButton.setText(
            hasElevationAccess
                ? lastFmEnabled
                    ? "Turn Last.fm Alerts Off"
                    : "Turn Last.fm Alerts On"
                : "Last.fm Alerts Need Elevation"
        );
        toggleElevationLastFmButton.setEnabled(
            secureStorageReady && hasElevationAccess && !elevationBusy
        );

        removeElevationButton.setVisibility(hasElevationAccess ? View.VISIBLE : View.GONE);
        removeElevationButton.setEnabled(!elevationBusy);
    }

    private String resolveVersion() {
        return BuildConfig.VERSION_NAME == null || BuildConfig.VERSION_NAME.trim().isEmpty()
            ? "Unknown"
            : BuildConfig.VERSION_NAME.trim();
    }

    private JSONObject parseJsonObject(String rawJson) {
        try {
            String json = rawJson == null ? "" : rawJson.trim();
            return json.isEmpty() ? new JSONObject() : new JSONObject(json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String optText(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.isEmpty()) {
            return "";
        }
        String normalized = value.toLowerCase();
        return "null".equals(normalized) || "undefined".equals(normalized) ? "" : value;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private void bindStatusValue(TextView view, String text, int colorResId) {
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(requireContext(), colorResId));
    }

    private void bindMessage(TextView view, String text, boolean isError) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            view.setVisibility(View.GONE);
            view.setText("");
            return;
        }

        view.setVisibility(View.VISIBLE);
        view.setText(value);
        view.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                isError ? R.color.red_status : R.color.text_secondary
            )
        );
    }

    private void bindProgress(
        ProgressBar progressBar,
        TextView progressText,
        HelperApkDownloadTracker.DownloadSnapshot snapshot
    ) {
        if (progressBar == null || progressText == null) {
            return;
        }

        if (snapshot == null || !snapshot.visible) {
            progressBar.setVisibility(View.GONE);
            progressText.setVisibility(View.GONE);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
            progressText.setText("");
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(snapshot.indeterminate);
        if (!snapshot.indeterminate) {
            progressBar.setProgress(snapshot.progressPercent);
        }
        progressText.setText(firstNonEmpty(snapshot.label, snapshot.note));
        progressText.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                snapshot.failed ? R.color.red_status : R.color.text_secondary
            )
        );
    }
}
