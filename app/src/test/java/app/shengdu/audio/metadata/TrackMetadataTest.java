package app.shengdu.audio.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrackMetadataTest {
    @Test
    public void nullFieldsNormalizeToEmptyStrings() {
        TrackMetadata track = new TrackMetadata(null, null, null, null);

        assertEquals("", track.getSourcePackage());
        assertEquals("", track.getTitle());
        assertEquals("", track.getArtist());
        assertEquals("", track.getAlbum());
    }

    @Test
    public void fieldsAreTrimmed() {
        TrackMetadata track = new TrackMetadata(" pkg ", "  Title  ", " Artist ", " Album ");

        assertEquals("pkg", track.getSourcePackage());
        assertEquals("Title", track.getTitle());
        assertEquals("Artist", track.getArtist());
        assertEquals("Album", track.getAlbum());
    }

    @Test
    public void isEmptyOnlyWhenAllDisplayFieldsAreBlank() {
        assertTrue(new TrackMetadata("pkg", null, "", "  ").isEmpty());
        assertFalse(new TrackMetadata("pkg", "Title", null, null).isEmpty());
        assertFalse(new TrackMetadata("pkg", null, "Artist", null).isEmpty());
        assertFalse(new TrackMetadata("pkg", null, null, "Album").isEmpty());
    }

    @Test
    public void sameTrackIgnoresSourcePackage() {
        TrackMetadata fromApple = new TrackMetadata("com.apple.android.music", "T", "A", "B");
        TrackMetadata fromOther = new TrackMetadata("com.other.player", "T", "A", "B");
        TrackMetadata different = new TrackMetadata("com.apple.android.music", "T2", "A", "B");

        assertTrue(fromApple.sameTrack(fromOther));
        assertFalse(fromApple.sameTrack(different));
        assertFalse(fromApple.sameTrack(null));
    }

    @Test
    public void itemIdentityDistinguishesRepeatedVisibleMetadata() {
        TrackMetadata first = identified("queue:1", "Same title");
        TrackMetadata repeated = identified("queue:2", "Same title");
        TrackMetadata updated = identified("queue:1", "Updated title");

        assertFalse(first.sameTrack(repeated));
        assertTrue(first.sameTrack(updated));
        assertNotEquals(first.dedupKey(), repeated.dedupKey());
        assertNotEquals(first.dedupKey(), updated.dedupKey());
    }

    @Test
    public void identitySourceCanChangeWithoutCreatingANewTrack() {
        TrackMetadata mediaOnly = identified("media:same", "Same title");
        TrackMetadata queueOnly = identified("queue:42", "Same title");
        TrackMetadata both = identified("same", "42", "Same title");

        assertTrue(mediaOnly.sameTrack(both));
        assertTrue(queueOnly.sameTrack(both));
        assertTrue(mediaOnly.sameTrack(queueOnly));
    }

    @Test
    public void equalsAndHashCodeCoverAllFields() {
        TrackMetadata a = new TrackMetadata("pkg", "T", "A", "B");
        TrackMetadata same = new TrackMetadata("pkg", "T", "A", "B");
        TrackMetadata otherPackage = new TrackMetadata("pkg2", "T", "A", "B");

        assertEquals(a, same);
        assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, otherPackage);
    }

    @Test
    public void playbackFieldsDefaultAndClamp() {
        TrackMetadata minimal = new TrackMetadata("pkg", "T", "A", "B");
        assertEquals(0, minimal.getDurationMs());
        assertEquals(0, minimal.getPositionMs());
        assertFalse(minimal.isPlaying());

        TrackMetadata full = new TrackMetadata("pkg", "T", "A", "B", 180_000, 60_000, true);
        assertEquals(180_000, full.getDurationMs());
        assertEquals(60_000, full.getPositionMs());
        assertTrue(full.isPlaying());

        TrackMetadata negative = new TrackMetadata("pkg", "T", "A", "B", -5, -7, false);
        assertEquals(0, negative.getDurationMs());
        assertEquals(0, negative.getPositionMs());
    }

    @Test
    public void dedupKeyIgnoresPlaybackFields() {
        TrackMetadata playing = new TrackMetadata("pkg", "T", "A", "B", 180_000, 1_000, true);
        TrackMetadata paused = new TrackMetadata("pkg", "T", "A", "B", 180_000, 99_000, false);

        assertEquals(playing.dedupKey(), paused.dedupKey());
        assertEquals("", new TrackMetadata("pkg", null, null, null).dedupKey());
    }

    private static TrackMetadata identified(String itemIdentity, String title) {
        return new TrackMetadata(
                "pkg", itemIdentity, title, "Artist", "Album", 180_000, 1_000, true);
    }

    private static TrackMetadata identified(String mediaId, String queueItemId, String title) {
        return new TrackMetadata(
                "pkg", mediaId, queueItemId, title, "Artist", "Album", 180_000, 1_000, true);
    }
}
