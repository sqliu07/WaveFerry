package app.shengdu.audio.raop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RtpProgressTest {
    @Test
    public void trackStartAnchorsStartAtCurrentTimestamp() {
        long[] progress = RtpProgress.compute(0, 180_000, 1000);

        // 180 s = 7,938,000 ticks.
        assertArrayEquals(new long[] {1000, 1000, 7_939_000}, progress);
    }

    @Test
    public void midTrackAnchorsStartInThePast() {
        long[] progress = RtpProgress.compute(60_000, 180_000, 10_000_000);

        // 60 s = 2,646,000 ticks elapsed.
        assertEquals(7_354_000L, progress[RtpProgress.INDEX_START]);
        assertEquals(10_000_000L, progress[RtpProgress.INDEX_CURR]);
        assertEquals(15_292_000L, progress[RtpProgress.INDEX_END]);
    }

    @Test
    public void timestampsWrapAsUnsigned32Bit() {
        long[] progress = RtpProgress.compute(60_000, 180_000, 1_000_000);

        assertEquals(4_293_321_296L, progress[RtpProgress.INDEX_START]);
        assertEquals(1_000_000L, progress[RtpProgress.INDEX_CURR]);
        assertEquals(6_292_000L, progress[RtpProgress.INDEX_END]);
    }

    @Test
    public void millisecondPositionsTruncateToWholeTicks() {
        long[] progress = RtpProgress.compute(1, 0, 10_000);

        // 1 ms = 44.1 ticks, truncated to 44.
        assertEquals(10_000 - 44, progress[RtpProgress.INDEX_START]);
        assertEquals(10_000, progress[RtpProgress.INDEX_CURR]);
        assertEquals(10_000 - 44, progress[RtpProgress.INDEX_END]);
    }

    @Test
    public void negotiatedSampleRateControlsProgressTicks() {
        long[] progress = RtpProgress.compute(1000, 2000, 5000, 48000);

        assertArrayEquals(new long[] {4_294_924_296L, 5000, 53_000}, progress);
    }

    @Test
    public void rejectsNegativePositions() {
        try {
            RtpProgress.compute(-1, 0, 0);
            org.junit.Assert.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("positionMs and durationMs must be >= 0", expected.getMessage());
        }
    }
}
