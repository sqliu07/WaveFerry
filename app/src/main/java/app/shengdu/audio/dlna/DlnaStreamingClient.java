package app.shengdu.audio.dlna;

import app.shengdu.audio.format.AudioStreamFormat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Sends captured PCM to a MediaRenderer through a local WAV endpoint and AVTransport. */
public final class DlnaStreamingClient implements AutoCloseable {
    private static final long TRANSITION_RETRY_TIMEOUT_MS = 3_000L;
    private static final long TRANSITION_RETRY_INITIAL_MS = 100L;
    private static final long TRANSITION_RETRY_MAX_MS = 400L;
    private static final long REMOTE_STATE_POLL_INTERVAL_MS = 750L;
    private static final long POSITION_SYNC_DELAY_MS = 150L;

    public interface RemotePlaybackListener {
        void onRemotePlaybackRequested(boolean playing);
    }

    private final DlnaLocalAudioServer audioServer;
    private final DlnaAvTransportClient avTransport;
    private final RemotePlaybackListener remotePlaybackListener;
    private final Object metadataCacheLock = new Object();
    private final ScheduledExecutorService remoteStatePoller =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dlna-transport-state");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean prepared;
    private volatile boolean started;
    private volatile boolean closed;
    private boolean uriApplied;
    private volatile boolean localTransition;
    private volatile String lastObservedTransportState = "";
    private final AtomicLong localTransitionRevision = new AtomicLong();
    private URI mediaUri;
    private String appliedMetadataKey = "";
    private String lastTrackKey = "";
    private long pendingResumePositionMs;
    private final AtomicLong metadataRevision = new AtomicLong();
    private volatile PendingMetadata pendingMetadata;

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

    /** Starts the local endpoint and verifies that the renderer still answers AVTransport. */
    public synchronized void prepare() throws IOException {
        if (prepared) {
            return;
        }
        try {
            mediaUri = audioServer.start();
            // Do not assign or play the URI before Android capture is running. Some TVs
            // immediately pull and buffer the HTTP stream from SetAVTransportURI.
            try {
                lastObservedTransportState = normalizeState(avTransport.transportState());
            } catch (IOException error) {
                // A SOAP fault still proves that the advertised AVTransport endpoint is
                // reachable. Some legacy renderers reject GetTransportInfo but accept
                // SetAVTransportURI and Play, so defer that compatibility decision.
                if (!DlnaAvTransportClient.isUpnpFault(error)) {
                    throw error;
                }
                lastObservedTransportState = "";
            }
            prepared = true;
        } catch (IOException error) {
            mediaUri = null;
            audioServer.close();
            throw error;
        }
    }

    /** Assigns the live URI after AudioRecord starts and mirrors the cached source play state. */
    public synchronized void startPlayback() throws IOException {
        if (started) {
            return;
        }
        if (!prepared || mediaUri == null) {
            throw new IllegalStateException("DLNA renderer has not been prepared");
        }
        localTransitionRevision.incrementAndGet();
        localTransition = true;
        try {
            PendingMetadata metadata = applyLatestTransportMetadata();
            started = true;
            appliedMetadataKey = metadata == null ? "" : metadata.transportKey();
            lastTrackKey = metadata == null ? "" : metadata.trackKey;
            remoteStatePoller.scheduleWithFixedDelay(
                    this::pollRemoteTransportState,
                    REMOTE_STATE_POLL_INTERVAL_MS,
                    REMOTE_STATE_POLL_INTERVAL_MS,
                    TimeUnit.MILLISECONDS);
        } finally {
            localTransition = false;
        }
    }

    public void writePcm(byte[] buffer, int offset, int byteCount) {
        // Capture starts before SetAVTransportURI so the renderer receives current audio
        // as soon as it opens the HTTP stream. The bounded queue drops stale pre-roll.
        audioServer.writePcm(buffer, offset, byteCount);
    }

    /** Makes late artwork available to an in-flight renderer request without SOAP I/O. */
    public void cacheArtwork(byte[] artworkJpeg) {
        audioServer.updateArtwork(artworkJpeg);
    }

    public synchronized void pause() throws IOException {
        if (started) {
            localTransitionRevision.incrementAndGet();
            avTransport.cancelPendingSeek();
            avTransport.cancelPendingTransportPoll();
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
            avTransport.cancelPendingSeek();
            avTransport.cancelPendingTransportPoll();
            localTransition = true;
            try {
                playWhenReady();
                lastObservedTransportState = "PLAYING";
                long resumePositionMs = pendingResumePositionMs;
                pendingResumePositionMs = 0L;
                if (resumePositionMs > 0) {
                    schedulePositionSync(resumePositionMs);
                }
            } finally {
                localTransition = false;
            }
        }
    }

