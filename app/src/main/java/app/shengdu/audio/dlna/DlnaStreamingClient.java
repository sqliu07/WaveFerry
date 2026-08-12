package app.shengdu.audio.dlna;

import app.shengdu.audio.format.AudioStreamFormat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Sends captured PCM to a MediaRenderer through a local WAV endpoint and AVTransport. */
public final class DlnaStreamingClient implements AutoCloseable {
    private static final long TRANSITION_SETTLE_TIMEOUT_MS = 3_000L;
    private static final long TRANSITION_RETRY_INTERVAL_MS = 250L;
    private static final long REMOTE_STATE_POLL_INTERVAL_MS = 750L;

    public interface RemotePlaybackListener {
        void onRemotePlaybackRequested(boolean playing);
    }

    private final DlnaLocalAudioServer audioServer;
    private final DlnaAvTransportClient avTransport;
    private final RemotePlaybackListener remotePlaybackListener;
    private final ScheduledExecutorService remoteStatePoller =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dlna-transport-state");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean started;
    private volatile boolean localTransition;
    private volatile String lastObservedTransportState = "";
    private final AtomicLong localTransitionRevision = new AtomicLong();
    private URI mediaUri;
    private String metadataKey = "";
    private String lastTrackKey = "";

    public DlnaStreamingClient(
            InetAddress receiverAddress,
            DlnaRendererDescription renderer,
            AudioStreamFormat format) {
        this(receiverAddress, renderer, format, playing -> { });
    }

    public DlnaStreamingClient(
            InetAddress receiverAddress,
            DlnaRendererDescription renderer,
            AudioStreamFormat format,
            RemotePlaybackListener remotePlaybackListener) {
        Objects.requireNonNull(renderer, "renderer");
        if (!renderer.canReceiveMedia() || renderer.avTransportServiceType().isEmpty()) {
            throw new IllegalArgumentException("DLNA renderer does not expose AVTransport");
        }
        audioServer = new DlnaLocalAudioServer(Objects.requireNonNull(receiverAddress, "receiverAddress"), format);
        avTransport = new DlnaAvTransportClient(
                renderer.avTransportControlUri(), renderer.avTransportServiceType());
        this.remotePlaybackListener = Objects.requireNonNull(
                remotePlaybackListener, "remotePlaybackListener");
    }

    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        localTransitionRevision.incrementAndGet();
        localTransition = true;
        try {
            mediaUri = audioServer.start();
            stopCurrentTransportIfNeeded();
            runWhenTransitionReady(() ->
                    avTransport.setUri(mediaUri, DlnaLiveAudioMetadata.didlLite(mediaUri)));
            String state = waitForTransportToSettle();
            if (!isPlaying(state)) {
                playWhenReady();
            }
            started = true;
            lastObservedTransportState = "PLAYING";
            remoteStatePoller.scheduleWithFixedDelay(
                    this::pollRemoteTransportState,
                    REMOTE_STATE_POLL_INTERVAL_MS,
                    REMOTE_STATE_POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        } catch (IOException error) {
            mediaUri = null;
            audioServer.close();
            throw error;
        } finally {
            localTransition = false;
        }
    }

    public void writePcm(byte[] buffer, int offset, int byteCount) {
        // Buffer a short pre-roll even while SetAVTransportURI is in progress.
        audioServer.writePcm(buffer, offset, byteCount);
    }

    public synchronized void pause() throws IOException {
        if (started) {
            localTransitionRevision.incrementAndGet();
            localTransition = true;
            try {
                avTransport.pause();
                lastObservedTransportState = "PAUSED_PLAYBACK";
            } finally {
                localTransition = false;
            }
        }
    }

    public synchronized void resume() throws IOException {
        if (started) {
            localTransitionRevision.incrementAndGet();
            localTransition = true;
            try {
                playWhenReady();
                lastObservedTransportState = "PLAYING";
            } finally {
                localTransition = false;
            }
        }
    }

    /** Refreshes the renderer's now-playing fields without affecting the capture thread. */
    public synchronized void updateMetadata(
            String title,
            String artist,
            String album,
            String trackKey,
            long durationMs,
            long positionMs,
            byte[] artworkJpeg,
            boolean shouldPlay) throws IOException {
        if (!started || mediaUri == null || trackKey == null || trackKey.isBlank()) {
            return;
        }
        int artworkHash = Arrays.hashCode(artworkJpeg);
        String nextMetadataKey = trackKey + '\n' + durationMs + '\n' + Integer.toHexString(artworkHash);
        if (nextMetadataKey.equals(metadataKey)) {
            return;
        }

        boolean trackChanged = !trackKey.equals(lastTrackKey);
        audioServer.updateArtwork(artworkJpeg);
        URI artworkUri = artworkJpeg == null || artworkJpeg.length == 0
                ? null
                : audioServer.artworkUri(artworkHash);
        if (trackChanged) {
            // Do not let the tail of the previous track become the new track's pre-roll.
            audioServer.discardBufferedPcm();
        }

        localTransitionRevision.incrementAndGet();
        localTransition = true;
        try {
            stopCurrentTransportIfNeeded();
            runWhenTransitionReady(() -> avTransport.setUri(
                    mediaUri,
                    DlnaLiveAudioMetadata.didlLite(
                            mediaUri, title, artist, album, artworkUri, durationMs)));
            String state = waitForTransportToSettle();
            if (shouldPlay && !isPlaying(state)) {
                playWhenReady();
            }
            if (shouldPlay && positionMs > 0) {
                try {
                    avTransport.seekTo(positionMs);
                    waitForTransportToSettle();
                } catch (IOException ignored) {
                    // REL_TIME seek is optional. Duration metadata still fixes the
                    // renderer's total length when exact position sync is unavailable.
                }
            }
            lastObservedTransportState = shouldPlay ? "PLAYING" : normalizeState(state);
        } finally {
            localTransition = false;
        }
        metadataKey = nextMetadataKey;
        lastTrackKey = trackKey;
    }

