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
import app.shengdu.audio.metadata.MediaSessionMonitor;
import app.shengdu.audio.metadata.MetadataSendPolicy;
import app.shengdu.audio.metadata.PlaybackFlowGate;
import app.shengdu.audio.metadata.TrackMetadata;
import app.shengdu.audio.metadata.TrackProgressSender;
import app.shengdu.audio.raop.MetadataSupport;
import app.shengdu.audio.raop.RaopStreamingClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
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

    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_STREAMING = "streaming";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_ERROR = "error";

    private static final String ACTION_START = "app.shengdu.audio.action.START";
    private static final String ACTION_STOP = "app.shengdu.audio.action.STOP";
    private static final String EXTRA_PROJECTION_RESULT = "projection_result";
    private static final String EXTRA_PROJECTION_DATA = "projection_data";
    private static final String EXTRA_HOST = "host";
    private static final String EXTRA_PORT = "port";
    private static final String EXTRA_RECEIVER_NAME = "receiver_name";
    private static final String EXTRA_MUTE_DEVICE = "mute_device";
    private static final String EXTRA_TXT_KEYS = "txt_keys";
    private static final String EXTRA_TXT_PREFIX = "txt_";

    private static final String NOTIFICATION_CHANNEL_ID = "streaming";
    private static final int NOTIFICATION_ID = 1001;
    private static final int ERROR_NOTIFICATION_ID = 1002;
    private static volatile StatusSnapshot lastStatus = new StatusSnapshot("", "");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "shengdu-session");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger sessionGeneration = new AtomicInteger();

    private RaopStreamingClient raopClient;
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
    private final DeviceMutePolicy mutePolicy = new DeviceMutePolicy();
    private final PlaybackFlowGate playbackFlowGate = new PlaybackFlowGate();
    private final TrackProgressSender trackProgressSender = new TrackProgressSender();

    private static final int ARTWORK_MAX_DIMENSION = 512;
    private static final int ARTWORK_JPEG_QUALITY = 85;

    public static Intent createStartIntent(
            Context context,
            int projectionResult,
            Intent projectionData,
            InetAddress receiverAddress,
            int receiverPort,
            String receiverName,
            Map<String, byte[]> txtAttributes,
            boolean muteDevice) {
        Intent intent = new Intent(context, StreamingService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_PROJECTION_RESULT, projectionResult);
        intent.putExtra(EXTRA_PROJECTION_DATA, projectionData);
        intent.putExtra(EXTRA_HOST, receiverAddress.getHostAddress());
        intent.putExtra(EXTRA_PORT, receiverPort);
        intent.putExtra(EXTRA_RECEIVER_NAME, receiverName);
        intent.putExtra(EXTRA_MUTE_DEVICE, muteDevice);

        String[] keys = txtAttributes.keySet().toArray(new String[0]);
        intent.putExtra(EXTRA_TXT_KEYS, keys);
        for (String key : keys) {
            intent.putExtra(EXTRA_TXT_PREFIX + key, txtAttributes.get(key));
        }
        return intent;
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
            worker.execute(() -> stopSession(true));
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String requestedReceiverName = intent.getStringExtra(EXTRA_RECEIVER_NAME);
        if (requestedReceiverName == null || requestedReceiverName.isBlank()) {
            requestedReceiverName = "AirPlay 设备";
        }
        int generation = sessionGeneration.incrementAndGet();
        String sessionReceiverName = requestedReceiverName;
        startInForeground(sessionReceiverName);
        worker.execute(() -> startSession(intent, generation, sessionReceiverName));
        return START_NOT_STICKY;
    }

    private void startSession(Intent intent, int generation, String sessionReceiverName) {
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
            broadcastStatus(STATUS_CONNECTING, "正在连接 " + sessionReceiverName + "…");
            acquireNetworkLocks();

            InetAddress host = InetAddress.getByName(requiredStringExtra(intent, EXTRA_HOST));
            int port = intent.getIntExtra(EXTRA_PORT, -1);
            if (port <= 0 || port > 65_535) {
                throw new IllegalArgumentException("接收器端口无效");
            }

            Map<String, byte[]> txt = readTxtAttributes(intent);
            boolean mediaSessionAvailable = startMetadata(generation);
            if (stopIfStale(generation)) {
                return;
            }
            DacpSession remoteControl = mediaSessionAvailable
                    ? startDacpSession(generation, host)
                    : null;
            if (stopIfStale(generation)) {
                return;
            }
            RaopStreamingClient client = remoteControl == null
                    ? new RaopStreamingClient(host, port, txt)
                    : new RaopStreamingClient(
                            host,
                            port,
                            txt,
                            remoteControl.dacpId(),
                            remoteControl.activeRemote());
            configureMetadata(client);
            client.connect();
            raopClient = client;
            if (stopIfStale(generation)) {
                return;
            }

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
                    new CaptureListener(generation));
            playbackCapture = capture;
            if (!capture.start()) {
                throw new IllegalStateException("无法启动系统播放音频捕获");
            }
            if (stopIfStale(generation)) {
                return;
            }
            broadcastStatus(STATUS_STREAMING, "正在投送到 " + sessionReceiverName);
            applyDeviceMute(intent);
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

    private boolean stopIfStale(int generation) {
        if (isCurrentGeneration(generation)) {
            return false;
        }
        stopSession(false);
        return true;
    }

    private void writePcm(byte[] buffer, int byteCount) {
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
        // Scaling and JPEG compression run here, on the metadata thread — never
        // on the capture thread or the main thread.
        byte[] artworkJpeg = encodeArtwork(artwork);
        PendingMetadata pending = new PendingMetadata(track, artworkJpeg, transition, revision);
        worker.execute(() -> {
            RaopStreamingClient client = raopClient;
            if (client != null) {
                sendPendingMetadata(generation, client, pending);
            }
        });
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

    private static MetadataSendPolicy policyFor(MetadataSupport support) {
        if (support == MetadataSupport.NOT_ADVERTISED) {
            return null;
        }
        return new MetadataSendPolicy(support == MetadataSupport.ADVERTISED
                ? MetadataSendPolicy.Mode.ADVERTISED
                : MetadataSendPolicy.Mode.UNKNOWN);
    }

    private void sendPendingMetadata(int generation, RaopStreamingClient client, PendingMetadata pending) {
        if (pending == null || !isCurrentGeneration(generation) || client != raopClient) {
            return;
        }
        if (pending.transition == PlaybackFlowGate.Transition.PAUSED
                && pending.revision != playbackFlowGate.revision()) {
            return;
        }
        sendTextMetadata(client, pending.track);
        sendArtworkMetadata(client, pending.artworkJpeg);
        sendProgressMetadata(client, pending.track);
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
    private void applyDeviceMute(Intent intent) {
        if (!intent.getBooleanExtra(EXTRA_MUTE_DEVICE, false)) {
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
        Notification notification = buildNotification("正在连接 " + deviceName + "…");
        startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
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
        lastStatus = new StatusSnapshot(status, message);
        if (STATUS_CONNECTING.equals(status) || STATUS_STREAMING.equals(status)) {
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
                .putExtra(EXTRA_MESSAGE, message);
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
        if (STATUS_CONNECTING.equals(status.status) || STATUS_STREAMING.equals(status.status)) {
            lastStatus = new StatusSnapshot(STATUS_STOPPED, "投送服务已结束");
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

        private StatusSnapshot(String status, String message) {
            this.status = status;
            this.message = message;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }
    }
}
