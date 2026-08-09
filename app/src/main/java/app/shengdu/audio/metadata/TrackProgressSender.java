package app.shengdu.audio.metadata;

import app.shengdu.audio.raop.RtpProgress;

import java.io.IOException;
import java.util.Objects;

/** Computes, deduplicates, and acknowledges one receiver progress update. */
public final class TrackProgressSender {
    @FunctionalInterface
    public interface Sink {
        void send(long start, long current, long end) throws IOException;
    }

    private final TrackProgressTracker tracker = new TrackProgressTracker();

    public void send(
            MetadataSendPolicy policy,
            TrackMetadata track,
            long rtpTimestamp,
            Sink sink) {
        if (policy == null || track == null || track.getDurationMs() <= 0) {
            return;
        }
        String key = track.dedupKey() + "|" + (track.isPlaying() ? "playing" : "paused");
        if (!policy.shouldSend(key)) {
            return;
        }

        long[] progress = RtpProgress.compute(
                tracker.positionMsFor(track), track.getDurationMs(), rtpTimestamp);
        try {
            Objects.requireNonNull(sink, "sink").send(
                    progress[RtpProgress.INDEX_START],
                    progress[RtpProgress.INDEX_CURR],
                    progress[RtpProgress.INDEX_END]);
            policy.onSendSuccess();
            tracker.onProgressSent(track);
        } catch (IOException | RuntimeException ignored) {
            policy.onSendFailure();
        }
    }

    public void reset() {
        tracker.reset();
    }
}
