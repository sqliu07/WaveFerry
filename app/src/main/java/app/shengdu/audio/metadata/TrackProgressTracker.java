package app.shengdu.audio.metadata;

/**
 * Chooses the MediaSession position used for receiver progress metadata.
 *
 * <p>Some players publish the next track's metadata before resetting their playback
 * position. A receiver would otherwise combine the new title with the previous track's
 * position and keep counting from there. The first track keeps its real position so a
 * stream started mid-song remains accurate; every later track starts at zero until that
 * reset has actually been sent.</p>
 */
public final class TrackProgressTracker {
    private TrackMetadata currentTrack;
    private boolean resetPending;

    public synchronized long positionMsFor(TrackMetadata track) {
        if (track == null) {
            return 0;
        }
        if (track.dedupKey().isEmpty()) {
            return track.getPositionMs();
        }
        if (currentTrack != null && !currentTrack.sameTrack(track)) {
            resetPending = true;
        }
        currentTrack = track;
        return resetPending ? 0 : track.getPositionMs();
    }

    public synchronized void onProgressSent(TrackMetadata track) {
        if (track != null
                && track.isPlaying()
                && currentTrack != null
                && currentTrack.sameTrack(track)) {
            resetPending = false;
        }
    }

    public synchronized void reset() {
        currentTrack = null;
        resetPending = false;
    }
}