    /** Refreshes the renderer's now-playing fields without affecting the capture thread. */
    public void updateMetadata(
            String title,
            String artist,
            String album,
            String trackKey,
            long durationMs,
            long positionMs,
            byte[] artworkJpeg,
            boolean shouldPlay) throws IOException {
        long revision = cacheMetadata(
                title,
                artist,
                album,
                trackKey,
                durationMs,
                positionMs,
                artworkJpeg,
                shouldPlay);
        applyCachedMetadata(revision);
    }

    /** Applies a previously cached snapshot unless a newer callback has superseded it. */
    public synchronized void applyCachedMetadata(long revision) throws IOException {
        PendingMetadata next = pendingMetadata;
        if (revision <= 0L || next == null || next.revision != revision) {
            return;
        }
        if (!started || mediaUri == null || next.transportKey().equals(appliedMetadataKey)) {
            // Cache metadata received between receiver preparation and capture startup.
            return;
        }

        boolean trackChanged = !next.trackKey.equals(lastTrackKey);
        if (trackChanged) {
            // Do not let the tail of the previous track become the new track's pre-roll.
            audioServer.discardBufferedPcm();
        }

        localTransitionRevision.incrementAndGet();
        avTransport.cancelPendingSeek();
        avTransport.cancelPendingTransportPoll();
        localTransition = true;
        try {
            PendingMetadata applied = applyLatestTransportMetadata();
            appliedMetadataKey = applied == null ? "" : applied.transportKey();
            lastTrackKey = applied == null ? "" : applied.trackKey;
        } finally {
            localTransition = false;
        }
    }

    /** Stores metadata immediately without issuing SOAP, so playback startup can use it. */
    public long cacheMetadata(
            String title,
            String artist,
            String album,
            String trackKey,
            long durationMs,
            long positionMs,
            byte[] artworkJpeg,
            boolean shouldPlay) {
        if (trackKey == null || trackKey.isBlank()) {
            return 0L;
        }
        synchronized (metadataCacheLock) {
            if (closed) {
                return 0L;
            }
            long revision = metadataRevision.incrementAndGet();
            cacheArtwork(artworkJpeg);
            pendingMetadata = new PendingMetadata(
                    revision,
                    title,
                    artist,
                    album,
                    trackKey,
                    durationMs,
                    positionMs,
                    artworkJpeg,
                    shouldPlay);
            return revision;
        }
    }

    @Override
    public void close() {
        synchronized (metadataCacheLock) {
            if (closed) {
                return;
            }
            closed = true;
            metadataRevision.incrementAndGet();
            pendingMetadata = null;
        }
        avTransport.closeControlActions();
        localTransitionRevision.incrementAndGet();
        localTransition = true;
        avTransport.cancelPendingSeek();
        avTransport.cancelPendingTransportPoll();
        remoteStatePoller.shutdownNow();
        boolean stopRenderer;
        synchronized (this) {
            prepared = false;
            started = false;
            stopRenderer = uriApplied;
            uriApplied = false;
            mediaUri = null;
            appliedMetadataKey = "";
            lastTrackKey = "";
            pendingResumePositionMs = 0L;
        }
        if (stopRenderer) {
            try {
                avTransport.stopForTeardown();
            } catch (IOException ignored) {
                // The renderer may already be offline.
            }
        }
        audioServer.close();
        localTransition = false;
    }

    private PendingMetadata applyLatestTransportMetadata() throws IOException {
        stopCurrentTransportIfNeeded();
        // Snapshot after Stop: metadata callbacks remain memory-only and can still publish
        // a newer song or late artwork while a slow renderer completes the transition.
        PendingMetadata metadata = pendingMetadata;
        URI artworkUri = metadata == null || !metadata.hasArtwork
                ? null
                : audioServer.artworkUri(metadata.artworkHash);
        String didl = metadata == null
                ? DlnaLiveAudioMetadata.didlLite(mediaUri)
                : DlnaLiveAudioMetadata.didlLite(
                        mediaUri,
                        metadata.title,
                        metadata.artist,
                        metadata.album,
                        artworkUri,
                        metadata.durationMs);
        runWhenTransitionReady(() -> avTransport.setUri(mediaUri, didl));
        uriApplied = true;
        boolean shouldPlay = metadata == null || metadata.shouldPlay;
        if (shouldPlay) {
            pendingResumePositionMs = 0L;
            playWhenReady();
        }
        lastObservedTransportState = shouldPlay ? "PLAYING" : "STOPPED";
        if (shouldPlay && metadata != null && metadata.positionMs > 0) {
            schedulePositionSync(metadata.positionMs);
        } else if (!shouldPlay && metadata != null) {
            pendingResumePositionMs = metadata.positionMs;
        }
        return metadata;
    }

