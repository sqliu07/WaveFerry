package app.shengdu.audio;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded handoff between the AudioRecord callback and a potentially blocking RAOP writer.
 * Network I/O is confined to one daemon thread; capture only copies and enqueues PCM.
 */
final class AsyncPcmSender {
    interface Writer {
        void write(byte[] pcm) throws IOException;
    }

    interface Listener {
        void onFailure(IOException error);
    }

    private final Object lock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final int maxQueuedBytes;
    private final Writer writer;
    private final Runnable aborter;
    private final AutoCloseable closer;
    private final Listener listener;
    private final AtomicBoolean abortStarted = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();

    private Thread thread;
    private int queuedBytes;
    private long droppedBufferCount;
    private boolean started;
    private boolean accepting;
    private boolean stopping;

    AsyncPcmSender(
            int maxQueuedBytes,
            Writer writer,
            Runnable aborter,
            AutoCloseable closer,
            Listener listener) {
        if (maxQueuedBytes <= 0) {
            throw new IllegalArgumentException("maxQueuedBytes must be positive");
        }
        this.maxQueuedBytes = maxQueuedBytes;
        this.writer = Objects.requireNonNull(writer, "writer");
        this.aborter = Objects.requireNonNull(aborter, "aborter");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("PCM sender already started");
            }
            started = true;
            accepting = true;
            thread = new Thread(this::runLoop, "raop-pcm-sender");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /**
     * Enqueues a defensive copy without waiting for network I/O. If necessary, the oldest
     * complete queued buffers are dropped so the receiver hears the freshest available audio.
     *
     * @return true when no PCM was dropped, false when this or older queued PCM was dropped
     */
    boolean offer(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        checkRange(data.length, offset, length);
        if (length == 0) {
            synchronized (lock) {
                return accepting;
            }
        }

        synchronized (lock) {
            if (!accepting) {
                return false;
            }
            if (length > maxQueuedBytes) {
                droppedBufferCount++;
                return false;
            }
        }

        byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
        synchronized (lock) {
            if (!accepting) {
                return false;
            }
            boolean keptEverything = true;
            while (queuedBytes > maxQueuedBytes - length && !queue.isEmpty()) {
                byte[] dropped = queue.removeFirst();
                queuedBytes -= dropped.length;
                droppedBufferCount++;
                keptEverything = false;
            }
            if (queuedBytes > maxQueuedBytes - length) {
                droppedBufferCount++;
                return false;
            }
            queue.addLast(copy);
            queuedBytes += length;
            lock.notifyAll();
            return keptEverything;
        }
    }

    /**
     * Stops accepting PCM and waits briefly for an orderly close. If the sender remains stuck,
     * the transport is aborted independently of the writer monitor and the thread is joined again.
     */
    boolean stop(long gracefulTimeoutMs, long abortTimeoutMs) {
        if (gracefulTimeoutMs < 0 || abortTimeoutMs < 0) {
            throw new IllegalArgumentException("stop timeouts must not be negative");
        }
        Thread threadToJoin;
        synchronized (lock) {
            accepting = false;
            stopping = true;
            queue.clear();
            queuedBytes = 0;
            threadToJoin = thread;
            lock.notifyAll();
        }
        if (threadToJoin == null || threadToJoin == Thread.currentThread()) {
            return true;
        }

        threadToJoin.interrupt();
        if (!join(threadToJoin, gracefulTimeoutMs)) {
            abortOnce();
            threadToJoin.interrupt();
            join(threadToJoin, abortTimeoutMs);
        }
        return !threadToJoin.isAlive();
    }

    long droppedBufferCount() {
        synchronized (lock) {
            return droppedBufferCount;
        }
    }

    void discardQueued() {
        synchronized (lock) {
            queue.clear();
            queuedBytes = 0;
        }
    }

    private void runLoop() {
        IOException terminalFailure = null;
        try {
            while (true) {
                byte[] pcm;
                synchronized (lock) {
                    while (accepting && queue.isEmpty()) {
                        lock.wait();
                    }
                    if (!accepting) {
                        break;
                    }
                    pcm = queue.removeFirst();
                    queuedBytes -= pcm.length;
                }
                writer.write(pcm);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!isStopping()) {
                terminalFailure = new IOException("RAOP sender interrupted", exception);
            }
        } catch (IOException exception) {
            if (!isStopping()) {
                terminalFailure = exception;
            }
        } catch (RuntimeException exception) {
            if (!isStopping()) {
                terminalFailure = new IOException("RAOP sender failed", exception);
            }
        } finally {
            synchronized (lock) {
                accepting = false;
                queue.clear();
                queuedBytes = 0;
                lock.notifyAll();
            }

            if (terminalFailure != null) {
                abortOnce();
            }
            closeOnce();

            if (terminalFailure != null) {
                try {
                    listener.onFailure(terminalFailure);
                } catch (RuntimeException ignored) {
                    // Service callbacks must not bypass sender thread finalization.
                }
            }
            synchronized (lock) {
                if (thread == Thread.currentThread()) {
                    thread = null;
                }
                lock.notifyAll();
            }
        }
    }

    private boolean isStopping() {
        synchronized (lock) {
            return stopping;
        }
    }

    private void abortOnce() {
        if (!abortStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            aborter.run();
        } catch (RuntimeException ignored) {
            // Abort is best-effort; close still gets a chance to release remaining state.
        }
    }

    private void closeOnce() {
        if (!closeStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            closer.close();
        } catch (Exception ignored) {
            // The receiver may already have gone away or the transport may have been aborted.
        }
    }

    private static boolean join(Thread thread, long timeoutMs) {
        if (timeoutMs == 0) {
            return !thread.isAlive();
        }
        try {
            thread.join(timeoutMs);
            return !thread.isAlive();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void checkRange(int arrayLength, int offset, int length) {
        if (offset < 0 || length < 0 || offset > arrayLength || length > arrayLength - offset) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", arrayLength=" + arrayLength);
        }
    }
}
