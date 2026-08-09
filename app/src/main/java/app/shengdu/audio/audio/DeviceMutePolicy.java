package app.shengdu.audio.audio;

/**
 * Decides when Shengdu may mute the device's music stream during casting and when it
 * may restore the previous volume afterwards. Pure Java so the policy is unit testable;
 * the Android side only performs the actual AudioManager calls.
 *
 * Rules:
 * - Only mute when the user enabled the option and the stream is currently audible.
 *   Muting an already-silent stream would create a bogus "restore to 0" obligation.
 * - On stop, only restore when the stream is still at the muted level we set (0). If the
 *   user picked a new volume during casting, their choice wins and nothing is restored.
 */
public final class DeviceMutePolicy {
    public static final int NO_CHANGE = -1;

    private boolean applied;
    private int savedVolume;

    /** Returns the volume to set (0) when muting should happen, or {@link #NO_CHANGE}. */
    public int onStreamingStarted(boolean enabled, int currentVolume) {
        applied = false;
        if (!enabled || currentVolume <= 0) {
            return NO_CHANGE;
        }
        savedVolume = currentVolume;
        applied = true;
        return 0;
    }

    /** Returns the volume to restore, or {@link #NO_CHANGE} to leave the user's choice. */
    public int onStreamingStopped(int currentVolume) {
        if (!applied) {
            return NO_CHANGE;
        }
        applied = false;
        return currentVolume == 0 ? savedVolume : NO_CHANGE;
    }
}
