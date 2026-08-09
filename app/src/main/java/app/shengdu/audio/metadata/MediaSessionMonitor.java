package app.shengdu.audio.metadata;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Watches active media sessions and reports the currently playing track through
 * {@link Listener}. Used by MainActivity (display) and StreamingService (RAOP send);
 * both share the pure {@link SessionSelector} so the screen shows exactly what would
 * be sent. Requires notification-listener access for {@link MediaSessionListenerService};
 * without it {@link #start()} returns false and the monitor stays inert.
 *
 * All callbacks run on the supplied handler, which also serializes internal state.
 * Metadata lives only in memory: nothing is logged, persisted, or broadcast.
 */
public final class MediaSessionMonitor {
    private static final Set<String> BONUS_PACKAGES = Set.of("com.apple.android.music");

    public interface Listener {
        /**
         * Called on the callback handler; {@code track} is null when no session is usable.
         * {@code artwork} is the current album art bitmap, or null when the session has
         * none. Listeners must not retain or recycle the bitmap.
         */
        void onTrackChanged(TrackMetadata track, Bitmap artwork);
    }

    private final Context appContext;
    private final Handler handler;
    private final Listener listener;
    private final ComponentName listenerComponent;
    private final MediaSessionManager sessionManager;
    private final SessionSelector selector;
    private final Object lifecycleLock = new Object();

    // Registration happens on the caller thread so start() can report permission
    // failure; these flags bridge that lifecycle safely to the callback handler.
    private boolean registered;
    private boolean stopRequested;
    private boolean running;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            this::onSessionsChanged;
    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            onSelectedControllerEvent();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            onSelectedControllerEvent();
        }

        @Override
        public void onSessionDestroyed() {
            onSelectedControllerEvent();
        }
    };

    // Everything below is only touched on the callback handler thread.
    private List<MediaController> controllers = Collections.emptyList();
    private MediaController selectedController;
    private TrackMetadata lastEmitted;
    private Bitmap lastEmittedArtwork;
    private boolean emittedOnce;

    public MediaSessionMonitor(Context context, Handler callbackHandler, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.handler = callbackHandler;
        this.listener = listener;
        this.listenerComponent = new ComponentName(appContext, MediaSessionListenerService.class);
        this.sessionManager =
                (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
        this.selector = new SessionSelector(appContext.getPackageName(), BONUS_PACKAGES);
    }

    /**
     * Starts watching. Returns false when notification-listener access is not granted;
     * in that case nothing is registered and the listener is never called.
     */
    public boolean start() {
        final List<MediaController> initial;
        synchronized (lifecycleLock) {
            if (stopRequested) {
                return false;
            }
            if (registered) {
                return true;
            }
            try {
                initial = sessionManager.getActiveSessions(listenerComponent);
                sessionManager.addOnActiveSessionsChangedListener(
                        sessionsChangedListener, listenerComponent, handler);
            } catch (SecurityException exception) {
                return false;
            }
            registered = true;
        }
        handler.post(() -> {
            synchronized (lifecycleLock) {
                if (!registered || stopRequested) {
                    return;
                }
                if (running) {
                    return;
                }
                running = true;
            }
            evaluate(initial);
        });
        return true;
    }

    /** Stops watching. Idempotent and safe to call from any thread. */
    public void stop() {
        boolean unregister;
        synchronized (lifecycleLock) {
            stopRequested = true;
            unregister = registered;
            registered = false;
            running = false;
        }
        if (unregister) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (RuntimeException ignored) {
                // The media-session service may be shutting down with the owner.
            }
        }
        handler.post(() -> {
            bindSelectedController(null);
            controllers = Collections.emptyList();
            selector.reset();
            lastEmitted = null;
            lastEmittedArtwork = null;
            emittedOnce = false;
        });
    }

    /** Requests playback control on the same media session selected for metadata. */
    public void requestPause() {
        postTransportControl(TransportControl.PAUSE);
    }

    public void requestPlay() {
        postTransportControl(TransportControl.PLAY);
    }

    public void requestPlayPause() {
        postTransportControl(TransportControl.PLAY_PAUSE);
    }

    public void requestSkipToNext() {
        postTransportControl(TransportControl.NEXT);
    }

    public void requestSkipToPrevious() {
        postTransportControl(TransportControl.PREVIOUS);
    }

    private void postTransportControl(TransportControl control) {
        handler.post(() -> {
            MediaController controller = selectedController;
            if (!isRunning() || controller == null) {
                return;
            }
            try {
                MediaController.TransportControls controls = controller.getTransportControls();
                switch (control) {
                    case PLAY:
                        controls.play();
                        break;
                    case PAUSE:
                        controls.pause();
                        break;
                    case PLAY_PAUSE:
                        PlaybackState state = controller.getPlaybackState();
                        if (isActivePlaybackState(state)) {
                            controls.pause();
                        } else {
                            controls.play();
                        }
                        break;
                    case NEXT:
                        controls.skipToNext();
                        break;
                    case PREVIOUS:
                        controls.skipToPrevious();
                        break;
                }
            } catch (RuntimeException ignored) {
                // The source app may destroy its MediaSession between selection and dispatch.
            }
        });
    }

    private static boolean isActivePlaybackState(PlaybackState state) {
        if (state == null) {
            return false;
        }
        return switch (state.getState()) {
            case PlaybackState.STATE_PLAYING,
                    PlaybackState.STATE_BUFFERING,
                    PlaybackState.STATE_CONNECTING,
                    PlaybackState.STATE_FAST_FORWARDING,
                    PlaybackState.STATE_REWINDING,
                    PlaybackState.STATE_SKIPPING_TO_NEXT,
                    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
                    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true;
            default -> false;
        };
    }

    private void onSessionsChanged(List<MediaController> activeControllers) {
        if (isRunning()) {
            evaluate(activeControllers);
        }
    }

    private void onSelectedControllerEvent() {
        if (isRunning()) {
            evaluate(controllers);
        }
    }

    private boolean isRunning() {
        synchronized (lifecycleLock) {
            return running && registered && !stopRequested;
        }
    }

    private void evaluate(List<MediaController> activeControllers) {
        controllers = activeControllers == null ? Collections.emptyList() : activeControllers;

        List<SessionSelector.Descriptor> descriptors = new ArrayList<>(controllers.size());
        for (MediaController controller : controllers) {
            descriptors.add(toDescriptor(controller));
        }
        Object selectedId = selector.update(descriptors);

        MediaController selected = null;
        if (selectedId != null) {
            for (MediaController controller : controllers) {
                if (selectedId.equals(controller.getSessionToken())) {
                    selected = controller;
                    break;
                }
            }
        }
        bindSelectedController(selected);
        emit(currentTrack(), currentArtwork());
    }

    private void bindSelectedController(MediaController controller) {
        if (selectedController == controller) {
            return;
        }
        if (selectedController != null) {
            selectedController.unregisterCallback(controllerCallback);
        }
        selectedController = controller;
        if (controller != null) {
            controller.registerCallback(controllerCallback, handler);
        }
    }

    private TrackMetadata currentTrack() {
        if (selectedController == null) {
            return null;
        }
        MediaMetadata metadata = selectedController.getMetadata();
        PlaybackState playbackState = selectedController.getPlaybackState();
        boolean playing = playbackState != null
                && playbackState.getState() == PlaybackState.STATE_PLAYING;
        return new TrackMetadata(
                selectedController.getPackageName(),
                firstText(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID, null),
                queueItemId(playbackState),
                firstText(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                firstText(metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                firstText(metadata, MediaMetadata.METADATA_KEY_ALBUM, null),
                metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs(playbackState, playing),
                playing);
    }

    private static String queueItemId(PlaybackState playbackState) {
        if (playbackState == null) {
            return "";
        }
        long itemId = playbackState.getActiveQueueItemId();
        return itemId == MediaSession.QueueItem.UNKNOWN_ID ? "" : Long.toString(itemId);
    }

    private static long positionMs(PlaybackState playbackState, boolean playing) {
        if (playbackState == null) {
            return 0;
        }
        long position = playbackState.getPosition();
        if (playing && playbackState.getPlaybackSpeed() > 0) {
            long updatedAt = playbackState.getLastPositionUpdateTime();
            long now = android.os.SystemClock.elapsedRealtime();
            if (updatedAt > 0 && now >= updatedAt) {
                position += (long) ((now - updatedAt) * playbackState.getPlaybackSpeed());
            }
        }
        return Math.max(0, position);
    }

    private Bitmap currentArtwork() {
        if (selectedController == null) {
            return null;
        }
        MediaMetadata metadata = selectedController.getMetadata();
        if (metadata == null) {
            return null;
        }
        Bitmap artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (artwork == null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        }
        return artwork;
    }

    private void emit(TrackMetadata track, Bitmap artwork) {
        // Position alone must never trigger an emit (some players report it every
        // second); only a different track or a play/pause transition does.
        boolean trackChanged = lastEmitted == null
                ? track != null
                : track == null
                        || !lastEmitted.sameTrack(track)
                        || !lastEmitted.dedupKey().equals(track.dedupKey())
                        || lastEmitted.isPlaying() != track.isPlaying();
        boolean artworkChanged = lastEmittedArtwork != artwork;
        if (emittedOnce && !trackChanged && !artworkChanged) {
            return;
        }
        emittedOnce = true;
        lastEmitted = track;
        lastEmittedArtwork = artwork;
        listener.onTrackChanged(track, artwork);
    }

    private SessionSelector.Descriptor toDescriptor(MediaController controller) {
        PlaybackState playbackState = controller.getPlaybackState();
        int stateValue = playbackState == null ? PlaybackState.STATE_NONE : playbackState.getState();
        SessionSelector.State state;
        switch (stateValue) {
            case PlaybackState.STATE_PLAYING:
                state = SessionSelector.State.PLAYING;
                break;
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_PAUSED:
                state = SessionSelector.State.PAUSED_OR_BUFFERING;
                break;
            default:
                state = SessionSelector.State.OTHER;
                break;
        }
        MediaMetadata metadata = controller.getMetadata();
        boolean hasTitle = !firstText(
                metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isEmpty();
        boolean hasArtist = !firstText(
                metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).isEmpty();
        return new SessionSelector.Descriptor(
                controller.getSessionToken(), controller.getPackageName(), state, hasTitle, hasArtist);
    }

    private static String firstText(MediaMetadata metadata, String primary, String fallback) {
        if (metadata == null) {
            return "";
        }
        CharSequence value = metadata.getText(primary);
        if (value == null && fallback != null) {
            value = metadata.getText(fallback);
        }
        return value == null ? "" : value.toString();
    }

    private enum TransportControl {
        PLAY,
        PAUSE,
        PLAY_PAUSE,
        NEXT,
        PREVIOUS
    }
}
