package app.shengdu.audio;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;

import app.shengdu.audio.audio.DeviceMutePolicy;
import app.shengdu.audio.capture.PlaybackCapture;
import app.shengdu.audio.dacp.DacpControlServer;
import app.shengdu.audio.dacp.DacpSession;
import app.shengdu.audio.dlna.DlnaRendererDescription;
import app.shengdu.audio.dlna.DlnaStreamingClient;
import app.shengdu.audio.metadata.MediaSessionMonitor;
import app.shengdu.audio.metadata.MetadataSendPolicy;
import app.shengdu.audio.metadata.PlaybackFlowGate;
import app.shengdu.audio.metadata.TrackMetadata;
import app.shengdu.audio.metadata.TrackProgressSender;
import app.shengdu.audio.raop.MetadataSupport;
import app.shengdu.audio.raop.RaopStreamingClient;
import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.FormatNegotiator;
import app.shengdu.audio.format.ReceiverCapabilities;
import app.shengdu.audio.format.SampleFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps system playback capture and the RAOP session alive while the app is backgrounded. */
public final class StreamingService extends Service {
    public static final String ACTION_STATUS = "app.shengdu.audio.action.STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_RECEIVER_KEY = "receiver_key";

    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_STOPPING = "stopping";
    public static final String STATUS_AWAITING_CAPTURE = "awaiting_capture";
    public static final String STATUS_STREAMING = "streaming";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_ERROR = "error";

    private static final String ACTION_PREPARE = "app.shengdu.audio.action.PREPARE";
    private static final String ACTION_ATTACH_CAPTURE = "app.shengdu.audio.action.ATTACH_CAPTURE";
    private static final String ACTION_STOP = "app.shengdu.audio.action.STOP";
    private static final String EXTRA_PROJECTION_RESULT = "projection_result";
    private static final String EXTRA_PROJECTION_DATA = "projection_data";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_RECEIVER_NAME = "receiver_name";
    private static final String EXTRA_MUTE_DEVICE = "mute_device";
    private static final String EXTRA_AUDIO_QUALITY = "audio_quality";
    private static final String EXTRA_TXT_KEYS = "txt_keys";
    private static final String EXTRA_TXT_PREFIX = "txt_";

