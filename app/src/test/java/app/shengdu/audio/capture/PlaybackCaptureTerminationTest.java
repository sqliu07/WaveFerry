package app.shengdu.audio.capture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class PlaybackCaptureTerminationTest {
    @Test
    public void boundedJoinReturnsWhenCaptureThreadDoesNotStop() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Thread blocked = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                // This fixture intentionally models a native read that ignores interruption.
            }
        }, "blocked-capture-test");
        blocked.start();

        long startedNanos = System.nanoTime();
        try {
            assertFalse(PlaybackCapture.awaitThreadTermination(blocked, 50L));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) < 500L);
        } finally {
            release.countDown();
            blocked.join(1_000L);
        }
    }
}
