package app.shengdu.audio;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper around persisted user-facing toggles. */
public final class AppPreferences {
    private static final String PREFS_NAME = "shengdu";
    private static final String PREF_MUTE_DEVICE = "mute_device";
    private static final String PREF_AUDIO_QUALITY = "audio_quality";

    private final SharedPreferences preferences;

    public AppPreferences(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isMuteDeviceEnabled() {
        return preferences.getBoolean(PREF_MUTE_DEVICE, true);
    }

    public void setMuteDeviceEnabled(boolean enabled) {
        preferences.edit().putBoolean(PREF_MUTE_DEVICE, enabled).apply();
    }

    public AudioQuality getAudioQuality() {
        String value = preferences.getString(PREF_AUDIO_QUALITY, AudioQuality.AUTO.name());
        try {
            return AudioQuality.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AudioQuality.AUTO;
        }
    }

    public void setAudioQuality(AudioQuality quality) {
        preferences.edit().putString(PREF_AUDIO_QUALITY, quality.name()).apply();
    }

    public enum AudioQuality {
        AUTO,
        PCM16_44_1,
        PCM16_48
    }
}
