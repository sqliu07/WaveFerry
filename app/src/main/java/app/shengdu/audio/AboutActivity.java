package app.shengdu.audio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import app.shengdu.audio.update.ReleaseUpdateChecker;

/** App information and an explicit, user-initiated release check. */
public final class AboutActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final ReleaseUpdateChecker updateChecker = new ReleaseUpdateChecker();
    private Button checkUpdatesButton;
    private Future<?> updateTask;
    private AlertDialog updateDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_about);
        SystemBarInsets.applyPadding(findViewById(R.id.about_root));

        findViewById(R.id.button_about_back).setOnClickListener(view -> finish());
        TextView version = findViewById(R.id.text_about_version);
        version.setText(getString(R.string.about_version,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        checkUpdatesButton = findViewById(R.id.button_check_updates);
        checkUpdatesButton.setOnClickListener(view -> checkForUpdates());
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
        if (!checkUpdatesButton.isEnabled()) {
            return;
        }
        checkUpdatesButton.setEnabled(false);
        checkUpdatesButton.setText(R.string.about_checking_updates);
        updateTask = updateExecutor.submit(() -> {
            try {
                ReleaseUpdateChecker.Result result = updateChecker.check(BuildConfig.VERSION_NAME);
                mainHandler.post(() -> showUpdateResult(result));
            } catch (IOException | SecurityException exception) {
                mainHandler.post(this::showUpdateFailure);
            }
        });
    }

    private void showUpdateResult(ReleaseUpdateChecker.Result result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        finishUpdateCheck();
        if (!result.isUpdateAvailable()) {
            Toast.makeText(this, R.string.about_no_updates, Toast.LENGTH_SHORT).show();
            return;
        }
        updateDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.about_update_title)
                .setMessage(getString(R.string.about_update_message, result.getVersionName()))
                .setPositiveButton(R.string.about_open_release,
                        (dialog, which) -> openExternalUrl(result.getReleaseUrl()))
                .setNegativeButton(R.string.about_later, null)
                .show();
    }

    private void showUpdateFailure() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        finishUpdateCheck();
        Toast.makeText(this, R.string.about_update_failed, Toast.LENGTH_LONG).show();
    }

    private void finishUpdateCheck() {
        checkUpdatesButton.setEnabled(true);
        checkUpdatesButton.setText(R.string.about_check_updates);
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
    protected void onDestroy() {
        if (updateTask != null) {
            updateTask.cancel(true);
        }
        updateChecker.close();
        updateExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (updateDialog != null) {
            updateDialog.dismiss();
        }
        super.onDestroy();
    }
}
