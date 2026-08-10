package app.shengdu.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AsyncPcmSenderTest {
    @Test
    public void offerCopiesCaptureBufferAndPreservesOrder() throws Exception {
        List<byte[]> delivered = new ArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        AsyncPcmSender sender = new AsyncPcmSender(
                64,
                pcm -> {
                    synchronized (delivered) {
                        delivered.add(pcm);
                    }
                    received.countDown();
                },
                () -> {},
                error -> fail("Unexpected sender failure: " + error));
        sender.start();

        byte[] first = {1, 2, 3};
        byte[] second = {4, 5};
        sender.offer(first, 0, first.length);
        sender.offer(second, 0, second.length);
        first[0] = 9;

        assertTrue(received.await(1, TimeUnit.SECONDS));
        synchronized (delivered) {
            assertArrayEquals(new byte[]{1, 2, 3}, delivered.get(0));
            assertArrayEquals(new byte[]{4, 5}, delivered.get(1));
        }
        assertTrue(sender.stop(1_000));
    }

    @Test
    public void fullQueueFailsFastInsteadOfBlockingCapture() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        AsyncPcmSender sender = new AsyncPcmSender(
                4,
                pcm -> {
                    writerEntered.countDown();
                    try {
                        releaseWriter.await();
                    } catch (InterruptedException ignored) {
                        // Simulate a socket write that does not wake on thread interruption.
                    }
                },
                () -> {},
                error -> fail("Unexpected sender failure: " + error));
        sender.start();
        sender.offer(new byte[4], 0, 4);
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS));
        sender.offer(new byte[4], 0, 4);

        long startedNs = System.nanoTime();
        try {
            sender.offer(new byte[1], 0, 1);
            fail("Expected bounded queue failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("full"));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        assertTrue("offer blocked for " + elapsedMs + " ms", elapsedMs < 100);

        releaseWriter.countDown();
        assertTrue(sender.stop(1_000));
    }

    @Test
    public void writerFailureReachesListenerAndStopsFurtherOffers() throws Exception {
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<IOException> failure = new AtomicReference<>();
        AsyncPcmSender sender = new AsyncPcmSender(
                64,
                pcm -> { throw new IOException("receiver disconnected"); },
                () -> {},
                error -> {
                    failure.set(error);
                    failed.countDown();
                });
        sender.start();
        sender.offer(new byte[4], 0, 4);

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertTrue(failure.get().getMessage().contains("receiver disconnected"));
        try {
            sender.offer(new byte[1], 0, 1);
            fail("Expected sender failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("failed"));
        }
        assertTrue(sender.stop(1_000));
    }

    @Test
    public void stopIsBoundedWhenWriterIgnoresInterrupts() throws Exception {
        CountDownLatch writerEntered = new CountDownLatch(1);
        AtomicBoolean releaseWriter = new AtomicBoolean();
        AsyncPcmSender sender = new AsyncPcmSender(
                64,
                pcm -> {
                    writerEntered.countDown();
                    while (!releaseWriter.get()) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                            // SocketOutputStream writes can behave like this: interruption alone
                            // is not a reliable teardown mechanism.
                        }
                    }
                },
                () -> {},
                error -> fail("Unexpected sender failure: " + error));
        sender.start();
        sender.offer(new byte[4], 0, 4);
        assertTrue(writerEntered.await(1, TimeUnit.SECONDS));

        long startedNs = System.nanoTime();
        assertFalse(sender.stop(50));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
        assertTrue("stop blocked for " + elapsedMs + " ms", elapsedMs < 250);

        releaseWriter.set(true);
        assertTrue(sender.stop(1_000));
    }
}
