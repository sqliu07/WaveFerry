package app.shengdu.audio.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlaybackFlowGateTest {
    @Test
    public void defaultForwardsPcm() {
        PlaybackFlowGate gate = new PlaybackFlowGate();

        assertTrue(gate.shouldForwardPcm());
    }

    @Test
    public void initialPausedTrackReturnsPausedAndBlocksPcm() {
        PlaybackFlowGate gate = new PlaybackFlowGate();

        assertEquals(PlaybackFlowGate.Transition.PAUSED, gate.update(track(false, 12_000)));
        assertFalse(gate.shouldForwardPcm());
    }

    @Test
    public void repeatedPausedUpdateReturnsNone() {
        PlaybackFlowGate gate = new PlaybackFlowGate();
        gate.update(track(false, 12_000));

        assertEquals(PlaybackFlowGate.Transition.NONE, gate.update(track(false, 18_000)));
        assertFalse(gate.shouldForwardPcm());
    }

    @Test
    public void transitionBackToPlayingReturnsResumedAndForwardsPcm() {
        PlaybackFlowGate gate = new PlaybackFlowGate();
        gate.update(track(false, 12_000));

        assertEquals(PlaybackFlowGate.Transition.RESUMED, gate.update(track(true, 18_000)));
        assertTrue(gate.shouldForwardPcm());
    }

    @Test
    public void initialNullMetadataKeepsGateOpen() {
        PlaybackFlowGate gate = new PlaybackFlowGate();

        assertEquals(PlaybackFlowGate.Transition.NONE, gate.update(null));
        assertTrue(gate.shouldForwardPcm());
        assertEquals(0L, gate.revision());
    }

    @Test
    public void nullMetadataAfterPauseKeepsPcmBlocked() {
        PlaybackFlowGate gate = new PlaybackFlowGate();
        gate.update(track(false, 12_000));

        assertEquals(PlaybackFlowGate.Transition.NONE, gate.update(null));
        assertFalse(gate.shouldForwardPcm());
        assertEquals(1L, gate.revision());
    }

    @Test
    public void missingMetadataAfterKnownPlayingPausesAndBlocksPcm() {
        PlaybackFlowGate gate = new PlaybackFlowGate();
        gate.update(track(true, 12_000));

        assertEquals(PlaybackFlowGate.Transition.PAUSED, gate.update(null));
        assertFalse(gate.shouldForwardPcm());
        assertEquals(1L, gate.revision());
    }

    @Test
    public void resetFailsOpenAndForwardsPcm() {
        PlaybackFlowGate gate = new PlaybackFlowGate();
        gate.update(track(false, 12_000));

        gate.reset();

        assertTrue(gate.shouldForwardPcm());
    }

    @Test
    public void positionOnlySameStateUpdateReturnsNone() {
        PlaybackFlowGate gate = new PlaybackFlowGate();
        gate.update(track(true, 12_000));

        assertEquals(PlaybackFlowGate.Transition.NONE, gate.update(track(true, 48_000)));
        assertTrue(gate.shouldForwardPcm());
    }

    @Test
    public void revisionOnlyAdvancesOnGateStateChanges() {
        PlaybackFlowGate gate = new PlaybackFlowGate();

        assertEquals(0L, gate.revision());
        gate.update(track(true, 12_000));
        assertEquals(0L, gate.revision());
        gate.update(track(true, 48_000));
        assertEquals(0L, gate.revision());
        gate.update(track(false, 60_000));
        assertEquals(1L, gate.revision());
        gate.update(track(false, 72_000));
        assertEquals(1L, gate.revision());
        gate.update(track(true, 84_000));
        assertEquals(2L, gate.revision());
        gate.update(null);
        assertEquals(3L, gate.revision());
        gate.update(null);
        assertEquals(3L, gate.revision());
    }

    private static TrackMetadata track(boolean playing, long positionMs) {
        return new TrackMetadata("pkg", "Title", "Artist", "Album", 180_000, positionMs, playing);
    }
}
