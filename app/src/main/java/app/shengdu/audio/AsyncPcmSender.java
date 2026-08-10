package app.shengdu.audio;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/**
 * Bounded, non-blocking handoff between AudioRecord capture and a potentially blocking
 * network writer. The capture thread only copies/enqueues PCM; network I/O happens on
 * the dedicated sender thread.
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
    private final AutoCloseable closer;
    private final Listener listener;

    private Thread thread;
    private int queuedBytes;
    private boolean running;
    private IOException failure;

    AsyncPcmSender(int maxQueuedBytes, Writer writer, AutoCloseable closer, Listener listener) {
        if (maxQueuedBytes <= 0) {
            throw new IllegalArgumentException("maxQueuedBytes must be positive");
        }
        this.maxQueuedBytes = maxQueuedBytes;
        this.writer = Objects.requireNonNull(writer, "writer");
        this.closer = Objects.requireNonNull(closer, "closer");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        synchronized (lock) {
            if (running || thread != null) {
                throw new IllegalStateException("PCM sender already started");
            }
            running = true;
            thread = new Thread(this::runLoop, "raop-pcm-sender");
            thread.setDaemon(true);
            thread.start();
        }
    }

    void offer(byte[] data, int offset, int length) throws IOException {
        Objects.requireNonNull(data, "data");
        if (offset < 0 || length < 0 || offset > data.length || length > data.length - offset) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", arrayLength=" + data.length);
        }
        if (length == 0) {
            return;
        }
        byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
        synchronized (lock) {
            if (failure != null) {
                throw new IOException("RAOP sender failed", failure);
            }
            if (!running) {
                throw new IOException("RAOP sender is stopped");
            }
            if (length > maxQueuedBytes || queuedBytes > maxQueuedBytes - length) {
                throw new IOException("RAOP PCM queue is full");
            }
            queue.addLast(copy);
            queuedBytes += length;
            lock.notifyAll();
        }
    }

    /** Stops accepting PCM immediately. Returns false if network I/O is still blocked. */
    boolean stop(long joinTimeoutMs) {
        Thread threadToJoin;
        synchronized (lock) {
            running = false;
            queue.clear();
            queuedBytes = 0;
            threadToJoin = thread;
            lock.notifyAll();
        }
        if (threadToJoin == null || threadToJoin == Thread.currentThread()) {
            return true;
        }
        threadToJoin.interrupt();
        try {
            threadToJoin.join(Math.max(0, joinTimeoutMs));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !threadToJoin.isAlive();
    }

    private void runLoop() {
        IOException terminalFailure = null;
        try {
            while (true) {
                byte[] pcm;
                synchronized (lock) {
                    while (running && queue.isEmpty()) {
                        lock.wait();
                    }
                    if (!running) {
                        break;
                    }
                    pcm = queue.removeFirst();
                    queuedBytes -= pcm.length;
                }
                writer.write(pcm);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                if (running) {
                    terminalFailure = new IOException("RAOP sender interrupted", exception);
                }
            }
        } catch (IOException exception) {
            terminalFailure = exception;
        } catch (RuntimeException exception) {
            terminalFailure = new IOException("RAOP sender failed", exception);
        } finally {
            synchronized (lock) {
                running = false;
                queue.clear();
                queuedBytes = 0;
                failure = terminalFailure;
                thread = null;
                lock.notifyAll();
            }
            if (terminalFailure != null) {
                listener.onFailure(terminalFailure);
            }
            try {
                closer.close();
            } catch (Exception ignored) {
                // The receiver may already have gone away; sender shutdown stays best-effort.
            }
        }
    }
}
