package app.shengdu.audio.metadata;

import static org.junit.Assert.assertArrayEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class TrackProgressSenderTest {
    @Test
    public void initialTrackKeepsPositionAndNextTrackStartsAtZero() {
        TrackProgressSender sender = new TrackProgressSender();
        MetadataSendPolicy policy = advertisedPolicy();
        List<long[]> sent = new ArrayList<>();

        sender.send(policy, track("First", 60_000, true), 10_000_000, collecting(sent));
        sender.send(policy, track("Next", 60_000, true), 10_500_000, collecting(sent));

        assertArrayEquals(new long[] {7_354_000, 10_000_000, 15_292_000}, sent.get(0));
        assertArrayEquals(new long[] {10_500_000, 10_500_000, 18_438_000}, sent.get(1));
    }

    @Test
    public void failedResetSendDoesNotAcknowledgeTrackBoundary() {
        TrackProgressSender sender = new TrackProgressSender();
        List<long[]> sent = new ArrayList<>();

        sender.send(advertisedPolicy(), track("First", 60_000, true), 10_000_000, collecting(sent));
        sender.send(
                advertisedPolicy(),
                track("Next", 60_000, true),
                10_500_000,
                (start, current, end) -> {
                    throw new IOException("receiver rejected progress");
                });
        sender.send(
                advertisedPolicy(),
                track("Next", 61_000, true),
                10_600_000,
                collecting(sent));

        assertArrayEquals(new long[] {10_600_000, 10_600_000, 18_538_000}, sent.get(1));
    }

    @Test
    public void pausedBoundaryRemainsZeroWhenPlaybackStarts() {
        TrackProgressSender sender = new TrackProgressSender();
        MetadataSendPolicy policy = advertisedPolicy();
        List<long[]> sent = new ArrayList<>();

        sender.send(policy, track("First", 60_000, true), 10_000_000, collecting(sent));
        sender.send(policy, track("Next", 60_000, false), 10_500_000, collecting(sent));
        sender.send(policy, track("Next", 60_000, true), 10_600_000, collecting(sent));

        assertArrayEquals(new long[] {10_500_000, 10_500_000, 18_438_000}, sent.get(1));
        assertArrayEquals(new long[] {10_600_000, 10_600_000, 18_538_000}, sent.get(2));
    }

    @Test
    public void repeatedVisibleMetadataWithDifferentItemIdentityStartsAtZero() {
        TrackProgressSender sender = new TrackProgressSender();
        MetadataSendPolicy policy = advertisedPolicy();
        List<long[]> sent = new ArrayList<>();

        sender.send(policy, track("queue:1", "Same", 60_000), 10_000_000, collecting(sent));
        sender.send(policy, track("queue:2", "Same", 60_000), 10_500_000, collecting(sent));

        assertArrayEquals(new long[] {10_500_000, 10_500_000, 18_438_000}, sent.get(1));
    }

    @Test
    public void identitySourceChangeKeepsTheSameTrackPosition() {
        TrackProgressSender sender = new TrackProgressSender();
        MetadataSendPolicy policy = advertisedPolicy();
        List<long[]> sent = new ArrayList<>();

        sender.send(policy, track("media:same", "Same", 60_000), 10_000_000, collecting(sent));
        sender.send(policy, track("queue:42", "Same", 61_000), 10_500_000, collecting(sent));

        assertArrayEquals(new long[] {7_809_900, 10_500_000, 15_747_900}, sent.get(1));
    }

    private static MetadataSendPolicy advertisedPolicy() {
        return new MetadataSendPolicy(MetadataSendPolicy.Mode.ADVERTISED);
    }

    private static TrackProgressSender.Sink collecting(List<long[]> sent) {
        return (start, current, end) -> sent.add(new long[] {start, current, end});
    }

    private static TrackMetadata track(String title, long positionMs, boolean playing) {
        return new TrackMetadata("player", title, "Artist", "Album", 180_000, positionMs, playing);
    }

    private static TrackMetadata track(String itemIdentity, String title, long positionMs) {
        return new TrackMetadata(
                "player", itemIdentity, title, "Artist", "Album", 180_000, positionMs, true);
    }
}
