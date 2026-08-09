package app.shengdu.audio;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Switch;

import app.shengdu.audio.metadata.MediaSessionListenerService;

/** Settings surface for user-configurable playback and permission options. */
public final class SettingsActivity extends Activity {
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
        notificationAccessButton = findViewById(R.id.button_notification_access);

        backButton.setOnClickListener(view -> finish());
        muteSwitch.setChecked(preferences.isMuteDeviceEnabled());
        muteSwitch.setOnCheckedChangeListener(
                (buttonView, isChecked) -> preferences.setMuteDeviceEnabled(isChecked));
        notificationAccessButton.setOnClickListener(
                view -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
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
