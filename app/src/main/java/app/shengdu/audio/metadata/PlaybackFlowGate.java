package app.shengdu.audio.metadata;

/**
 * Coordinates MediaSession play/pause state with the audio capture path.
 *
 * <p>Fail-open by default so audio keeps flowing before any session state is known.
 * Once playback has been observed, a paused/buffering snapshot or disappearance of that
 * session blocks PCM until a playing snapshot resumes the flow or the owner resets the gate.
 */
public final class PlaybackFlowGate {
    public enum Transition {
        NONE,
        PAUSED,
        RESUMED
    }

    private boolean pcmAllowed = true;
    private boolean stateKnown;
    private long revision;

    public synchronized Transition update(TrackMetadata track) {
        if (track == null) {
            // Some players remove their MediaSession instead of publishing STATE_PAUSED.
            // Stay fail-open until a real session has been observed, but once playback is
            // known, losing that session must freeze the receiver just like an explicit pause.
            if (!stateKnown || !pcmAllowed) {
                return Transition.NONE;
            }
            pcmAllowed = false;
            revision++;
            return Transition.PAUSED;
        }

        boolean nextAllowed = track.isPlaying();
        if (!stateKnown) {
            stateKnown = true;
            if (pcmAllowed != nextAllowed) {
                pcmAllowed = nextAllowed;
                revision++;
            }
            return nextAllowed ? Transition.NONE : Transition.PAUSED;
        }
        if (pcmAllowed == nextAllowed) {
            return Transition.NONE;
        }
        pcmAllowed = nextAllowed;
        revision++;
        return nextAllowed ? Transition.RESUMED : Transition.PAUSED;
    }

    public synchronized boolean shouldForwardPcm() {
        return pcmAllowed;
    }

    public synchronized void reset() {
        pcmAllowed = true;
        stateKnown = false;
        revision = 0L;
    }

    public synchronized long revision() {
        return revision;
    }
}
