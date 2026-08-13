package app.shengdu.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AsyncPcmSenderTest {
    @Test
    public void offerCopiesCaptureBufferAndPreservesOrder() throws Exception {
        List<byte[]> delivered = new ArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        AsyncPcmSender sender = sender(
                64,
                pcm -> {
                    synchronized (delivered) {
                        delivered.add(pcm);
                    }
                    received.countDown();
                });
        sender.start();

        byte[] first = {1, 2, 3};
        byte[] second = {4, 5};
        assertTrue(sender.offer(first, 0, first.length));
        assertTrue(sender.offer(second, 0, second.length));
        first[0] = 9;

        assertTrue(received.await(1, TimeUnit.SECONDS));
        synchronized (delivered) {
            assertArrayEquals(new byte[]{1, 2, 3}, delivered.get(0));
            assertArrayEquals(new byte[]{4, 5}, delivered.get(1));
        }
        assertTrue(sender.stop(100, 1_000));
    }

    @Test
    public void overflowDropsOldestQueuedBufferWithoutBlockingCapture() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(3);
        List<byte[]> delivered = new ArrayList<>();
        AsyncPcmSender sender = sender(
                8,
                pcm -> {
                    writerEntered.countDown();
                    synchronized (delivered) {
                        delivered.add(pcm);
                    }
                    received.countDown();
                    if (pcm[0] == 1) {
                        awaitIgnoringInterrupts(releaseWriter);
                    }
                });
        sender.start();
        assertTrue(sender.offer(new byte[]{1, 1, 1, 1}, 0, 4));
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
        assertTrue(sender.offer(new byte[]{2, 2, 2, 2}, 0, 4));
        assertTrue(sender.offer(new byte[]{3, 3, 3, 3}, 0, 4));

        long startedNs = System.nanoTime();
        assertFalse(sender.offer(new byte[]{4, 4, 4, 4}, 0, 4));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        assertTrue("offer blocked for " + elapsedMs + " ms", elapsedMs < 100);
        assertEquals(1, sender.droppedBufferCount());

        releaseWriter.countDown();
        assertTrue(received.await(1, TimeUnit.SECONDS));
        synchronized (delivered) {
            assertEquals(3, delivered.size());
            assertArrayEquals(new byte[]{1, 1, 1, 1}, delivered.get(0));
            assertArrayEquals(new byte[]{3, 3, 3, 3}, delivered.get(1));
            assertArrayEquals(new byte[]{4, 4, 4, 4}, delivered.get(2));
        }
        assertTrue(sender.stop(100, 1_000));
    }

    @Test
    public void discardQueuedKeepsInFlightAudioButRemovesPendingAudio() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(2);
        List<byte[]> delivered = new ArrayList<>();
        AsyncPcmSender sender = sender(
                16,
                pcm -> {
                    synchronized (delivered) {
                        delivered.add(pcm);
                    }
                    received.countDown();
                    if (pcm[0] == 1) {
                        writerEntered.countDown();
                        awaitIgnoringInterrupts(releaseWriter);
                    }
                });
        sender.start();
        assertTrue(sender.offer(new byte[]{1}, 0, 1));
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
        assertTrue(sender.offer(new byte[]{2}, 0, 1));
        assertTrue(sender.offer(new byte[]{3}, 0, 1));

        sender.discardQueued();
        assertTrue(sender.offer(new byte[]{4}, 0, 1));
        releaseWriter.countDown();

        assertTrue(received.await(1, TimeUnit.SECONDS));
        synchronized (delivered) {
            assertEquals(2, delivered.size());
            assertArrayEquals(new byte[]{1}, delivered.get(0));
            assertArrayEquals(new byte[]{4}, delivered.get(1));
        }
        assertTrue(sender.stop(100, 1_000));
    }

    @Test
    public void oversizedBufferIsDroppedWithoutBreakingQueueBound() throws Exception {
        AsyncPcmSender sender = sender(4, pcm -> fail("Oversized PCM must not be delivered"));
        sender.start();

        assertFalse(sender.offer(new byte[5], 0, 5));
        assertEquals(1, sender.droppedBufferCount());
        assertTrue(sender.stop(100, 1_000));
    }

    @Test
    public void stopAbortsWriterThatPermanentlyIgnoresInterrupts() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch abortReleasedWriter = new CountDownLatch(1);
        AtomicInteger abortCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AsyncPcmSender sender = new AsyncPcmSender(
                64,
                pcm -> {
                    writerEntered.countDown();
                    awaitIgnoringInterrupts(abortReleasedWriter);
                    throw new IOException("socket closed by abort");
                },
                () -> {
                    abortCount.incrementAndGet();
                    abortReleasedWriter.countDown();
                },
                closeCount::incrementAndGet,
                error -> failureCount.incrementAndGet());
        sender.start();
        assertTrue(sender.offer(new byte[4], 0, 4));
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS));

        long startedNs = System.nanoTime();
        assertTrue(sender.stop(20, 1_000));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);

        assertTrue("stop exceeded its bounded waits: " + elapsedMs + " ms", elapsedMs < 2_000);
        assertEquals(1, abortCount.get());
        assertEquals(1, closeCount.get());
        assertEquals(0, failureCount.get());
        assertTrue(sender.stop(20, 20));
        assertEquals(1, abortCount.get());
        assertEquals(1, closeCount.get());
    }

    @Test
    public void stopAbortsCloserBlockedWaitingForGracefulTransportResponse() throws Exception {
        CountDownLatch closerEntered = new CountDownLatch(1);
        CountDownLatch abortReleasedCloser = new CountDownLatch(1);
        AtomicInteger abortCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AsyncPcmSender sender = new AsyncPcmSender(
                64,
                pcm -> {},
                () -> {
                    abortCount.incrementAndGet();
                    abortReleasedCloser.countDown();
                },
                () -> {
                    closeCount.incrementAndGet();
                    closerEntered.countDown();
                    awaitIgnoringInterrupts(abortReleasedCloser);
                },
                error -> failureCount.incrementAndGet());
        sender.start();

        AtomicBoolean stopped = new AtomicBoolean();
        Thread stopThread = new Thread(
                () -> stopped.set(sender.stop(2_000, 1_000)),
                "pcm-sender-stop-test");
        stopThread.start();
        assertTrue(closerEntered.await(1, TimeUnit.SECONDS));
        assertEquals("abort must wait for the graceful close window", 1, abortReleasedCloser.getCount());
        assertTrue("stop must still be waiting on the blocked closer", stopThread.isAlive());
        stopThread.join(4_000);

        assertFalse("stop did not complete after abort released close", stopThread.isAlive());
        assertTrue(stopped.get());
        assertEquals(1, abortCount.get());
        assertEquals(1, closeCount.get());
        assertEquals(0, failureCount.get());
    }

    @Test
    public void writerFailureCleansUpBeforeNotifyingAndContainsListenerFailure() throws Exception {
        AtomicInteger abortCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger listenerCount = new AtomicInteger();
        AtomicBoolean cleanupObserved = new AtomicBoolean();
        CountDownLatch notified = new CountDownLatch(1);
        AsyncPcmSender sender = new AsyncPcmSender(
                64,
                pcm -> { throw new IOException("receiver disconnected"); },
                abortCount::incrementAndGet,
                closeCount::incrementAndGet,
                error -> {
                    cleanupObserved.set(abortCount.get() == 1 && closeCount.get() == 1);
                    listenerCount.incrementAndGet();
                    notified.countDown();
                    throw new IllegalStateException("listener bug");
                });
        sender.start();
        assertTrue(sender.offer(new byte[4], 0, 4));

        assertTrue(notified.await(1, TimeUnit.SECONDS));
        assertTrue(cleanupObserved.get());
        assertEquals(1, listenerCount.get());
        assertFalse(sender.offer(new byte[1], 0, 1));
        assertTrue(sender.stop(100, 1_000));
        assertEquals(1, abortCount.get());
        assertEquals(1, closeCount.get());
    }

    @Test
    public void offerValidatesArrayRange() {
        AsyncPcmSender sender = sender(8, pcm -> {});
        sender.start();
        try {
            sender.offer(new byte[4], 3, 2);
            fail("Expected invalid range to be rejected");
        } catch (IndexOutOfBoundsException expected) {
            assertTrue(expected.getMessage().contains("offset=3"));
        } finally {
            assertTrue(sender.stop(100, 1_000));
        }
    }

    private static AsyncPcmSender sender(int maxQueuedBytes, AsyncPcmSender.Writer writer) {
        AtomicReference<IOException> unexpectedFailure = new AtomicReference<>();
        return new AsyncPcmSender(
                maxQueuedBytes,
                writer,
                () -> {},
                () -> {},
                unexpectedFailure::set);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
