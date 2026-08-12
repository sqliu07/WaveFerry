package app.shengdu.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.shengdu.audio.discovery.AirPlayDiscovery;
import app.shengdu.audio.discovery.DiscoveryError;
import app.shengdu.audio.discovery.DiscoveryScanDeadline;
import app.shengdu.audio.discovery.DiscoverySnapshot;
import app.shengdu.audio.discovery.ReceiverDevice;
import app.shengdu.audio.dlna.DlnaDiscovery;
import app.shengdu.audio.dlna.DlnaRendererDescription;
import app.shengdu.audio.metadata.MediaSessionMonitor;
import app.shengdu.audio.metadata.TrackMetadata;
import app.shengdu.audio.raop.RaopCompatibility;
import app.shengdu.audio.raop.RaopStreamingClient;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main surface for receiver discovery and session launch. */
public final class MainActivity extends Activity implements AirPlayDiscovery.Listener {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PROJECTION = 101;
    private static final int ARTWORK_THUMBNAIL_MAX_PX = 320;
    private static final long DISCOVERY_TIMEOUT_MS = 30_000L;

    private final List<ReceiverDevice> devices = new ArrayList<>();
    private final List<ReceiverDevice> airPlayDevices = new ArrayList<>();
    private final List<ReceiverDevice> dlnaDevices = new ArrayList<>();
    private final Handler discoveryTimeoutHandler = new Handler(Looper.getMainLooper());
    private final DiscoveryScanDeadline discoveryScanDeadline = new DiscoveryScanDeadline();
    private final Runnable discoveryTimeout = this::onDiscoveryTimeout;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyStreamingStatus(
                    intent.getStringExtra(StreamingService.EXTRA_STATUS),
                    intent.getStringExtra(StreamingService.EXTRA_MESSAGE),
                    intent.getStringExtra(StreamingService.EXTRA_RECEIVER_KEY));
        }
    };

    private AirPlayDiscovery discovery;
    private DlnaDiscovery dlnaDiscovery;
    private AppPreferences preferences;
    private ReceiverDevice selectedReceiver;
    private MediaSessionMonitor nowPlayingMonitor;
    private HandlerThread nowPlayingThread;
    private int nowPlayingGeneration;
    private boolean hasDisplayedTrack;
    private boolean receiverRegistered;
    private boolean activityStarted;
    private boolean projectionRequestInFlight;
    private boolean receiverListExpanded;
    private String activeReceiverKey = "";
    private String currentStreamingStatus = StreamingService.STATUS_STOPPED;

    private View receiverHeader;
    private LinearLayout receiverList;
    private ImageView receiverExpandIcon;
    private View receiverActions;
    private Button scanButton;
    private TextView sessionStatusView;
    private TextView discoveryStatusView;
    private ImageView artworkView;
    private ImageView artworkPlaceholderView;
    private TextView trackTitleView;
    private TextView trackSubtitleView;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBarInsets.enableEdgeToEdge(this);
        setContentView(R.layout.activity_main);
        SystemBarInsets.applyPadding(findViewById(R.id.main_root));

        preferences = new AppPreferences(this);
        discovery = new AirPlayDiscovery(this, this);
        dlnaDiscovery = new DlnaDiscovery(this, new DlnaDiscovery.Listener() {
            @Override
            public void onScanFinished(List<DlnaRendererDescription> renderers) {
                dlnaDevices.clear();
                for (DlnaRendererDescription renderer : renderers) {
                    dlnaDevices.add(toDlnaReceiver(renderer));
                }
                refreshReceiverList(discovery.isScanning() || dlnaDiscovery.isScanning());
            }

            @Override
            public void onError(String message) {
                if (devices.isEmpty() && !discoveryScanDeadline.isArmed()) {
                    setDiscoveryStatus(message, true);
                }
            }
        });

        bindViews();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        registerStatusReceiver();
        restoreStreamingStatus();
        if (isStreamingActive()) {
            discovery.start();
            dlnaDiscovery.restartScan();
        } else {
            startDiscoveryScan(false);
        }
        startNowPlaying();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        cancelDiscoveryTimeout();
        if (!isStreamingActive()) {
            discovery.stop();
            dlnaDiscovery.cancelScan();
        }
        stopNowPlaying();
        if (!isStreamingActive()) {
            unregisterStatusReceiver();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        discovery.stop();
        dlnaDiscovery.close();
        unregisterStatusReceiver();
        artworkView.setImageDrawable(null);
        super.onDestroy();
    }

    private void bindViews() {
        ImageButton settingsButton = findViewById(R.id.button_open_settings);
        receiverHeader = findViewById(R.id.receiver_header);
        receiverList = findViewById(R.id.receiver_list);
        receiverExpandIcon = findViewById(R.id.image_receiver_expand);
        receiverActions = findViewById(R.id.receiver_actions);
        sessionStatusView = findViewById(R.id.text_session_status);
        discoveryStatusView = findViewById(R.id.text_discovery_status);
        artworkView = findViewById(R.id.image_artwork);
        artworkPlaceholderView = findViewById(R.id.image_artwork_placeholder);
        trackTitleView = findViewById(R.id.text_track_title);
        trackSubtitleView = findViewById(R.id.text_track_subtitle);
        scanButton = findViewById(R.id.button_scan);
        startButton = findViewById(R.id.button_start);
        stopButton = findViewById(R.id.button_stop);

        sessionStatusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        discoveryStatusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        trackTitleView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        settingsButton.setOnClickListener(view -> {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            if (selectedReceiver != null) {
                String rates = txtValue(selectedReceiver.getTxtAttributes(), "sr");
                settingsIntent.putExtra(SettingsActivity.EXTRA_RECEIVER_SAMPLE_RATES, rates);
            }
            startActivity(settingsIntent);
        });
        scanButton.setOnClickListener(view -> {
            startDiscoveryScan(true);
        });
        receiverHeader.setOnClickListener(view -> {
            if (!StreamingService.STATUS_STREAMING.equals(currentStreamingStatus)) {
                return;
            }
            receiverListExpanded = !receiverListExpanded;
            updateReceiverSection(true);
        });
        startButton.setOnClickListener(view -> {
            if (StreamingService.STATUS_AWAITING_CAPTURE.equals(currentStreamingStatus)) {
                requestProjection();
            } else {
                beginStreaming();
            }
        });
        stopButton.setOnClickListener(view -> {
            projectionRequestInFlight = false;
            startService(StreamingService.createStopIntent(this));
            applyStreamingStatus(
                    StreamingService.STATUS_STOPPING,
                    getString(R.string.status_stopping));
        });
        updateActionButtons();
    }

    private static String txtValue(Map<String, String> txt, String key) {
        for (Map.Entry<String, String> entry : txt.entrySet()) {
            if (key.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                return entry.getValue().trim();
            }
        }
        return "";
    }

    private void startNowPlaying() {
        if (nowPlayingMonitor != null) {
            return;
        }
        int generation = ++nowPlayingGeneration;
        HandlerThread thread = new HandlerThread("shengdu-now-playing");
        thread.start();
        MediaSessionMonitor monitor = new MediaSessionMonitor(
                this,
                new Handler(thread.getLooper()),
                (track, artwork) -> {
                    Bitmap thumbnail = createArtworkThumbnail(artwork);
                    runOnUiThread(() -> {
                        if (generation != nowPlayingGeneration) {
                            if (thumbnail != null) {
                                thumbnail.recycle();
                            }
                            return;
                        }
                        showNowPlaying(track, thumbnail);
                    });
                });
        nowPlayingThread = thread;
        nowPlayingMonitor = monitor;
        if (!monitor.start()) {
            ++nowPlayingGeneration;
            nowPlayingMonitor = null;
            nowPlayingThread = null;
            thread.quitSafely();
            showNowPlaying(null, null);
        }
    }

    private void stopNowPlaying() {
        ++nowPlayingGeneration;
        MediaSessionMonitor monitor = nowPlayingMonitor;
        nowPlayingMonitor = null;
        if (monitor != null) {
            monitor.stop();
        }
        HandlerThread thread = nowPlayingThread;
        nowPlayingThread = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void showNowPlaying(TrackMetadata track, Bitmap artwork) {
        boolean hasTrack = track != null && !track.isEmpty();
        if (!hasTrack) {
            if (!hasDisplayedTrack) {
                trackTitleView.setText(R.string.now_playing_empty);
                trackSubtitleView.setText("");
                trackSubtitleView.setVisibility(View.GONE);
                artworkView.setImageDrawable(null);
                artworkView.setVisibility(View.GONE);
                artworkPlaceholderView.setVisibility(View.VISIBLE);
            }
            return;
        }

        hasDisplayedTrack = true;
        trackTitleView.setText(track.getTitle().isEmpty()
                ? getString(R.string.now_playing_unknown)
                : track.getTitle());
        String subtitle = trackSubtitle(track);
        trackSubtitleView.setText(subtitle);
        trackSubtitleView.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);

        if (artwork == null) {
            artworkView.setImageDrawable(null);
            artworkView.setVisibility(View.GONE);
            artworkPlaceholderView.setVisibility(View.VISIBLE);
        } else {
            artworkView.setImageBitmap(artwork);
            artworkView.setVisibility(View.VISIBLE);
            artworkPlaceholderView.setVisibility(View.GONE);
        }
    }

    private String trackSubtitle(TrackMetadata track) {
        String artist = track.getArtist();
        String album = track.getAlbum();
        if (!artist.isEmpty() && !album.isEmpty()) {
            return getString(R.string.now_playing_artist_album, artist, album);
        }
        return artist.isEmpty() ? album : artist;
    }

    private static Bitmap createArtworkThumbnail(Bitmap artwork) {
        if (artwork == null) {
            return null;
        }
        int width = artwork.getWidth();
        int height = artwork.getHeight();
        float scale = Math.min(
                1f,
                (float) ARTWORK_THUMBNAIL_MAX_PX / Math.max(width, height));
        Bitmap thumbnail = Bitmap.createScaledBitmap(
                artwork,
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)),
                true);
        if (thumbnail != artwork) {
            return thumbnail;
        }
        return artwork.copy(Bitmap.Config.ARGB_8888, false);
    }

    private void beginStreaming() {
        if (selectedReceiver == null) {
            applyStreamingStatus(StreamingService.STATUS_ERROR, getString(R.string.receiver_empty));
            return;
        }
        ReceiverDevice receiver = selectedReceiver;
        if (!receiver.supportsRaop() && !receiver.supportsDlna()) {
            applyStreamingStatus(
                    StreamingService.STATUS_ERROR,
                    getString(R.string.receiver_incompatible_generic));
            return;
        }
        String compatibilityError = receiverCompatibilityError(receiver);
        if (compatibilityError != null) {
            applyStreamingStatus(StreamingService.STATUS_ERROR, compatibilityError);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
            return;
        }
        connectReceiver(receiver);
    }

    private void connectReceiver(ReceiverDevice receiver) {
        try {
            InetAddress address = InetAddress.getByName(receiver.getAddress());
            Intent serviceIntent = StreamingService.createPrepareIntent(
                    this,
                    address,
                    receiver.getPort(),
                    receiver.getDisplayName(),
                    receiver.getStableKey(),
                    encodeTxtAttributes(receiver.getTxtAttributes()),
                    preferences.isMuteDeviceEnabled(),
                    preferences.getAudioQuality());
            startForegroundService(serviceIntent);
            activeReceiverKey = receiver.getStableKey();
            applyStreamingStatus(
                    StreamingService.STATUS_CONNECTING,
                    getString(R.string.status_connecting_receiver, receiver.getDisplayName()));
        } catch (Exception error) {
            applyStreamingStatus(
                    StreamingService.STATUS_ERROR,
                    getString(R.string.receiver_address_error, error.getMessage()));
        }
    }

    private void requestProjection() {
        if (projectionRequestInFlight) {
            return;
        }
        projectionRequestInFlight = true;
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        applyStreamingStatus(
                StreamingService.STATUS_AWAITING_CAPTURE,
                getString(R.string.projection_waiting));
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            applyStreamingStatus(
                    StreamingService.STATUS_ERROR,
                    getString(R.string.permission_required_audio));
            return;
        }
        ReceiverDevice receiver = selectedReceiver;
        if (receiver == null) {
            applyStreamingStatus(StreamingService.STATUS_ERROR, getString(R.string.receiver_empty));
            return;
        }
        connectReceiver(receiver);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            return;
        }
        projectionRequestInFlight = false;
        if (resultCode != RESULT_OK || data == null) {
            restoreStreamingStatus();
            return;
        }
        try {
            Intent serviceIntent = StreamingService.createAttachCaptureIntent(this, resultCode, data);
            startForegroundService(serviceIntent);
            Toast.makeText(
                    getApplicationContext(),
                    R.string.projection_ready_hint,
                    Toast.LENGTH_LONG).show();
            applyStreamingStatus(
                    StreamingService.STATUS_CONNECTING,
                    getString(R.string.status_starting_capture));
        } catch (Exception error) {
            applyStreamingStatus(
                    StreamingService.STATUS_ERROR,
                    getString(R.string.receiver_address_error, error.getMessage()));
        }
    }

    @Override
    public void onSnapshot(DiscoverySnapshot snapshot) {
        if (!activityStarted
                && !snapshot.isScanning()
                && snapshot.getDevices().isEmpty()
                && isStreamingActive()) {
            return;
        }
        airPlayDevices.clear();
        for (ReceiverDevice device : snapshot.getDevices()) {
            if (device.supportsRaop()) {
                airPlayDevices.add(device);
            }
        }
        refreshReceiverList(snapshot.isScanning() || dlnaDiscovery.isScanning());
    }

    private void refreshReceiverList(boolean scanning) {
        String selectedKey = selectedDeviceKey();
        if (selectedKey.isEmpty() && isStreamingActive()) {
            selectedKey = activeReceiverKey;
        }
        devices.clear();
        devices.addAll(airPlayDevices);
        devices.addAll(dlnaDevices);
        devices.sort((left, right) -> left.getDisplayName().compareToIgnoreCase(right.getDisplayName()));
        renderReceiverList(selectedKey);
        if (!devices.isEmpty() && discoveryScanDeadline.isArmed()) {
            discoveryScanDeadline.onDevicesFound();
            discoveryTimeoutHandler.removeCallbacks(discoveryTimeout);
        }
        boolean scanInProgress = devices.isEmpty()
                && !discoveryScanDeadline.hasTimedOut()
                && (discoveryScanDeadline.isArmed() || dlnaDiscovery.isScanning() || scanning);
        scanButton.setEnabled(!scanInProgress);
        scanButton.setText(getString(scanInProgress ? R.string.scanning : R.string.scan));
        if (devices.isEmpty()) {
            setDiscoveryStatus(
                    getString(discoveryScanDeadline.hasTimedOut()
                            ? R.string.discovery_timeout
                            : scanInProgress
                            ? R.string.discovery_scanning
                            : R.string.receiver_empty),
                    discoveryScanDeadline.hasTimedOut() || !scanInProgress);
        } else {
            setDiscoveryStatus(
                    getResources().getQuantityString(
                            R.plurals.discovery_found,
                            devices.size(),
                            devices.size()),
                    false);
        }
        updateActionButtons();
    }

    private void startDiscoveryScan(boolean restartAirPlay) {
        cancelDiscoveryTimeout();
        // A new scan must not let stale results suppress the empty-scan deadline.
        // Both discovery backends will repopulate the list as fresh responses arrive.
        airPlayDevices.clear();
        dlnaDevices.clear();
        devices.clear();
        discoveryScanDeadline.begin(false);
        if (restartAirPlay) {
            setDiscoveryStatus(getString(R.string.discovery_rescanning), false);
            discovery.stop();
        }
        discovery.start();
        dlnaDiscovery.restartScan();
        if (discoveryScanDeadline.isArmed()) {
            discoveryTimeoutHandler.postDelayed(discoveryTimeout, DISCOVERY_TIMEOUT_MS);
        }
        refreshReceiverList(discovery.isScanning() || dlnaDiscovery.isScanning());
    }

    private void cancelDiscoveryTimeout() {
        discoveryTimeoutHandler.removeCallbacks(discoveryTimeout);
        discoveryScanDeadline.cancel();
    }

    private void onDiscoveryTimeout() {
        if (!activityStarted || !devices.isEmpty() || !discoveryScanDeadline.expire()) {
            return;
        }
        discovery.stop();
        dlnaDiscovery.cancelScan();
        refreshReceiverList(false);
    }

    @Override
    public void onScanningChanged(boolean scanning) {
        refreshReceiverList(scanning);
    }

    private static ReceiverDevice toDlnaReceiver(DlnaRendererDescription renderer) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("fn", renderer.friendlyName());
        attributes.put("transport", "dlna");
        attributes.put("dlna_av_transport_url", renderer.avTransportControlUri().toString());
        attributes.put("dlna_av_transport_type", renderer.avTransportServiceType());
        attributes.put("dlna_sink_wav_advisory", switch (renderer.wavSupport()) {
            case ADVERTISED -> "advertised";
            case NOT_ADVERTISED -> "not_advertised";
            case UNKNOWN -> "unknown";
        });
        if (renderer.connectionManagerControlUri() != null) {
            attributes.put("dlna_connection_manager_url", renderer.connectionManagerControlUri().toString());
        }
        String address = renderer.descriptionUri().getHost();
        int port = renderer.descriptionUri().getPort();
        if (port <= 0) {
            port = "https".equalsIgnoreCase(renderer.descriptionUri().getScheme()) ? 443 : 80;
        }
        String type = "urn:schemas-upnp-org:device:MediaRenderer:1";
        return new ReceiverDevice(
                "dlna:" + renderer.udn(),
                renderer.friendlyName(),
                type,
                renderer.udn(),
                address,
                port,
                attributes,
                Set.of(type));
    }

    @Override
    public void onError(DiscoveryError error) {
        if (devices.isEmpty() && !discoveryScanDeadline.isArmed()) {
            setDiscoveryStatus(getString(R.string.discovery_error, error.getMessage()), true);
        }
    }

    private void updateActionButtons() {
        boolean active = isStreamingActive();
        boolean connecting = StreamingService.STATUS_CONNECTING.equals(currentStreamingStatus);
        boolean stopping = StreamingService.STATUS_STOPPING.equals(currentStreamingStatus);
        boolean streaming = StreamingService.STATUS_STREAMING.equals(currentStreamingStatus);
        boolean awaitingCapture = StreamingService.STATUS_AWAITING_CAPTURE.equals(
                currentStreamingStatus);
        boolean switchingReceiver = isReceiverSwitchPending(
                currentStreamingStatus, activeReceiverKey, selectedDeviceKey());
        startButton.setText(connecting
                ? R.string.connecting
                : stopping
                ? R.string.status_stopping
                : awaitingCapture
                ? R.string.continue_to_source
                : streaming
                ? switchingReceiver ? R.string.switch_receiver : R.string.current_receiver
                : R.string.start_streaming);
        startButton.setEnabled(streaming
                ? switchingReceiver
                : awaitingCapture
                ? !projectionRequestInFlight
                : selectedReceiver != null && !active);
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
        stopButton.setEnabled(!stopping);
        updateReceiverSection(false);
    }

    static boolean isReceiverSwitchPending(
            String status, String activeKey, String selectedKey) {
        return StreamingService.STATUS_STREAMING.equals(status)
                && activeKey != null
                && !activeKey.isEmpty()
                && selectedKey != null
                && !selectedKey.isEmpty()
                && !activeKey.equals(selectedKey);
    }

    private void updateReceiverSection(boolean animate) {
        boolean collapsible = StreamingService.STATUS_STREAMING.equals(currentStreamingStatus);
        boolean expanded = !collapsible || receiverListExpanded;
        receiverHeader.setClickable(collapsible);
        receiverHeader.setContentDescription(collapsible
                ? getString(expanded ? R.string.collapse_receivers : R.string.expand_receivers)
                : null);
        receiverExpandIcon.setVisibility(collapsible ? View.VISIBLE : View.GONE);
        receiverExpandIcon.animate().cancel();
        receiverExpandIcon.animate()
                .rotation(expanded ? 180f : 0f)
                .setDuration(animate ? 180L : 0L)
                .start();
        if (expanded) {
            receiverList.setVisibility(View.VISIBLE);
            receiverActions.setVisibility(View.VISIBLE);
            if (animate) {
                receiverList.setAlpha(0f);
                receiverList.setTranslationY(-8f);
                receiverList.animate().alpha(1f).translationY(0f).setDuration(180L).start();
                receiverActions.setAlpha(0f);
                receiverActions.animate().alpha(1f).setDuration(180L).start();
            }
        } else {
            receiverList.setVisibility(View.GONE);
            receiverActions.setVisibility(View.GONE);
            receiverList.setAlpha(1f);
            receiverList.setTranslationY(0f);
            receiverActions.setAlpha(1f);
        }
    }

    private boolean isStreamingActive() {
        return StreamingService.STATUS_CONNECTING.equals(currentStreamingStatus)
                || StreamingService.STATUS_STOPPING.equals(currentStreamingStatus)
                || StreamingService.STATUS_AWAITING_CAPTURE.equals(currentStreamingStatus)
                || StreamingService.STATUS_STREAMING.equals(currentStreamingStatus);
    }

    private void restoreStreamingStatus() {
        StreamingService.StatusSnapshot snapshot = StreamingService.getLastStatus();
        applyStreamingStatus(
                snapshot.getStatus(), snapshot.getMessage(), snapshot.getReceiverKey());
    }

    private void applyStreamingStatus(String status, String message) {
        applyStreamingStatus(status, message, activeReceiverKey);
    }

    private void applyStreamingStatus(String status, String message, String receiverKey) {
        boolean wasActive = isStreamingActive();
        boolean wasStreaming = StreamingService.STATUS_STREAMING.equals(currentStreamingStatus);
        String resolvedStatus = status == null || status.isBlank()
                ? StreamingService.STATUS_STOPPED
                : status;
        String resolvedMessage = message == null || message.isBlank()
                ? getString(R.string.status_ready)
                : message;
        currentStreamingStatus = resolvedStatus;
        if (StreamingService.STATUS_ERROR.equals(resolvedStatus)
                || StreamingService.STATUS_STOPPED.equals(resolvedStatus)) {
            activeReceiverKey = "";
            receiverListExpanded = false;
        } else if (receiverKey != null && !receiverKey.isEmpty()) {
            activeReceiverKey = receiverKey;
        }
        if (!wasStreaming && StreamingService.STATUS_STREAMING.equals(resolvedStatus)) {
            receiverListExpanded = false;
        }
        sessionStatusView.setText(resolvedMessage);
        if (StreamingService.STATUS_ERROR.equals(resolvedStatus)) {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_status_error));
        } else if (StreamingService.STATUS_STREAMING.equals(resolvedStatus)
                || StreamingService.STATUS_AWAITING_CAPTURE.equals(resolvedStatus)) {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_status_success));
        } else if (StreamingService.STATUS_CONNECTING.equals(resolvedStatus)
                || StreamingService.STATUS_STOPPING.equals(resolvedStatus)) {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_status_warning));
        } else {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_text_primary));
        }
        if (activityStarted && wasActive && !isStreamingActive()) {
            startDiscoveryScan(false);
        } else if (!activityStarted && wasActive && !isStreamingActive()) {
            discovery.stop();
            dlnaDiscovery.cancelScan();
            unregisterStatusReceiver();
        }
        updateActionButtons();
    }

    private void setDiscoveryStatus(String message, boolean error) {
        discoveryStatusView.setText(message);
        if (error) {
            discoveryStatusView.setTextColor(getColor(R.color.shengdu_status_error));
        } else {
            discoveryStatusView.setTextColor(getColor(R.color.shengdu_text_secondary));
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStatusReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(StreamingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterStatusReceiver() {
        if (!receiverRegistered) {
            return;
        }
        unregisterReceiver(statusReceiver);
        receiverRegistered = false;
    }

    private String selectedDeviceKey() {
        return selectedReceiver == null ? "" : selectedReceiver.getStableKey();
    }

    private void renderReceiverList(String selectedKey) {
        ReceiverDevice restoredSelection = null;
        for (ReceiverDevice device : devices) {
            if (device.getStableKey().equals(selectedKey)) {
                restoredSelection = device;
                break;
            }
        }
        selectedReceiver = restoredSelection;
        receiverList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ReceiverDevice device : devices) {
            View row = inflater.inflate(R.layout.item_receiver, receiverList, false);
            boolean selected = device == selectedReceiver;
            row.setActivated(selected);
            ((TextView) row.findViewById(R.id.text_receiver_name)).setText(device.getDisplayName());
            ((TextView) row.findViewById(R.id.text_receiver_type)).setText(
                    device.supportsDlna() ? R.string.receiver_type_dlna : R.string.receiver_type_airplay);
            ((ImageView) row.findViewById(R.id.image_receiver_type)).setImageResource(
                    device.supportsDlna() ? R.drawable.ic_dlna_24 : R.drawable.ic_airplay_24);
            row.findViewById(R.id.image_receiver_selected).setVisibility(
                    selected ? View.VISIBLE : View.GONE);
            row.setOnClickListener(view -> {
                selectedReceiver = device;
                renderReceiverList(selectedDeviceKey());
                updateActionButtons();
            });
            receiverList.addView(row);
        }
    }

    private String receiverCompatibilityError(ReceiverDevice receiver) {
        if (receiver.supportsDlna()) {
            return txtValue(receiver.getTxtAttributes(), "dlna_av_transport_url").isEmpty()
                    ? getString(R.string.receiver_incompatible_generic)
                    : null;
        }
        RaopStreamingClient client = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                receiver.getPort(),
                encodeTxtAttributes(receiver.getTxtAttributes()));
        RaopCompatibility compatibility = client.compatibility();
        return switch (compatibility) {
            case LEGACY_CLEAR, LEGACY_RSA -> null;
            default -> getString(R.string.receiver_incompatible_generic);
        };
    }

    private static Map<String, byte[]> encodeTxtAttributes(Map<String, String> source) {
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            encoded.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return encoded;
    }

}