    @Override
    public synchronized void close() {
        localTransitionRevision.incrementAndGet();
        localTransition = true;
        started = false;
        remoteStatePoller.shutdownNow();
        if (mediaUri != null) {
            try {
                avTransport.stop();
            } catch (IOException ignored) {
                // The renderer may already be offline.
            }
        }
        mediaUri = null;
        metadataKey = "";
        lastTrackKey = "";
        audioServer.close();
        localTransition = false;
    }

    private void pollRemoteTransportState() {
        if (!started || localTransition) {
            return;
        }
        long revision = localTransitionRevision.get();
        final String state;
        try {
            state = normalizeState(avTransport.transportState());
        } catch (IOException | RuntimeException ignored) {
            return;
        }
        if (state.isEmpty()
                || !started
                || localTransition
                || revision != localTransitionRevision.get()) {
            return;
        }
        String previous = lastObservedTransportState;
        lastObservedTransportState = state;
        RemoteCommand command = remoteCommand(previous, state);
        try {
            if (command == RemoteCommand.PLAY) {
                remotePlaybackListener.onRemotePlaybackRequested(true);
            } else if (command == RemoteCommand.PAUSE) {
                remotePlaybackListener.onRemotePlaybackRequested(false);
            }
        } catch (RuntimeException ignored) {
            // A service may be tearing down while an in-flight poll completes.
        }
    }

    static RemoteCommand remoteCommand(String previousState, String currentState) {
        String previous = normalizeState(previousState);
        String current = normalizeState(currentState);
        if (current.isEmpty() || current.equals(previous)) {
            return RemoteCommand.NONE;
        }
        if ("PLAYING".equals(current)) {
            return RemoteCommand.PLAY;
        }
        if ("PAUSED_PLAYBACK".equals(current)
                || "STOPPED".equals(current)
                || "NO_MEDIA_PRESENT".equals(current)) {
            return RemoteCommand.PAUSE;
        }
        return RemoteCommand.NONE;
    }

    private static String normalizeState(String state) {
        return state == null ? "" : state.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private void runWhenTransitionReady(TransportAction action) throws IOException {
        IOException lastTransitionError = null;
        long deadlineNanos = System.nanoTime() + TRANSITION_SETTLE_TIMEOUT_MS * 1_000_000L;
        do {
            try {
                action.run();
                return;
            } catch (IOException error) {
                if (!DlnaAvTransportClient.isTransitionNotAvailable(error)) {
                    throw error;
                }
                lastTransitionError = error;
                sleepBeforeRetry();
            }
        } while (System.nanoTime() < deadlineNanos);
        String finalState = readTransportState();
        throw new IOException(lastTransitionError.getMessage()
                + "，电视状态：" + (finalState.isEmpty() ? "未知" : finalState),
                lastTransitionError);
    }

    private void stopCurrentTransportIfNeeded() throws IOException {
        String state = readTransportState();
        if (state.isEmpty() || "STOPPED".equalsIgnoreCase(state)
                || "NO_MEDIA_PRESENT".equalsIgnoreCase(state)) {
            return;
        }
        try {
            avTransport.stop();
            waitForTransportToSettle();
        } catch (IOException error) {
            // A live-TV input often reports 701 for Stop but can still accept a new URI.
            if (!DlnaAvTransportClient.isTransitionNotAvailable(error)) {
                throw error;
            }
        }
    }

    private void playWhenReady() throws IOException {
        IOException lastTransitionError = null;
        long deadlineNanos = System.nanoTime() + TRANSITION_SETTLE_TIMEOUT_MS * 1_000_000L;
        do {
            try {
                avTransport.play();
                return;
            } catch (IOException error) {
                if (!DlnaAvTransportClient.isTransitionNotAvailable(error)) {
                    throw error;
                }
                lastTransitionError = error;
                if (isPlaying(readTransportState())) {
                    return;
                }
                sleepBeforeRetry();
            }
        } while (System.nanoTime() < deadlineNanos);
        // Some LG renderers return 701 after accepting Play. A final state read
        // distinguishes that case from an actual unsupported transition.
        String finalState = readTransportState();
        if (!isPlaying(finalState)) {
            throw new IOException(lastTransitionError.getMessage()
                    + "，电视状态：" + (finalState.isEmpty() ? "未知" : finalState),
                    lastTransitionError);
        }
    }

    private String waitForTransportToSettle() {
        String lastState = "";
        long deadlineNanos = System.nanoTime() + TRANSITION_SETTLE_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadlineNanos) {
            try {
                lastState = avTransport.transportState();
                if (!"TRANSITIONING".equalsIgnoreCase(lastState)) {
                    return lastState;
                }
            } catch (IOException ignored) {
                // Some legacy renderers omit GetTransportInfo; the Play retry remains enough.
                return "";
            }
            sleepBeforeRetry();
        }
        return lastState;
    }

    private String readTransportState() {
        try {
            return avTransport.transportState();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static boolean isPlaying(String state) {
        return "PLAYING".equalsIgnoreCase(state);
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(TRANSITION_RETRY_INTERVAL_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface TransportAction {
        void run() throws IOException;
    }

    enum RemoteCommand {
        NONE,
        PLAY,
        PAUSE
    }
}
