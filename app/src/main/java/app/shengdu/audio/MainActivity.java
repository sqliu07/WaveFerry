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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import app.shengdu.audio.discovery.AirPlayDiscovery;
import app.shengdu.audio.discovery.DiscoveryError;
import app.shengdu.audio.discovery.DiscoverySnapshot;
import app.shengdu.audio.discovery.ReceiverDevice;
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

/** Main surface for receiver discovery and session launch. */
public final class MainActivity extends Activity implements AirPlayDiscovery.Listener {
    private static final int REQUEST_PERMISSIONS = 100;
    private static final int REQUEST_PROJECTION = 101;
    private static final int ARTWORK_THUMBNAIL_MAX_PX = 320;

    private final List<ReceiverDevice> devices = new ArrayList<>();

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applyStreamingStatus(
                    intent.getStringExtra(StreamingService.EXTRA_STATUS),
                    intent.getStringExtra(StreamingService.EXTRA_MESSAGE));
        }
    };

    private AirPlayDiscovery discovery;
    private AppPreferences preferences;
    private ReceiverAdapter receiverAdapter;
    private ReceiverDevice pendingReceiver;
    private MediaSessionMonitor nowPlayingMonitor;
    private HandlerThread nowPlayingThread;
    private int nowPlayingGeneration;
    private boolean hasDisplayedTrack;
    private boolean receiverRegistered;
    private String currentStreamingStatus = StreamingService.STATUS_STOPPED;

    private Spinner receiverSpinner;
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

        bindViews();
        receiverAdapter = new ReceiverAdapter();
        receiverSpinner.setAdapter(receiverAdapter);
        receiverSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateActionButtons();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateActionButtons();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStatusReceiver();
        restoreStreamingStatus();
        discovery.start();
        startNowPlaying();
    }

    @Override
    protected void onStop() {
        discovery.stop();
        stopNowPlaying();
        unregisterStatusReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        discovery.stop();
        artworkView.setImageDrawable(null);
        super.onDestroy();
    }

    private void bindViews() {
        ImageButton settingsButton = findViewById(R.id.button_open_settings);
        receiverSpinner = findViewById(R.id.spinner_receivers);
        sessionStatusView = findViewById(R.id.text_session_status);
        discoveryStatusView = findViewById(R.id.text_discovery_status);
        artworkView = findViewById(R.id.image_artwork);
        artworkPlaceholderView = findViewById(R.id.image_artwork_placeholder);
        trackTitleView = findViewById(R.id.text_track_title);
        trackSubtitleView = findViewById(R.id.text_track_subtitle);
        Button scanButton = findViewById(R.id.button_scan);
        startButton = findViewById(R.id.button_start);
        stopButton = findViewById(R.id.button_stop);

        sessionStatusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        discoveryStatusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        trackTitleView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        settingsButton.setOnClickListener(
                view -> startActivity(new Intent(this, SettingsActivity.class)));
        scanButton.setOnClickListener(view -> {
            setDiscoveryStatus(getString(R.string.discovery_rescanning), false);
            discovery.stop();
            discovery.start();
        });
        startButton.setOnClickListener(view -> beginStreaming());
        stopButton.setOnClickListener(view -> {
            startService(StreamingService.createStopIntent(this));
            applyStreamingStatus(currentStreamingStatus, getString(R.string.status_stopping));
        });
        updateActionButtons();
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
        Object selected = receiverSpinner.getSelectedItem();
        if (!(selected instanceof ReceiverDevice)) {
            applyStreamingStatus(StreamingService.STATUS_ERROR, getString(R.string.receiver_empty));
            return;
        }
        ReceiverDevice receiver = (ReceiverDevice) selected;
        if (!receiver.supportsRaop()) {
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
        pendingReceiver = receiver;

        List<String> missingPermissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missingPermissions.isEmpty()) {
            requestPermissions(missingPermissions.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        applyStreamingStatus(StreamingService.STATUS_CONNECTING, getString(R.string.projection_waiting));
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
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            applyStreamingStatus(
                    StreamingService.STATUS_ERROR,
                    getString(R.string.permission_required_notifications));
            return;
        }
        requestProjection();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PROJECTION) {
            return;
        }
        ReceiverDevice receiver = pendingReceiver;
        pendingReceiver = null;
        if (resultCode != RESULT_OK || data == null || receiver == null) {
            applyStreamingStatus(StreamingService.STATUS_ERROR, getString(R.string.projection_cancelled));
            return;
        }
        try {
            InetAddress address = InetAddress.getByName(receiver.getAddress());
            Intent serviceIntent = StreamingService.createStartIntent(
                    this,
                    resultCode,
                    data,
                    address,
                    receiver.getPort(),
                    receiver.getDisplayName(),
                    encodeTxtAttributes(receiver.getTxtAttributes()),
                    preferences.isMuteDeviceEnabled());
            startForegroundService(serviceIntent);
            applyStreamingStatus(
                    StreamingService.STATUS_CONNECTING,
                    getString(R.string.status_connecting_receiver, receiver.getDisplayName()));
        } catch (Exception error) {
            applyStreamingStatus(
                    StreamingService.STATUS_ERROR,
                    getString(R.string.receiver_address_error, error.getMessage()));
        }
    }

    @Override
    public void onSnapshot(DiscoverySnapshot snapshot) {
        String selectedKey = selectedDeviceKey();
        devices.clear();
        for (ReceiverDevice device : snapshot.getDevices()) {
            if (device.supportsRaop()) {
                devices.add(device);
            }
        }
        receiverAdapter.notifyDataSetChanged();
        restoreSelection(selectedKey);
        if (devices.isEmpty()) {
            setDiscoveryStatus(
                    getString(snapshot.isScanning()
                            ? R.string.discovery_scanning
                            : R.string.receiver_empty),
                    !snapshot.isScanning());
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

    @Override
    public void onScanningChanged(boolean scanning) {
        if (devices.isEmpty()) {
            setDiscoveryStatus(
                    getString(scanning ? R.string.discovery_scanning : R.string.receiver_empty),
                    !scanning);
        }
    }

    @Override
    public void onError(DiscoveryError error) {
        if (devices.isEmpty()) {
            setDiscoveryStatus(getString(R.string.discovery_error, error.getMessage()), true);
        }
    }

    private void updateActionButtons() {
        boolean active = isStreamingActive();
        startButton.setEnabled(!devices.isEmpty() && !active);
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private boolean isStreamingActive() {
        return StreamingService.STATUS_CONNECTING.equals(currentStreamingStatus)
                || StreamingService.STATUS_STREAMING.equals(currentStreamingStatus);
    }

    private void restoreStreamingStatus() {
        StreamingService.StatusSnapshot snapshot = StreamingService.getLastStatus();
        applyStreamingStatus(snapshot.getStatus(), snapshot.getMessage());
    }

    private void applyStreamingStatus(String status, String message) {
        String resolvedStatus = status == null || status.isBlank()
                ? StreamingService.STATUS_STOPPED
                : status;
        String resolvedMessage = message == null || message.isBlank()
                ? getString(R.string.status_ready)
                : message;
        currentStreamingStatus = resolvedStatus;
        sessionStatusView.setText(resolvedMessage);
        if (StreamingService.STATUS_ERROR.equals(resolvedStatus)) {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_status_error));
        } else if (StreamingService.STATUS_STREAMING.equals(resolvedStatus)) {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_status_success));
        } else if (StreamingService.STATUS_CONNECTING.equals(resolvedStatus)) {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_status_warning));
        } else {
            sessionStatusView.setTextColor(getColor(R.color.shengdu_text_primary));
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
        Object selected = receiverSpinner.getSelectedItem();
        return selected instanceof ReceiverDevice ? ((ReceiverDevice) selected).getStableKey() : "";
    }

    private void restoreSelection(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) {
            return;
        }
        for (int index = 0; index < devices.size(); index++) {
            if (stableKey.equals(devices.get(index).getStableKey())) {
                receiverSpinner.setSelection(index);
                return;
            }
        }
    }

    private String receiverCompatibilityError(ReceiverDevice receiver) {
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

    private final class ReceiverAdapter extends ArrayAdapter<ReceiverDevice> {
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        private ReceiverAdapter() {
            super(MainActivity.this, R.layout.item_receiver_selected, R.id.text_receiver_name, devices);
            setDropDownViewResource(R.layout.item_receiver_dropdown);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return bind(convertView, parent, position, R.layout.item_receiver_selected);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return bind(convertView, parent, position, R.layout.item_receiver_dropdown);
        }

        private View bind(View convertView, ViewGroup parent, int position, int layoutId) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(layoutId, parent, false);
            }
            TextView title = view.findViewById(R.id.text_receiver_name);
            ReceiverDevice item = getItem(position);
            title.setText(item == null
                    ? getString(R.string.receiver_empty)
                    : item.getDisplayName());
            return view;
        }
    }
}
