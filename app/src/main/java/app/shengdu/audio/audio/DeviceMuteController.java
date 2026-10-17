package app.shengdu.audio.audio;

/** Applies and verifies a session's local mute without taking over the user's volume changes. */
public final class DeviceMuteController {
    public interface VolumeControl {
        int getVolume();

        boolean isMuted();

        boolean isFixedVolume();

        void setVolume(int index);

        void setMuted(boolean muted);
    }

    private DeviceMutePolicy volumeRestore;
    private boolean ownsMute;
    private boolean unmuteAttempted;

    public synchronized boolean mute(VolumeControl volume) {
        if (volumeRestore != null || ownsMute) {
            return isSilent(volume);
        }

        final int originalVolume;
        try {
            originalVolume = volume.getVolume();
            if (originalVolume <= 0 || volume.isMuted()) {
                return true;
            }
            if (volume.isFixedVolume()) {
                return false;
            }
        } catch (RuntimeException ignored) {
            return false;
        }

        DeviceMutePolicy attemptedRestore = new DeviceMutePolicy();
        attemptedRestore.onStreamingStarted(true, originalVolume);
        // Keep cleanup possible even if an OEM changes the stream and then throws.
        volumeRestore = attemptedRestore;
        try {
            volume.setVolume(0);
        } catch (RuntimeException ignored) {
            // Some devices reject index changes but still accept explicit muting.
        }

        int afterZero = readVolume(volume);
        Boolean mutedAfterZero = readMuted(volume);
        if (afterZero > 0) {
            volumeRestore = null;
        }
        if (Boolean.TRUE.equals(mutedAfterZero)) {
            ownsMute = true;
            return true;
        }
        if (afterZero == 0) {
            return true;
        }

        ownsMute = true;
        try {
            volume.setMuted(true);
        } catch (RuntimeException ignored) {
            // Read back before deciding whether this attempt created a cleanup obligation.
        }
        Boolean muted = readMuted(volume);
        int currentVolume = readVolume(volume);
        if (Boolean.FALSE.equals(muted)) {
            ownsMute = false;
            if (currentVolume > 0) {
                volumeRestore = null;
            } else if (currentVolume == 0) {
                volumeRestore = attemptedRestore;
            }
        }
        return Boolean.TRUE.equals(muted) || currentVolume == 0;
    }

    public synchronized void restore(VolumeControl volume) {
        // Cleanup may be the service's final call. Retry one transient failure here,
        // under the same lifecycle lock, rather than scheduling work after the session ends.
        for (int attempt = 0; attempt < 2 && (ownsMute || volumeRestore != null); attempt++) {
            restoreOnce(volume);
        }
    }

    private void restoreOnce(VolumeControl volume) {
        if (ownsMute) {
            boolean alreadyUnmuted = Boolean.FALSE.equals(readMuted(volume));
            if (alreadyUnmuted && !unmuteAttempted) {
                // The user already cancelled our explicit mute; their choice wins.
                ownsMute = false;
                volumeRestore = null;
                return;
            }
            if (!alreadyUnmuted) {
                unmuteAttempted = true;
                try {
                    volume.setMuted(false);
                } catch (RuntimeException ignored) {
                    // The setter may have changed the stream before reporting failure.
                }
                if (!Boolean.FALSE.equals(readMuted(volume))) {
                    return;
                }
            }
            ownsMute = false;
            unmuteAttempted = false;
        }

        if (volumeRestore == null) {
            return;
        }
        int currentVolume = readVolume(volume);
        if (currentVolume < 0) {
            return;
        }
        int target = volumeRestore.onStreamingStopped(currentVolume);
        if (target == DeviceMutePolicy.NO_CHANGE) {
            volumeRestore = null;
            return;
        }
        // Keep the original target available for the bounded retry if restoration fails.
        volumeRestore.onStreamingStarted(true, target);
        try {
            volume.setVolume(target);
        } catch (RuntimeException ignored) {
            // Preserve cleanup state when the platform still reports zero or cannot be read.
        }
        if (readVolume(volume) > 0) {
            volumeRestore = null;
        }
    }

    private static boolean isSilent(VolumeControl volume) {
        return Boolean.TRUE.equals(readMuted(volume)) || readVolume(volume) == 0;
    }

    private static int readVolume(VolumeControl volume) {
        try {
            return volume.getVolume();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static Boolean readMuted(VolumeControl volume) {
        try {
            return volume.isMuted();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