    private void schedulePositionSync(long positionMs) {
        if (positionMs <= 0) {
            return;
        }
        long expectedRevision = localTransitionRevision.get();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(TRANSITION_RETRY_TIMEOUT_MS);
        schedulePositionSyncAttempt(
                expectedRevision,
                positionMs,
                POSITION_SYNC_DELAY_MS,
                TRANSITION_RETRY_INITIAL_MS,
                deadlineNanos);
    }

    private void schedulePositionSyncAttempt(
            long expectedRevision,
            long positionMs,
            long scheduleDelayMs,
            long retryDelayMs,
            long deadlineNanos) {
        try {
            remoteStatePoller.schedule(
                    () -> syncPositionWhenReady(
                            expectedRevision, positionMs, retryDelayMs, deadlineNanos),
                    scheduleDelayMs,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Session teardown won the race; no renderer remains to synchronize.
        }
    }

    private void syncPositionWhenReady(
            long expectedRevision,
            long positionMs,
            long retryDelayMs,
            long deadlineNanos) {
        long seekRevision;
        synchronized (this) {
            if (!started
                    || localTransition
                    || expectedRevision != localTransitionRevision.get()) {
                return;
            }
            seekRevision = avTransport.currentSeekRevision();
        }
        boolean retry = false;
        try {
            avTransport.seekTo(positionMs, seekRevision);
        } catch (IOException error) {
            // REL_TIME is optional; audio streaming must continue without it.
            retry = DlnaAvTransportClient.isTransitionNotAvailable(error)
                    && System.nanoTime() < deadlineNanos;
        }
        synchronized (this) {
            if (retry
                    && started
                    && expectedRevision == localTransitionRevision.get()) {
                schedulePositionSyncAttempt(
                        expectedRevision,
                        positionMs,
                        retryDelayMs,
                        Math.min(TRANSITION_RETRY_MAX_MS, retryDelayMs * 2L),
                        deadlineNanos);
            }
        }
    }

    private void pollRemoteTransportState() {
        if (!started || localTransition) {
            return;
        }
        long revision = localTransitionRevision.get();
        final String state;
        try {
            state = normalizeState(avTransport.transportStateForPolling());
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
        long retryDelayMs = TRANSITION_RETRY_INITIAL_MS;
        long deadlineNanos = System.nanoTime() + TRANSITION_RETRY_TIMEOUT_MS * 1_000_000L;
        do {
            try {
                action.run();
                return;
            } catch (IOException error) {
                if (!DlnaAvTransportClient.isTransitionNotAvailable(error)) {
                    throw error;
                }
                lastTransitionError = error;
                sleepBeforeRetry(retryDelayMs);
                retryDelayMs = Math.min(TRANSITION_RETRY_MAX_MS, retryDelayMs * 2L);
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
        } catch (IOException error) {
            // A live-TV input often reports 701 for Stop but can still accept a new URI.
            if (!DlnaAvTransportClient.isTransitionNotAvailable(error)) {
                throw error;
            }
        }
    }

    private void playWhenReady() throws IOException {
        IOException lastTransitionError = null;
        long retryDelayMs = TRANSITION_RETRY_INITIAL_MS;
        long deadlineNanos = System.nanoTime() + TRANSITION_RETRY_TIMEOUT_MS * 1_000_000L;
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
                sleepBeforeRetry(retryDelayMs);
                retryDelayMs = Math.min(TRANSITION_RETRY_MAX_MS, retryDelayMs * 2L);
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

    private static void sleepBeforeRetry(long delayMs) throws IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted =
                    new InterruptedIOException("DLNA transition retry interrupted");
            interrupted.initCause(error);
            throw interrupted;
        }
    }

    @FunctionalInterface
    private interface TransportAction {
        void run() throws IOException;
    }

    private static final class PendingMetadata {
        private final long revision;
        private final String title;
        private final String artist;
        private final String album;
        private final String trackKey;
        private final long durationMs;
        private final long positionMs;
        private final boolean hasArtwork;
        private final int artworkHash;
        private final boolean shouldPlay;

        private PendingMetadata(
                long revision,
                String title,
                String artist,
                String album,
                String trackKey,
                long durationMs,
                long positionMs,
                byte[] artworkJpeg,
                boolean shouldPlay) {
            this.revision = revision;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.trackKey = trackKey;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.hasArtwork = artworkJpeg != null && artworkJpeg.length > 0;
            this.artworkHash = hasArtwork ? Arrays.hashCode(artworkJpeg) : 0;
            this.shouldPlay = shouldPlay;
        }

        private String transportKey() {
            return trackKey + '\n' + durationMs + '\n'
                    + (hasArtwork ? Integer.toHexString(artworkHash) : "-");
        }
    }

    enum RemoteCommand {
        NONE,
        PLAY,
        PAUSE
    }
}
