package app.shengdu.audio.raop;

/**
 * Computes the RAOP {@code progress: start/curr/end} triple (RTP timestamp ticks at
 * 44.1 kHz, unsigned 32-bit with wraparound) from an Android MediaSession position.
 *
 * The anchor maps "now" onto the live audio stream: {@code curr} is the stream's current
 * RTP timestamp, {@code start} is the timestamp the track began at (curr minus elapsed
 * ticks), and {@code end} is where it will finish. Receivers interpolate the on-screen
 * position from their own RTP clock, so this only needs to be sent on track changes and
 * play/pause transitions, not periodically.
 */
public final class RtpProgress {
    public static final int TICKS_PER_SECOND = 44100;
    public static final int INDEX_START = 0;
    public static final int INDEX_CURR = 1;
    public static final int INDEX_END = 2;

    private RtpProgress() {
    }

    /** Returns {start, curr, end} as unsigned 32-bit RTP timestamps. */
    public static long[] compute(long positionMs, long durationMs, long rtpTimestampNow) {
        if (positionMs < 0 || durationMs < 0) {
            throw new IllegalArgumentException("positionMs and durationMs must be >= 0");
        }
        long positionTicks = positionMs * TICKS_PER_SECOND / 1000;
        long durationTicks = durationMs * TICKS_PER_SECOND / 1000;
        long curr = rtpTimestampNow & 0xffffffffL;
        long start = (curr - positionTicks) & 0xffffffffL;
        long end = (start + durationTicks) & 0xffffffffL;
        return new long[] {start, curr, end};
    }
}
