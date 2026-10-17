package app.shengdu.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.shengdu.audio.update.ApkUpdateDownload;
import app.shengdu.audio.update.ReleaseUpdateChecker;

/** User-initiated updates; downloads survive this activity and installation requires system consent. */
public final class AboutActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final ReleaseUpdateChecker updateChecker = new ReleaseUpdateChecker();
    private final Runnable downloadPoll = this::refreshDownload;
    private ApkUpdateDownload apkDownload;
    private Button checkUpdatesButton;
    private View downloadPanel;
    private TextView downloadStatus;
    private ProgressBar downloadProgress;
    private Button installButton;
    private Button cancelDownloadButton;
    private Button downloadBrowserButton;
    private AlertDialog updateDialog;
    private ApkUpdateDownload.State downloadState = ApkUpdateDownload.State.NONE;
    private boolean resumed;
    private boolean checkingUpdates;
    private boolean queryingDownload;
    private boolean changingDownload;
    private boolean autoInstallWhenReady;
    private boolean waitingForInstallPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_about);
        SystemBarInsets.applyPadding(findViewById(R.id.about_root));
        if (savedInstanceState != null) {
            waitingForInstallPermission = savedInstanceState.getBoolean("waitingForInstallPermission");
        }

        findViewById(R.id.button_about_back).setOnClickListener(view -> finish());
        TextView version = findViewById(R.id.text_about_version);
        version.setText(getString(R.string.about_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        downloadPanel = findViewById(R.id.about_download_panel);
        downloadStatus = findViewById(R.id.text_update_download);
        downloadProgress = findViewById(R.id.progress_update_download);
        installButton = findViewById(R.id.button_install_update);
        cancelDownloadButton = findViewById(R.id.button_cancel_update);
        downloadBrowserButton = findViewById(R.id.button_download_browser);
        installButton.setOnClickListener(view -> prepareInstallation());
        cancelDownloadButton.setOnClickListener(view -> cancelDownload());
        downloadBrowserButton.setOnClickListener(
                view -> openExternalUrl(getString(R.string.latest_release_url)));

        checkUpdatesButton = findViewById(R.id.button_check_updates);
        checkUpdatesButton.setOnClickListener(view -> {
            if (BuildConfig.EXTERNAL_UPDATES_ENABLED) {
                checkForUpdates();
            } else {
                openPlayStore();
            }
        });
        if (BuildConfig.EXTERNAL_UPDATES_ENABLED) {
            apkDownload = new ApkUpdateDownload(getApplicationContext());
        }
        findViewById(R.id.button_about_project).setOnClickListener(
                view -> openExternalUrl(getString(R.string.project_url)));
        findViewById(R.id.button_about_privacy).setOnClickListener(
                view -> openExternalUrl(getString(R.string.privacy_url)));

        Button supportButton = findViewById(R.id.button_about_support);
        String supportUrl = getString(R.string.support_url).trim();
        if (isHttpsUrl(supportUrl)) {
            supportButton.setVisibility(View.VISIBLE);
            supportButton.setOnClickListener(view -> openExternalUrl(supportUrl));
        }
    }

    private void checkForUpdates() {
        if (!checkUpdatesButton.isEnabled() || checkingUpdates || changingDownload) {
            return;
        }
        checkingUpdates = true;
        updateCheckButton();
        updateExecutor.submit(() -> {
            try {
                ReleaseUpdateChecker.Result result = updateChecker.check(BuildConfig.VERSION_NAME);
                mainHandler.post(() -> showUpdateResult(result));
            } catch (IOException | SecurityException exception) {
                int message = R.string.about_update_failed;
                if (exception instanceof ReleaseUpdateChecker.HttpException) {
                    int statusCode = ((ReleaseUpdateChecker.HttpException) exception).getStatusCode();
                    if (statusCode == 403 || statusCode == 429) {
                        message = R.string.about_update_access_limited;
                    }
                } else if (exception instanceof SocketTimeoutException) {
                    message = R.string.about_update_timeout;
                }
                int failureMessage = message;
                mainHandler.post(() -> {
                    checkingUpdates = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    updateCheckButton();
                    showFailure(R.string.about_update_failed_title, failureMessage);
                });
            }
        });
    }

    private void showUpdateResult(ReleaseUpdateChecker.Result result) {
        checkingUpdates = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        updateCheckButton();
        if (!result.isUpdateAvailable()) {
            Toast.makeText(this, R.string.about_no_updates, Toast.LENGTH_SHORT).show();
            return;
        }
        dismissUpdateDialog();
        updateDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.about_update_title)
                .setMessage(getString(R.string.about_update_message, result.getVersionName()))
                .setPositiveButton(R.string.about_download_update,
                        (dialog, which) -> startDownload(result))
                .setNeutralButton(R.string.about_open_release,
                        (dialog, which) -> openExternalUrl(result.getReleaseUrl()))
                .setNegativeButton(R.string.about_later, null)
                .show();
    }

    private void startDownload(ReleaseUpdateChecker.Result result) {
        if (changingDownload || apkDownload == null) {
            return;
        }
        changingDownload = true;
        autoInstallWhenReady = true;
        updateCheckButton();
        updateExecutor.submit(() -> {
            try {
                apkDownload.start(result);
                mainHandler.post(this::finishDownloadChange);
            } catch (IOException | SecurityException exception) {
                mainHandler.post(() -> downloadChangeFailed(R.string.about_download_unavailable));
            }
        });
    }

    private void refreshDownload() {
        if (!resumed || apkDownload == null || queryingDownload || changingDownload) {
            return;
        }
        queryingDownload = true;
        updateExecutor.submit(() -> {
            try {
                ApkUpdateDownload.Snapshot snapshot = apkDownload.query();
                mainHandler.post(() -> {
                    queryingDownload = false;
                    if (isFinishing() || isDestroyed() || changingDownload) {
                        return;
                    }
                    renderDownload(snapshot);
                    if (resumed && autoInstallWhenReady
                            && snapshot.getState() == ApkUpdateDownload.State.READY) {
                        autoInstallWhenReady = false;
                        prepareInstallation();
                    } else if (resumed && (snapshot.getState() == ApkUpdateDownload.State.DOWNLOADING
                            || snapshot.getState() == ApkUpdateDownload.State.WAITING)) {
                        mainHandler.removeCallbacks(downloadPoll);
                        mainHandler.postDelayed(downloadPoll, 1_000);
                    }
                });
            } catch (IOException | SecurityException exception) {
                mainHandler.post(() -> {
                    queryingDownload = false;
                    autoInstallWhenReady = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    downloadState = ApkUpdateDownload.State.FAILED;
                    downloadPanel.setVisibility(View.VISIBLE);
                    downloadStatus.setText(R.string.about_download_unavailable);
                    downloadProgress.setVisibility(View.GONE);
                    installButton.setVisibility(View.GONE);
                    cancelDownloadButton.setVisibility(View.VISIBLE);
                    downloadBrowserButton.setVisibility(View.VISIBLE);
                    updateCheckButton();
                });
            }
        });
    }

    private void renderDownload(ApkUpdateDownload.Snapshot snapshot) {
        downloadState = snapshot.getState();
        boolean active = downloadState == ApkUpdateDownload.State.DOWNLOADING
                || downloadState == ApkUpdateDownload.State.WAITING;
        boolean ready = downloadState == ApkUpdateDownload.State.READY;
        downloadPanel.setVisibility(downloadState == ApkUpdateDownload.State.NONE ? View.GONE : View.VISIBLE);
        downloadProgress.setVisibility(active ? View.VISIBLE : View.GONE);
        installButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        installButton.setEnabled(!changingDownload);
        cancelDownloadButton.setVisibility(View.VISIBLE);
        cancelDownloadButton.setEnabled(!changingDownload);
        cancelDownloadButton.setText(active ? R.string.about_cancel_download : R.string.about_delete_download);
        downloadBrowserButton.setVisibility(downloadState == ApkUpdateDownload.State.FAILED ? View.VISIBLE : View.GONE);
        if (downloadState == ApkUpdateDownload.State.DOWNLOADING) {
            long total = snapshot.getTotalBytes();
            long downloaded = snapshot.getDownloadedBytes();
            boolean knownSize = total > 0;
            downloadProgress.setIndeterminate(!knownSize);
            int percent = knownSize ? (int) Math.min(100, Math.max(0, downloaded * 100.0 / total)) : 0;
            downloadProgress.setProgress(percent);
            downloadStatus.setText(knownSize
                    ? getString(R.string.about_downloading_progress, snapshot.getVersionName(), percent)
                    : getString(R.string.about_downloading_update, snapshot.getVersionName()));
        } else if (downloadState == ApkUpdateDownload.State.WAITING) {
            downloadProgress.setIndeterminate(true);
            downloadStatus.setText(R.string.about_download_waiting);
        } else if (ready) {
            downloadStatus.setText(getString(R.string.about_download_ready, snapshot.getVersionName()));
        } else if (downloadState == ApkUpdateDownload.State.FAILED) {
            autoInstallWhenReady = false;
            downloadStatus.setText(R.string.about_download_failed);
        }
        updateCheckButton();
    }

    private void cancelDownload() {
        if (changingDownload || apkDownload == null) {
            return;
        }
        changingDownload = true;
        autoInstallWhenReady = false;
        waitingForInstallPermission = false;
        mainHandler.removeCallbacks(downloadPoll);
        updateCheckButton();
        installButton.setEnabled(false);
        cancelDownloadButton.setEnabled(false);
        updateExecutor.submit(() -> {
            try {
                apkDownload.cancel();
                mainHandler.post(this::finishDownloadChange);
            } catch (IOException | SecurityException exception) {
                mainHandler.post(() -> downloadChangeFailed(R.string.about_download_unavailable));
            }
        });
    }

    private void finishDownloadChange() {
        changingDownload = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        updateCheckButton();
        refreshDownload();
    }

    private void downloadChangeFailed(int message) {
        changingDownload = false;
        autoInstallWhenReady = false;
        if (isFinishing() || isDestroyed()) {
            return;
        }
        installButton.setEnabled(true);
        cancelDownloadButton.setEnabled(true);
        updateCheckButton();
        showFailure(R.string.about_download_failed_title, message);
        refreshDownload();
    }

    private void prepareInstallation() {
        if (changingDownload || apkDownload == null) {
            return;
        }
        changingDownload = true;
        autoInstallWhenReady = false;
        updateCheckButton();
        installButton.setEnabled(false);
        cancelDownloadButton.setEnabled(false);
        downloadStatus.setText(R.string.about_verifying_update);
        updateExecutor.submit(() -> {
            try {
                Intent installIntent = apkDownload.prepareInstall();
                mainHandler.post(() -> {
                    changingDownload = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    installButton.setEnabled(true);
                    cancelDownloadButton.setEnabled(true);
                    if (!resumed) {
                        return;
                    }
                    if (!getPackageManager().canRequestPackageInstalls()) {
                        requestInstallPermission();
                    } else {
                        try {
                            startActivity(installIntent);
                        } catch (ActivityNotFoundException | SecurityException exception) {
                            showFailure(R.string.about_download_failed_title, R.string.about_installer_unavailable);
                        }
                    }
                    refreshDownload();
                });
            } catch (IOException | SecurityException exception) {
                mainHandler.post(() -> downloadChangeFailed(R.string.about_update_verification_failed));
            }
        });
    }

    private void requestInstallPermission() {
        dismissUpdateDialog();
        updateDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.about_install_permission_title)
                .setMessage(R.string.about_install_permission_message)
                .setPositiveButton(R.string.about_open_install_settings, (dialog, which) -> {
                    try {
                        waitingForInstallPermission = true;
                        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException | SecurityException exception) {
                        waitingForInstallPermission = false;
                        showFailure(R.string.about_download_failed_title, R.string.about_installer_unavailable);
                    }
                })
                .setNegativeButton(R.string.about_later, null)
                .show();
    }

    private void showFailure(int title, int message) {
        dismissUpdateDialog();
        updateDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.about_open_release,
                        (dialog, which) -> openExternalUrl(getString(R.string.latest_release_url)))
                .setNegativeButton(R.string.about_later, null)
                .show();
    }

    private void updateCheckButton() {
        checkUpdatesButton.setEnabled(!checkingUpdates && !changingDownload
                && (downloadState == ApkUpdateDownload.State.NONE || downloadState == ApkUpdateDownload.State.FAILED));
        checkUpdatesButton.setText(checkingUpdates ? R.string.about_checking_updates : R.string.about_check_updates);
    }

    private void openPlayStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + getPackageName())));
        } catch (ActivityNotFoundException | SecurityException exception) {
            openExternalUrl("https://play.google.com/store/apps/details?id=" + getPackageName());
        }
    }

    private void openExternalUrl(String url) {
        if (!isHttpsUrl(url)) {
            Toast.makeText(this, R.string.about_open_link_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.about_open_link_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean isHttpsUrl(String url) {
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isEmpty();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (apkDownload != null && waitingForInstallPermission) {
            waitingForInstallPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) {
                prepareInstallation();
                return;
            }
            Toast.makeText(this, R.string.about_install_permission_denied, Toast.LENGTH_LONG).show();
        }
        refreshDownload();
    }

    @Override
    protected void onPause() {
        resumed = false;
        autoInstallWhenReady = false;
        mainHandler.removeCallbacks(downloadPoll);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("waitingForInstallPermission", waitingForInstallPermission);
        super.onSaveInstanceState(outState);
    }

    private void dismissUpdateDialog() {
        if (updateDialog != null) {
            updateDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        updateChecker.close();
        updateExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        dismissUpdateDialog();
        super.onDestroy();
    }
}
