package app.shengdu.audio;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import app.shengdu.audio.metadata.MediaSessionListenerService;

/** Settings surface for user-configurable playback and permission options. */
public final class SettingsActivity extends Activity {
    public static final String EXTRA_RECEIVER_SAMPLE_RATES = "receiver_sample_rates";
    private AppPreferences preferences;
    private Button notificationAccessButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_settings);
        SystemBarInsets.applyPadding(findViewById(R.id.settings_root));

        preferences = new AppPreferences(this);

        ImageButton backButton = findViewById(R.id.button_back);
        Switch muteSwitch = findViewById(R.id.switch_mute_device);
        TextView qualityValue = findViewById(R.id.text_audio_quality_value);
        RadioGroup qualityOptions = findViewById(R.id.audio_quality_options);
        RadioButton autoOption = findViewById(R.id.audio_quality_auto);
        RadioButton rate44Option = findViewById(R.id.audio_quality_44);
        RadioButton rate48Option = findViewById(R.id.audio_quality_48);
        boolean supports48 = supportsRate(getIntent().getStringExtra(EXTRA_RECEIVER_SAMPLE_RATES), 48_000);
        rate48Option.setVisibility(supports48 ? android.view.View.VISIBLE : android.view.View.GONE);
        notificationAccessButton = findViewById(R.id.button_notification_access);

        backButton.setOnClickListener(view -> finish());
        muteSwitch.setChecked(preferences.isMuteDeviceEnabled());
        muteSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> preferences.setMuteDeviceEnabled(isChecked));
        AppPreferences.AudioQuality initialQuality = preferences.getAudioQuality();
        if (initialQuality == AppPreferences.AudioQuality.PCM16_48 && !supports48) {
            initialQuality = AppPreferences.AudioQuality.AUTO;
            preferences.setAudioQuality(initialQuality);
        }
        setQualitySelection(initialQuality, qualityValue, autoOption, rate44Option, rate48Option);
        qualityValue.setOnClickListener(view -> {
            setQualityOptionsExpanded(qualityOptions,
                    qualityOptions.getVisibility() != android.view.View.VISIBLE);
        });
        qualityOptions.setOnCheckedChangeListener((group, checkedId) -> {
            AppPreferences.AudioQuality selected;
            if (checkedId == R.id.audio_quality_48) {
                selected = AppPreferences.AudioQuality.PCM16_48;
            } else if (checkedId == R.id.audio_quality_44) {
                selected = AppPreferences.AudioQuality.PCM16_44_1;
            } else {
                selected = AppPreferences.AudioQuality.AUTO;
            }
            preferences.setAudioQuality(selected);
            setQualitySelection(selected, qualityValue, autoOption, rate44Option, rate48Option);
            setQualityOptionsExpanded(qualityOptions, false);
        });
        notificationAccessButton.setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.button_about).setOnClickListener(
                view -> startActivity(new Intent(this, AboutActivity.class)));
    }

    private static void setQualitySelection(
            AppPreferences.AudioQuality quality,
            TextView value,
            RadioButton auto,
            RadioButton rate44,
            RadioButton rate48) {
        auto.setChecked(quality == AppPreferences.AudioQuality.AUTO);
        rate44.setChecked(quality == AppPreferences.AudioQuality.PCM16_44_1);
        rate48.setChecked(quality == AppPreferences.AudioQuality.PCM16_48);
        value.setText(quality == AppPreferences.AudioQuality.PCM16_44_1
                ? R.string.audio_quality_44
                : quality == AppPreferences.AudioQuality.PCM16_48
                ? R.string.audio_quality_48
                : R.string.audio_quality_auto);
    }

    private static void setQualityOptionsExpanded(android.view.View options, boolean expanded) {
        options.animate().cancel();
        if (expanded) {
            options.setAlpha(0f);
            options.setTranslationY(-8f);
            options.setVisibility(android.view.View.VISIBLE);
            options.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .start();
        } else if (options.getVisibility() == android.view.View.VISIBLE) {
            options.animate()
                    .alpha(0f)
                    .translationY(-8f)
                    .setDuration(140L)
                    .withEndAction(() -> {
                        options.setVisibility(android.view.View.GONE);
                        options.setAlpha(1f);
                        options.setTranslationY(0f);
                    })
                    .start();
        }
    }

    private static boolean supportsRate(String value, int expectedHz) {
        if (value == null) {
            return false;
        }
        for (String token : value.split("[,\\s/]+")) {
            try {
                if (Integer.parseInt(token.trim()) == expectedHz) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Ignore unknown TXT tokens.
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        notificationAccessButton.setText(isNotificationListenerGranted()
                ? R.string.settings_notification_access_on
                : R.string.settings_notification_access_off);
    }

    private boolean isNotificationListenerGranted() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        ComponentName component = new ComponentName(this, MediaSessionListenerService.class);
        String flattened = component.flattenToString();
        String shortFlattened = component.flattenToShortString();
        for (String entry : enabled.split(":")) {
            if (entry.equalsIgnoreCase(flattened) || entry.equalsIgnoreCase(shortFlattened)) {
                return true;
            }
        }
        return false;
    }
}