    private static final String NOTIFICATION_CHANNEL_ID = "streaming";
    private static final int NOTIFICATION_ID = 1001;
    private static final int ERROR_NOTIFICATION_ID = 1002;
    private static volatile StatusSnapshot lastStatus = new StatusSnapshot("", "", "");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "shengdu-session");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger sessionGeneration = new AtomicInteger();

    private RaopStreamingClient raopClient;
    private DlnaStreamingClient dlnaClient;
    private PlaybackCapture playbackCapture;
    private MediaProjection mediaProjection;
    private DacpSession dacpSession;
    private WifiManager.WifiLock wifiLock;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private MediaSessionMonitor metadataMonitor;
    private HandlerThread metadataThread;
    private MetadataSendPolicy metadataPolicy;
    private MetadataSendPolicy artworkPolicy;
    private MetadataSendPolicy progressPolicy;
    private AudioStreamFormat activeFormat;
    private String activeReceiverName = "";
    private volatile String activeReceiverKey = "";
    private boolean awaitingCapture;
    private boolean activeMuteDevice;
    private final DeviceMutePolicy mutePolicy = new DeviceMutePolicy();
    private final PlaybackFlowGate playbackFlowGate = new PlaybackFlowGate();
    private final TrackProgressSender trackProgressSender = new TrackProgressSender();

    private static final int ARTWORK_MAX_DIMENSION = 512;
    private static final int ARTWORK_JPEG_QUALITY = 85;

    public static Intent createPrepareIntent(
            Context context,
            InetAddress receiverAddress,
            int receiverPort,
            String receiverName,
            String receiverKey,
            Map<String, byte[]> txtAttributes,
            boolean muteDevice,
            AppPreferences.AudioQuality audioQuality) {
        Intent intent = new Intent(context, StreamingService.class).setAction(ACTION_PREPARE);
        intent.putExtra(EXTRA_HOST, receiverAddress.getHostAddress());
        intent.putExtra(EXTRA_PORT, receiverPort);
        intent.putExtra(EXTRA_RECEIVER_NAME, receiverName);
        intent.putExtra(EXTRA_RECEIVER_KEY, receiverKey);
        intent.putExtra(EXTRA_MUTE_DEVICE, muteDevice);
        intent.putExtra(EXTRA_AUDIO_QUALITY, audioQuality.name());

        String[] keys = txtAttributes.keySet().toArray(new String[0]);
        intent.putExtra(EXTRA_TXT_KEYS, keys);
        for (String key : keys) {
            intent.putExtra(EXTRA_TXT_PREFIX + key, txtAttributes.get(key));
        }
        return intent;
    }

    public static Intent createAttachCaptureIntent(
            Context context, int projectionResult, Intent projectionData) {
        return new Intent(context, StreamingService.class)
                .setAction(ACTION_ATTACH_CAPTURE)
                .putExtra(EXTRA_PROJECTION_RESULT, projectionResult)
                .putExtra(EXTRA_PROJECTION_DATA, projectionData);
    }

    public static Intent createStopIntent(Context context) {
        return new Intent(context, StreamingService.class).setAction(ACTION_STOP);
    }

    public static StatusSnapshot getLastStatus() {
        return lastStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            sessionGeneration.incrementAndGet();
            broadcastStatus(STATUS_STOPPING, getString(R.string.status_stopping));
            worker.execute(() -> stopSession(true));
            return START_NOT_STICKY;
        }
        if (ACTION_ATTACH_CAPTURE.equals(intent.getAction())) {
            startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.status_starting_capture)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            int generation = sessionGeneration.get();
            worker.execute(() -> attachPlaybackCapture(intent, generation));
            return START_NOT_STICKY;
        }
        if (!ACTION_PREPARE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String requestedReceiverName = intent.getStringExtra(EXTRA_RECEIVER_NAME);
        if (requestedReceiverName == null || requestedReceiverName.isBlank()) {
            requestedReceiverName = "AirPlay 设备";
        }
        int generation = sessionGeneration.incrementAndGet();
        String sessionReceiverName = requestedReceiverName;
        activeReceiverKey = intent.getStringExtra(EXTRA_RECEIVER_KEY);
        if (activeReceiverKey == null) {
            activeReceiverKey = "";
        }
        startInForeground(sessionReceiverName);
        worker.execute(() -> prepareSession(intent, generation, sessionReceiverName));
        return START_NOT_STICKY;
    }

    private void prepareSession(Intent intent, int generation, String sessionReceiverName) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        stopSession(false);
        if (!isCurrentGeneration(generation)) {
            return;
        }
        stopping.set(false);
        playbackFlowGate.reset();
        try {
            broadcastStatus(
                    STATUS_CONNECTING,
                    getString(R.string.status_connecting_receiver, sessionReceiverName));
            acquireNetworkLocks();

            InetAddress host = InetAddress.getByName(requiredStringExtra(intent, EXTRA_HOST));
            int port = intent.getIntExtra(EXTRA_PORT, -1);
            if (port <= 0 || port > 65_535) {
                throw new IllegalArgumentException("接收器端口无效");
            }

            Map<String, byte[]> txt = readTxtAttributes(intent);
            boolean dlnaTarget = isDlnaTarget(txt);
            // The live DLNA endpoint currently advertises PCM/WAV at 44.1 kHz. Do not
            // reuse an AirPlay 48 kHz preference and make the receiver reject its MIME profile.
            AudioStreamFormat selectedFormat = dlnaTarget
                    ? new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE)
                    : selectTransportFormat(txt, parseAudioQuality(intent));
            boolean mediaSessionAvailable = startMetadata(generation);
            if (stopIfStale(generation)) {
                return;
            }
            if (dlnaTarget) {
                DlnaStreamingClient client = createDlnaClient(
                        host, sessionReceiverName, txt, selectedFormat, generation);
                dlnaClient = client;
                client.start();
            } else {
                DacpSession remoteControl = mediaSessionAvailable
                        ? startDacpSession(generation, host)
                        : null;
                if (stopIfStale(generation)) {
                    return;
                }
                RaopStreamingClient client = remoteControl == null
                        ? new RaopStreamingClient(host, port, txt, selectedFormat)
                        : new RaopStreamingClient(
                                host,
                                port,
                                txt,
                                remoteControl.dacpId(),
                                remoteControl.activeRemote(),
                                selectedFormat);
                configureMetadata(client);
                client.connect();
                raopClient = client;
            }
            if (stopIfStale(generation)) {
                return;
            }
            activeFormat = selectedFormat;
            activeReceiverName = sessionReceiverName;
            activeMuteDevice = intent.getBooleanExtra(EXTRA_MUTE_DEVICE, false);
            awaitingCapture = true;
            broadcastStatus(
                    STATUS_AWAITING_CAPTURE,
                    getString(R.string.status_receiver_connected));
        } catch (Throwable error) {
            if (sessionGeneration.compareAndSet(generation, generation + 1)) {
                broadcastStatus(STATUS_ERROR, readableError(error));
                stopSession(true, false);
            } else {
                stopSession(false);
            }
        }
    }

    private void attachPlaybackCapture(Intent intent, int generation) {
        if (!isCurrentGeneration(generation) || stopping.get()) {
            return;
        }
        AudioStreamFormat format = activeFormat;
        if (!awaitingCapture || format == null || (raopClient == null && dlnaClient == null)) {
            broadcastStatus(STATUS_ERROR, getString(R.string.capture_session_missing));
            stopSession(true, false);
            return;
        }
        try {
            broadcastStatus(STATUS_CONNECTING, getString(R.string.status_starting_capture));
            startPlaybackCapture(intent, generation, format);
            if (stopIfStale(generation)) {
                return;
            }
            awaitingCapture = false;
            broadcastStatus(
                    STATUS_STREAMING,
                    getString(R.string.status_streaming_receiver, activeReceiverName));
            applyDeviceMute(activeMuteDevice);
        } catch (Throwable error) {
            if (sessionGeneration.compareAndSet(generation, generation + 1)) {
                broadcastStatus(STATUS_ERROR, readableError(error));
                stopSession(true, false);
            } else {
                stopSession(false);
            }
        }
    }

    private boolean isCurrentGeneration(int generation) {
        return sessionGeneration.get() == generation;
    }

    private void startPlaybackCapture(
            Intent intent, int generation, AudioStreamFormat selectedFormat) {
        Intent projectionData = getProjectionData(intent);
        int resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0);
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        MediaProjection projection = manager.getMediaProjection(resultCode, projectionData);
        if (projection == null) {
            throw new IllegalStateException("未获得系统音频捕获授权");
        }
        mediaProjection = projection;

        PlaybackCapture capture = new PlaybackCapture(
                projection,
                (buffer, byteCount, framePosition) -> writePcm(buffer, byteCount),
                new CaptureListener(generation),
                selectedFormat);
        playbackCapture = capture;
        if (!capture.start()) {
            throw new IllegalStateException("无法启动系统播放音频捕获");
        }
    }

    private static AudioStreamFormat selectTransportFormat(
            Map<String, byte[]> txt,
            AppPreferences.AudioQuality quality) {
        AudioStreamFormat pcm44 = new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE);
        AudioStreamFormat pcm48 = new AudioStreamFormat(48_000, 2, SampleFormat.PCM16_LE);
        ReceiverCapabilities capabilities = ReceiverCapabilities.fromTxt(txt);
        if (quality == AppPreferences.AudioQuality.PCM16_44_1) {
            return chooseRequestedOrFallback(pcm44, pcm48, capabilities);
        }
        if (quality == AppPreferences.AudioQuality.PCM16_48) {
            return chooseRequestedOrFallback(pcm48, pcm44, capabilities);
        }
        AudioStreamFormat selected = FormatNegotiator.choose(
                Arrays.asList(pcm44, pcm48), capabilities);
        // Missing TXT format fields mean unknown rather than refusal; preserve the
        // long-standing 44.1 kHz default in that case.
        return selected == null ? pcm44 : selected;
    }

    private static AudioStreamFormat chooseRequestedOrFallback(
            AudioStreamFormat requested,
            AudioStreamFormat fallback,
            ReceiverCapabilities capabilities) {
        // A manually selected rate is a preference, not permission to violate the
        // receiver's advertised RAOP capabilities. Older receivers commonly expose
        // only 44.1 kHz; keep the session usable instead of failing during ANNOUNCE.
        if (capabilities.supports(requested)) {
            return requested;
        }
        if (capabilities.supports(fallback)) {
            return fallback;
        }
        // Empty/unknown TXT fields are treated as unknown by ReceiverCapabilities.
        // Let the RAOP client perform its normal compatibility check in that case.
        return requested;
    }

    private static AppPreferences.AudioQuality parseAudioQuality(Intent intent) {
        String value = intent.getStringExtra(EXTRA_AUDIO_QUALITY);
        if (value == null) {
            return AppPreferences.AudioQuality.AUTO;
        }
        try {
            return AppPreferences.AudioQuality.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return AppPreferences.AudioQuality.AUTO;
        }
    }

    private static boolean isDlnaTarget(Map<String, byte[]> attributes) {
        byte[] value = attributes.get("transport");
        return value != null && "dlna".equalsIgnoreCase(new String(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    private DlnaStreamingClient createDlnaClient(
            InetAddress host,
            String receiverName,
            Map<String, byte[]> attributes,
            AudioStreamFormat format,
            int generation) {
        String controlUrl = requiredDlnaAttribute(attributes, "dlna_av_transport_url");
        String serviceType = requiredDlnaAttribute(attributes, "dlna_av_transport_type");
        URI descriptionUri = URI.create("http://" + host.getHostAddress() + "/");
        DlnaRendererDescription renderer = new DlnaRendererDescription(
                receiverName,
                receiverName,
                descriptionUri,
                URI.create(controlUrl),
                serviceType,
                null,
                "",
                null);
        return new DlnaStreamingClient(
                host,
                renderer,
                format,
                playing -> worker.execute(
                        () -> dispatchDlnaPlaybackRequest(generation, playing)));
    }

    private static String requiredDlnaAttribute(Map<String, byte[]> attributes, String key) {
        byte[] value = attributes.get(key);
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("DLNA 接收器缺少 " + key);
        }
        return new String(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private boolean stopIfStale(int generation) {
        if (isCurrentGeneration(generation)) {
            return false;
        }
        stopSession(false);
        return true;
    }

    private void writePcm(byte[] buffer, int byteCount) {
        DlnaStreamingClient dlna = dlnaClient;
        if (dlna != null) {
            if (!stopping.get() && dlna == dlnaClient && playbackFlowGate.shouldForwardPcm()) {
                dlna.writePcm(buffer, 0, byteCount);
            }
            return;
        }
        RaopStreamingClient client = raopClient;
        if (client == null || stopping.get()) {
            return;
        }
        synchronized (client) {
            if (stopping.get() || client != raopClient || !playbackFlowGate.shouldForwardPcm()) {
                return;
            }
            try {
                client.writePcm(buffer, 0, byteCount);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }

    /**
     * Optional track-metadata chain: MediaSession -> RTSP SET_PARAMETER. Everything runs
     * off the audio path; any failure only disables that metadata kind for this session.
     */
    private boolean startMetadata(int generation) {
        HandlerThread thread = new HandlerThread("shengdu-metadata");
        thread.start();
        MediaSessionMonitor monitor = new MediaSessionMonitor(
                getApplicationContext(),
                new Handler(thread.getLooper()),
                (track, artwork) -> onMetadataChanged(generation, track, artwork));

        metadataThread = thread;
        metadataMonitor = monitor;
        if (!monitor.start()) {
            // Notification-listener access not granted: metadata and DACP stay off.
            stopMetadata();
            return false;
        }
        return true;
    }

    private void configureMetadata(RaopStreamingClient client) {
        MetadataSupport textSupport = client.textMetadataSupport();
        MetadataSupport artSupport = client.artworkSupport();
        MetadataSupport progSupport = client.progressSupport();
        metadataPolicy = policyFor(textSupport);
        artworkPolicy = policyFor(artSupport);
        progressPolicy = policyFor(progSupport);
    }

    private void onMetadataChanged(int generation, TrackMetadata track, Bitmap artwork) {
        PlaybackFlowGate.Transition transition = playbackFlowGate.update(track);
        long revision = playbackFlowGate.revision();
        RaopStreamingClient connectedClient = raopClient;
        if (transition == PlaybackFlowGate.Transition.PAUSED && connectedClient != null) {
            try {
                connectedClient.flushAudio();
            } catch (IOException | RuntimeException ignored) {
                // Best-effort transport freeze; metadata still continues.
            }
        }
        DlnaStreamingClient connectedDlna = dlnaClient;
        if (connectedDlna != null && transition != PlaybackFlowGate.Transition.NONE) {
            worker.execute(() -> {
                try {
                    if (transition == PlaybackFlowGate.Transition.PAUSED) {
                        connectedDlna.pause();
                    } else if (transition == PlaybackFlowGate.Transition.RESUMED) {
                        connectedDlna.resume();
                    }
                } catch (IOException ignored) {
                    // Some DLNA renderers do not implement PAUSE reliably. PCM gating still applies.
                }
            });
        }
        // Scaling and JPEG compression run here, on the metadata thread — never
        // on the capture thread or the main thread.
        byte[] artworkJpeg = encodeArtwork(artwork);
        PendingMetadata pending = new PendingMetadata(track, artworkJpeg, transition, revision);
        worker.execute(() -> sendPendingMetadata(generation, pending));
    }

    private DacpSession startDacpSession(int generation, InetAddress host) {
        try {
            DacpSession started = DacpSession.start(
                    getApplicationContext(),
                    host,
                    command -> worker.execute(() -> dispatchDacpCommand(generation, command)));
            dacpSession = started;
            return started;
        } catch (IOException | RuntimeException ignored) {
            // Reverse control is optional; RAOP audio remains usable without it.
            return null;
        }
    }

    private void dispatchDacpCommand(int generation, DacpControlServer.Command command) {
        if (!isCurrentGeneration(generation) || stopping.get()) {
            return;
        }
        MediaSessionMonitor monitor = metadataMonitor;
        if (monitor == null) {
            return;
        }
        switch (command) {
            case PLAY:
                monitor.requestPlay();
                break;
            case PAUSE:
                monitor.requestPause();
                break;
            case PLAY_PAUSE:
                monitor.requestPlayPause();
                break;
            case NEXT_ITEM:
                monitor.requestSkipToNext();
                break;
            case PREVIOUS_ITEM:
                monitor.requestSkipToPrevious();
                break;
        }
    }

    private void dispatchDlnaPlaybackRequest(int generation, boolean playing) {
        if (!isCurrentGeneration(generation) || stopping.get() || dlnaClient == null) {
            return;
        }
        MediaSessionMonitor monitor = metadataMonitor;
        if (monitor == null) {
            return;
        }
        if (playing) {
            monitor.requestPlay();
        } else {
            monitor.requestPause();
        }
    }

    private static MetadataSendPolicy policyFor(MetadataSupport support) {
        if (support == MetadataSupport.NOT_ADVERTISED) {
            return null;
        }
        return new MetadataSendPolicy(support == MetadataSupport.ADVERTISED
                ? MetadataSendPolicy.Mode.ADVERTISED
                : MetadataSendPolicy.Mode.UNKNOWN);
    }

    private void sendPendingMetadata(int generation, PendingMetadata pending) {
        if (pending == null || !isCurrentGeneration(generation)) {
            return;
        }
        if (pending.transition == PlaybackFlowGate.Transition.PAUSED
                && pending.revision != playbackFlowGate.revision()) {
            return;
        }
        RaopStreamingClient raop = raopClient;
        if (raop != null) {
            sendTextMetadata(raop, pending.track);
            sendArtworkMetadata(raop, pending.artworkJpeg);
            sendProgressMetadata(raop, pending.track);
        }
        DlnaStreamingClient dlna = dlnaClient;
        TrackMetadata track = pending.track;
        if (dlna != null && track != null && !track.isEmpty()) {
            try {
                dlna.updateMetadata(
                        track.getTitle(),
                        track.getArtist(),
                        track.getAlbum(),
                        track.dedupKey(),
                        track.getDurationMs(),
                        track.getPositionMs(),
                        pending.artworkJpeg,
                        track.isPlaying());
            } catch (IOException | RuntimeException ignored) {
                // Metadata is optional; an incompatible renderer must not stop live audio.
            }
        }
    }

    private void sendTextMetadata(RaopStreamingClient client, TrackMetadata track) {
        MetadataSendPolicy policy = metadataPolicy;
        if (policy == null || track == null || !policy.shouldSend(track.dedupKey())) {
            return;
        }
        try {
            client.sendTrackMetadata(track.getTitle(), track.getArtist(), track.getAlbum());
            policy.onSendSuccess();
        } catch (IOException | RuntimeException ignored) {
            policy.onSendFailure();
        }
    }

    private void sendArtworkMetadata(RaopStreamingClient client, byte[] artworkJpeg) {
        MetadataSendPolicy policy = artworkPolicy;
        if (policy == null
                || artworkJpeg == null
                || !policy.shouldSend(Integer.toHexString(Arrays.hashCode(artworkJpeg)))) {
            return;
        }
        try {
            client.sendTrackArtwork(artworkJpeg);
            policy.onSendSuccess();
        } catch (IOException | RuntimeException ignored) {
            policy.onSendFailure();
        }
    }

    private void sendProgressMetadata(RaopStreamingClient client, TrackMetadata track) {
        // Play/pause transitions re-send once; position-only changes never reach here.
        trackProgressSender.send(
                progressPolicy,
                track,
                client.currentRtpTimestamp(),
                client.sampleRateHz(),
                client::sendProgress);
    }

    private static byte[] encodeArtwork(Bitmap artwork) {
        if (artwork == null) {
            return null;
        }
        Bitmap source = artwork;
        int maxSide = Math.max(artwork.getWidth(), artwork.getHeight());
        if (maxSide > ARTWORK_MAX_DIMENSION) {
            float scale = (float) ARTWORK_MAX_DIMENSION / maxSide;
            source = Bitmap.createScaledBitmap(
                    artwork,
                    Math.max(1, Math.round(artwork.getWidth() * scale)),
                    Math.max(1, Math.round(artwork.getHeight() * scale)),
                    true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out);
        byte[] jpeg = out.toByteArray();
        return jpeg.length == 0 ? null : jpeg;
    }

    private static final class PendingMetadata {
        final TrackMetadata track;
        final byte[] artworkJpeg;
        final PlaybackFlowGate.Transition transition;
        final long revision;

        private PendingMetadata(
                TrackMetadata track,
                byte[] artworkJpeg,
                PlaybackFlowGate.Transition transition,
                long revision) {
            this.track = track;
            this.artworkJpeg = artworkJpeg;
            this.transition = transition;
            this.revision = revision;
        }
    }

    private void stopMetadata() {
        metadataPolicy = null;
        artworkPolicy = null;
        progressPolicy = null;
        playbackFlowGate.reset();
        trackProgressSender.reset();
        MediaSessionMonitor monitor = metadataMonitor;
        metadataMonitor = null;
        if (monitor != null) {
            monitor.stop();
        }
        HandlerThread thread = metadataThread;
        metadataThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    /**
     * Optional "mute this device while casting": sets the music stream to 0 after the
     * session is up and restores the previous volume on stop, unless the user picked a
     * new volume in the meantime. Capture duplicates the mix, so the receiver is unaffected.
     */
    private void applyDeviceMute(boolean enabled) {
        if (!enabled) {
            return;
        }
        AudioManager audioManager = getSystemService(AudioManager.class);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int target = mutePolicy.onStreamingStarted(true, current);
        if (target != DeviceMutePolicy.NO_CHANGE) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        }
    }

    private void restoreDeviceVolume() {
        AudioManager audioManager = getSystemService(AudioManager.class);
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int restored = mutePolicy.onStreamingStopped(current);
        if (restored != DeviceMutePolicy.NO_CHANGE) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restored, 0);
        }
    }

    private void stopSession(boolean stopService) {
        stopSession(stopService, true);
    }

    private void stopSession(boolean stopService, boolean announceStopped) {
        if (!stopping.compareAndSet(false, true) && !stopService) {
            return;
        }

        awaitingCapture = false;
        activeFormat = null;
        activeReceiverName = "";
        activeMuteDevice = false;

        DacpSession remoteControl = dacpSession;
        dacpSession = null;
        if (remoteControl != null) {
            remoteControl.close();
        }

        stopMetadata();
        restoreDeviceVolume();

        PlaybackCapture capture = playbackCapture;
        playbackCapture = null;
        if (capture != null) {
            capture.stop();
        }

        MediaProjection projection = mediaProjection;
        mediaProjection = null;
        if (projection != null) {
            projection.stop();
        }

        RaopStreamingClient client = raopClient;
        raopClient = null;
        if (client != null) {
            try {
                client.close();
            } catch (IOException ignored) {
                // The receiver may already have closed the session.
            }
        }
        DlnaStreamingClient dlna = dlnaClient;
        dlnaClient = null;
        if (dlna != null) {
            dlna.close();
        }
        releaseNetworkLocks();

        if (stopService) {
            if (announceStopped) {
                broadcastStatus(STATUS_STOPPED, "投送已停止");
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void startInForeground(String deviceName) {
        getSystemService(NotificationManager.class).cancel(ERROR_NOTIFICATION_ID);
        String message = getString(R.string.status_connecting_receiver, deviceName);
        // Publish the state before the session worker runs. If the user switches back
        // immediately, MainActivity must not mistake this session for an idle one and
        // restart discovery while the TV connection is being negotiated.
        lastStatus = new StatusSnapshot(STATUS_CONNECTING, message, activeReceiverKey);
        Notification notification = buildNotification(message);
        startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(
                        new Notification.Action.Builder(
                                null,
                                getString(R.string.notification_stop),
                                stopPendingIntent)
                                .build())
                .build();
    }

    private Notification buildErrorNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示正在进行的系统音频投送会话");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @SuppressLint("WakelockTimeout")
    private void acquireNetworkLocks() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Shengdu:stream");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();

        multicastLock = wifiManager.createMulticastLock("Shengdu:dacp");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shengdu:stream");
        wakeLock.setReferenceCounted(false);
        // The user controls an unbounded foreground streaming session. A fixed timeout
        // would silently break long playback; stopSession/onDestroy always release it.
        wakeLock.acquire();
    }

    private void releaseNetworkLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        multicastLock = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }

    @SuppressLint("MissingPermission")
    private void broadcastStatus(String status, String message) {
        if (STATUS_STOPPED.equals(status) || STATUS_ERROR.equals(status)) {
            activeReceiverKey = "";
        }
        lastStatus = new StatusSnapshot(status, message, activeReceiverKey);
        if (STATUS_CONNECTING.equals(status)
                || STATUS_STOPPING.equals(status)
                || STATUS_AWAITING_CAPTURE.equals(status)
                || STATUS_STREAMING.equals(status)) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, buildNotification(message));
            }
        } else if (STATUS_ERROR.equals(status)) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.notify(ERROR_NOTIFICATION_ID, buildErrorNotification(message));
            }
        }
        Intent update = new Intent(ACTION_STATUS)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, status)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_RECEIVER_KEY, activeReceiverKey);
        sendBroadcast(update);
    }

    private static String requiredStringExtra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少连接信息: " + key);
        }
        return value;
    }

    @SuppressWarnings("deprecation")
    private static Intent getProjectionData(Intent intent) {
        Intent data;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
        }
        if (data == null) {
            throw new IllegalArgumentException("缺少系统音频捕获授权");
        }
        return data;
    }

    private static Map<String, byte[]> readTxtAttributes(Intent intent) {
        String[] keys = intent.getStringArrayExtra(EXTRA_TXT_KEYS);
        if (keys == null || keys.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, byte[]> attributes = new HashMap<>();
        for (String key : keys) {
            byte[] value = intent.getByteArrayExtra(EXTRA_TXT_PREFIX + key);
            if (value != null) {
                attributes.put(key, value.clone());
            }
        }
        return attributes;
    }

    private static String readableError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? "投送失败：" + current.getClass().getSimpleName()
                : "投送失败：" + message;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        sessionGeneration.incrementAndGet();
        stopSession(false);
        worker.shutdownNow();
        StatusSnapshot status = lastStatus;
        if (STATUS_CONNECTING.equals(status.status)
                || STATUS_STOPPING.equals(status.status)
                || STATUS_AWAITING_CAPTURE.equals(status.status)
                || STATUS_STREAMING.equals(status.status)) {
            activeReceiverKey = "";
            lastStatus = new StatusSnapshot(STATUS_STOPPED, "投送服务已结束", "");
        }
        super.onDestroy();
    }

    private final class CaptureListener implements PlaybackCapture.Listener {
        private final int generation;

        private CaptureListener(int generation) {
            this.generation = generation;
        }

        @Override
        public void onStateChanged(PlaybackCapture.State state) {
            if (state == PlaybackCapture.State.STOPPED
                    && sessionGeneration.compareAndSet(generation, generation + 1)
                    && stopping.compareAndSet(false, true)) {
                worker.execute(() -> stopSession(true));
            }
        }

        @Override
        public void onError(Throwable error) {
            if (sessionGeneration.compareAndSet(generation, generation + 1)
                    && stopping.compareAndSet(false, true)) {
                broadcastStatus(STATUS_ERROR, readableError(error));
                worker.execute(() -> stopSession(true, false));
            }
        }
    }

    public static final class StatusSnapshot {
        private final String status;
        private final String message;
        private final String receiverKey;

        private StatusSnapshot(String status, String message, String receiverKey) {
            this.status = status;
            this.message = message;
            this.receiverKey = receiverKey;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public String getReceiverKey() {
            return receiverKey;
        }
    }
}
