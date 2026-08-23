package app.shengdu.audio.dlna;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** One bounded SSDP scan for DLNA/UPnP MediaRenderer devices. */
public final class DlnaDiscovery implements AutoCloseable {
    public interface Listener {
        void onScanFinished(List<DlnaRendererDescription> devices);

        void onError(String message);
    }

    private static final InetSocketAddress SSDP_ADDRESS =
            new InetSocketAddress("239.255.255.250", 1900);
    private static final int RECEIVE_BUFFER_BYTES = 8 * 1024;
    private static final int MAX_DESCRIPTION_BYTES = 512 * 1024;
    private static final int SCAN_TIMEOUT_MS = 4_000;

    private final Listener listener;
    private final WifiManager wifiManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dlna-discovery");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final AtomicInteger scanGeneration = new AtomicInteger();
    private volatile DatagramSocket activeSocket;
    private WifiManager.MulticastLock multicastLock;

    public DlnaDiscovery(Context context, Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        Object service = Objects.requireNonNull(context, "context")
                .getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!(service instanceof WifiManager)) {
            throw new IllegalStateException("Wi-Fi service is unavailable");
        }
        wifiManager = (WifiManager) service;
    }

    public boolean startScan() {
        if (!scanning.compareAndSet(false, true)) {
            return false;
        }
        int generation = scanGeneration.incrementAndGet();
        acquireMulticastLock();
        worker.execute(() -> scan(generation));
        return true;
    }

    /** Starts a fresh SSDP probe even when the previous bounded probe has not yet timed out. */
    public boolean restartScan() {
        if (scanning.get()) {
            cancelScan();
        }
        return startScan();
    }

    public boolean isScanning() {
        return scanning.get();
    }

    public void cancelScan() {
        scanning.set(false);
        DatagramSocket socket = activeSocket;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void close() {
        cancelScan();
        worker.shutdownNow();
        releaseMulticastLock();
    }

    private void scan(int generation) {
        LinkedHashMap<String, DlnaRendererDescription> devices = new LinkedHashMap<>();
        Set<String> seenLocations = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket();
                MulticastSocket notifications = openNotificationSocket()) {
            activeSocket = socket;
            socket.setSoTimeout(180);
            if (notifications != null) {
                notifications.setSoTimeout(180);
            }
            for (byte[] request : searchRequests()) {
                socket.send(new DatagramPacket(request, request.length, SSDP_ADDRESS));
            }
            long deadlineNanos = System.nanoTime() + SCAN_TIMEOUT_MS * 1_000_000L;
            while (isCurrentScan(generation) && System.nanoTime() < deadlineNanos) {
                receiveDiscoveryPacket(socket, seenLocations, devices);
                if (notifications != null) {
                    receiveDiscoveryPacket(notifications, seenLocations, devices);
                }
            }
            if (isCurrentScan(generation)) {
                markScanFinished(generation);
                deliverFinished(devices.values());
            }
        } catch (IOException error) {
            if (isCurrentScan(generation)) {
                deliverError("DLNA 扫描失败：" + readableMessage(error));
            }
        } finally {
            activeSocket = null;
            if (scanGeneration.get() == generation) {
                scanning.set(false);
                releaseMulticastLock();
            }
        }
    }

    private static MulticastSocket openNotificationSocket() {
        try {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(1900));
            socket.joinGroup(SSDP_ADDRESS.getAddress());
            return socket;
        } catch (IOException ignored) {
            // Active M-SEARCH remains available on devices/networks that reject port 1900 binding.
            return null;
        }
    }

    private void receiveDiscoveryPacket(
            DatagramSocket socket,
            Set<String> seenLocations,
            Map<String, DlnaRendererDescription> devices) throws IOException {
        byte[] receiveBuffer = new byte[RECEIVE_BUFFER_BYTES];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        try {
            socket.receive(packet);
        } catch (java.net.SocketTimeoutException ignored) {
            return;
        }
        DlnaSsdpResponse response = DlnaSsdpResponse.parse(packet.getData(), packet.getLength());
        if (response == null
                || !DlnaEndpointPolicy.acceptsDescription(
                response.location(), packet.getAddress())) {
            return;
        }
        if (!seenLocations.add(response.location().toString())) {
            return;
        }
        DlnaRendererDescription description = fetchDescription(
                response.location(), packet.getAddress());
        if (description != null && description.canReceiveMedia()) {
            devices.put(description.udn(), description);
        }
    }

    private boolean isCurrentScan(int generation) {
        return scanning.get() && scanGeneration.get() == generation;
    }

    private void markScanFinished(int generation) {
        if (scanGeneration.get() == generation) {
            scanning.set(false);
            releaseMulticastLock();
        }
    }

    private DlnaRendererDescription fetchDescription(URI location, InetAddress deviceAddress) {
        try {
            HttpURLConnection connection = (HttpURLConnection) location.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(3_000);
            connection.setRequestProperty("User-Agent", DlnaProtocol.USER_AGENT);
            try {
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return null;
                }
                try (InputStream input = connection.getInputStream()) {
                    DlnaRendererDescription description = DlnaDeviceDescriptionParser.parse(
                            readBounded(input), location, deviceAddress);
                    if (description.connectionManagerControlUri() != null
                            && !description.connectionManagerServiceType().isEmpty()) {
                        // Capability reports are advisory. A number of TVs omit WAV or
                        // return an incomplete Sink list despite accepting it in practice;
                        // discovery must not hide an otherwise valid MediaRenderer.
                        try {
                            DlnaSinkCapabilities capabilities = new DlnaConnectionManagerClient(
                                    description.connectionManagerControlUri(),
                                    description.connectionManagerServiceType()).getSinkCapabilities();
                            description = description.withWavSupport(capabilities.wavSupport());
                        } catch (IOException ignored) {
                            // AVTransport remains enough to expose a testable DLNA target.
                        }
                    }
                    return description.canReceiveMedia() ? description : null;
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            if (output.size() + count > MAX_DESCRIPTION_BYTES) {
                throw new IOException("DLNA description is too large");
            }
            output.write(buffer, 0, count);
        }
    }

    private static List<byte[]> searchRequests() {
        return List.of(searchRequest("ssdp:all"), searchRequest(
                "urn:schemas-upnp-org:device:MediaRenderer:1"));
    }

    private static byte[] searchRequest(String target) {
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + target + "\r\n"
                + "USER-AGENT: " + DlnaProtocol.USER_AGENT + "\r\n\r\n";
        return request.getBytes(StandardCharsets.US_ASCII);
    }

    private void deliverFinished(Iterable<DlnaRendererDescription> devices) {
        ArrayList<DlnaRendererDescription> sorted = new ArrayList<>();
        for (DlnaRendererDescription device : devices) {
            sorted.add(device);
        }
        sorted.sort(Comparator.comparing(DlnaRendererDescription::friendlyName, String.CASE_INSENSITIVE_ORDER));
        mainHandler.post(() -> listener.onScanFinished(Collections.unmodifiableList(sorted)));
    }

    private void deliverError(String message) {
        mainHandler.post(() -> listener.onError(message));
    }

    private synchronized void acquireMulticastLock() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("shengdu:dlna-discovery");
            multicastLock.setReferenceCounted(false);
        }
        if (!multicastLock.isHeld()) {
            multicastLock.acquire();
        }
    }

    private synchronized void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private static String readableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

}
