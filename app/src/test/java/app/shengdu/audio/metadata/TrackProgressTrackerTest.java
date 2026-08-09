package app.shengdu.audio.metadata;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TrackProgressTrackerTest {
    @Test
    public void initialTrackKeepsItsCurrentPosition() {
        TrackProgressTracker tracker = new TrackProgressTracker();
        TrackMetadata track = track("First", 45_000);

        assertEquals(45_000, tracker.positionMsFor(track));
    }

    @Test
    public void changedTrackStaysAtZeroUntilResetProgressIsSent() {
        TrackProgressTracker tracker = new TrackProgressTracker();
        TrackMetadata first = track("First", 45_000);
        TrackMetadata nextWithStalePosition = track("Next", 45_000);
        TrackMetadata nextBeforeAcknowledgement = track("Next", 800);
        TrackMetadata nextAfterAcknowledgement = track("Next", 1_200);

        tracker.positionMsFor(first);

        assertEquals(0, tracker.positionMsFor(nextWithStalePosition));
        assertEquals(0, tracker.positionMsFor(nextBeforeAcknowledgement));

        tracker.onProgressSent(nextBeforeAcknowledgement);

        assertEquals(1_200, tracker.positionMsFor(nextAfterAcknowledgement));
    }

    @Test
    public void missingSessionDoesNotForgetThePreviousTrack() {
        TrackProgressTracker tracker = new TrackProgressTracker();

        tracker.positionMsFor(track("First", 30_000));
        tracker.positionMsFor(null);

        assertEquals(0, tracker.positionMsFor(track("Next", 30_000)));
    }

    @Test
    public void changedTrackRemainsAtZeroAcrossPausedThenPlayingTransition() {
        TrackProgressTracker tracker = new TrackProgressTracker();
        TrackMetadata first = track("First", 45_000);
        TrackMetadata nextPaused = track("Next", 45_000, false);
        TrackMetadata nextPlaying = track("Next", 45_000, true);

        tracker.positionMsFor(first);

        assertEquals(0, tracker.positionMsFor(nextPaused));
        tracker.onProgressSent(nextPaused);
        assertEquals(0, tracker.positionMsFor(nextPlaying));
    }

    @Test
    public void resetMakesTheNextSnapshotAnInitialTrack() {
        TrackProgressTracker tracker = new TrackProgressTracker();

        tracker.positionMsFor(track("First", 30_000));
        tracker.reset();

        assertEquals(12_000, tracker.positionMsFor(track("Next", 12_000)));
    }

    @Test
    public void repeatedVisibleMetadataWithDifferentItemIdentityResets() {
        TrackProgressTracker tracker = new TrackProgressTracker();

        tracker.positionMsFor(track("queue:1", "Same", 30_000));

        assertEquals(0, tracker.positionMsFor(track("queue:2", "Same", 30_000)));
    }

    @Test
    public void identitySourceChangeDoesNotResetTheSameTrack() {
        TrackProgressTracker tracker = new TrackProgressTracker();

        tracker.positionMsFor(track("media:same", "Same", 30_000));

        assertEquals(31_000, tracker.positionMsFor(track("queue:42", "Same", 31_000)));
    }

    private static TrackMetadata track(String title, long positionMs) {
        return track(title, positionMs, true);
    }

    private static TrackMetadata track(String title, long positionMs, boolean playing) {
        return new TrackMetadata("player", title, "Artist", "Album", 180_000, positionMs, playing);
    }

    private static TrackMetadata track(String itemIdentity, String title, long positionMs) {
        return new TrackMetadata(
                "player", itemIdentity, title, "Artist", "Album", 180_000, positionMs, true);
    }
}
