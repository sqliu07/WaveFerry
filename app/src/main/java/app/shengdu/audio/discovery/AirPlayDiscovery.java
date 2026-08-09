package app.shengdu.audio.discovery;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AirPlayDiscovery {
    public interface Listener {
        void onSnapshot(DiscoverySnapshot snapshot);

        void onScanningChanged(boolean scanning);

        void onError(DiscoveryError error);
    }

    private static final Comparator<ReceiverDevice> DEVICE_COMPARATOR =
            Comparator.comparing(ReceiverDevice::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ReceiverDevice::getStableKey);

    private static final List<String> SERVICE_TYPES = List.of(
            ReceiverDiscoverySupport.SERVICE_TYPE_RAOP,
            ReceiverDiscoverySupport.SERVICE_TYPE_AIRPLAY);

    private final Object lock = new Object();
    private final Context appContext;
    private final NsdManager nsdManager;
    private final WifiManager wifiManager;
    private final Listener listener;
    private final Handler mainHandler;

    private WifiManager.MulticastLock multicastLock;
    private Session activeSession;
    private int nextSessionId = 1;
    private Boolean lastDeliveredScanning;

    public AirPlayDiscovery(Context context, Listener listener) {
        this.appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        this.listener = Objects.requireNonNull(listener, "listener");
        Object nsdService = appContext.getSystemService(Context.NSD_SERVICE);
        if (!(nsdService instanceof NsdManager)) {
            throw new IllegalStateException("NSD service is unavailable");
        }
        Object wifiService = appContext.getSystemService(Context.WIFI_SERVICE);
        if (!(wifiService instanceof WifiManager)) {
            throw new IllegalStateException("Wi-Fi service is unavailable");
        }
        this.nsdManager = (NsdManager) nsdService;
        this.wifiManager = (WifiManager) wifiService;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        Session sessionToStart;
        synchronized (lock) {
            if (activeSession != null) {
                dispatchSnapshotLocked();
                return;
            }
            sessionToStart = new Session(nextSessionId++);
            for (String serviceType : SERVICE_TYPES) {
                sessionToStart.discoveryStates.put(serviceType, new DiscoveryState());
            }
            activeSession = sessionToStart;
            acquireMulticastLockLocked();
            dispatchSnapshotLocked();
        }
        for (String serviceType : SERVICE_TYPES) {
            startDiscovery(sessionToStart, serviceType);
        }
    }

    public void stop() {
        Session sessionToStop;
        synchronized (lock) {
            sessionToStop = activeSession;
            activeSession = null;
            releaseMulticastLockLocked();
        }
        if (sessionToStop != null) {
            stopSession(sessionToStop);
        }
        dispatchSnapshot(new DiscoverySnapshot(Collections.emptyList(), false));
    }

    public boolean isScanning() {
        synchronized (lock) {
            return buildSnapshotLocked().isScanning();
        }
    }

    public DiscoverySnapshot getCurrentSnapshot() {
        synchronized (lock) {
            return buildSnapshotLocked();
        }
    }

    private void startDiscovery(Session session, String serviceType) {
        ServiceDiscoveryListener discoveryListener = new ServiceDiscoveryListener(session.sessionId, serviceType);
        synchronized (lock) {
            Session current = activeSession;
            if (current == null || current.sessionId != session.sessionId) {
                return;
            }
            DiscoveryState state = current.discoveryStates.get(serviceType);
            if (state == null) {
                return;
            }
            state.discoveryListener = discoveryListener;
            state.startIssued = true;
        }
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException exception) {
            synchronized (lock) {
                Session current = activeSession;
                if (current != null && current.sessionId == session.sessionId) {
                    DiscoveryState state = current.discoveryStates.get(serviceType);
                    if (state != null) {
                        state.terminalFailure = true;
                    }
                    dispatchSnapshotLocked();
                }
            }
            dispatchError(new DiscoveryError(
                    "discover",
                    serviceType,
                    "",
                    NsdManager.FAILURE_INTERNAL_ERROR,
                    exception.getMessage() == null ? "Failed to start discovery" : exception.getMessage()));
        }
    }

    private void stopSession(Session session) {
        List<NsdManager.DiscoveryListener> discoveryListeners = new ArrayList<>();
        NsdManager.ResolveListener resolveListener = null;
        synchronized (lock) {
            for (DiscoveryState state : session.discoveryStates.values()) {
                if (state.discoveryListener != null) {
                    discoveryListeners.add(state.discoveryListener);
                }
            }
            resolveListener = session.activeResolveListener;
        }
        if (resolveListener != null) {
            safeStopResolution(resolveListener);
        }
        for (NsdManager.DiscoveryListener discoveryListener : discoveryListeners) {
            safeStopDiscovery(discoveryListener);
        }
    }

    private void onDiscoveryStarted(int sessionId, String serviceType) {
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            DiscoveryState state = session.discoveryStates.get(serviceType);
            if (state == null) {
                return;
            }
            state.started = true;
            state.terminalFailure = false;
            dispatchSnapshotLocked();
        }
    }

    private void onDiscoveryStopped(int sessionId, String serviceType) {
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            DiscoveryState state = session.discoveryStates.get(serviceType);
            if (state == null) {
                return;
            }
            state.started = false;
            state.stopObserved = true;
            dispatchSnapshotLocked();
        }
    }

    private void onStartDiscoveryFailed(int sessionId, String serviceType, int errorCode) {
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            DiscoveryState state = session.discoveryStates.get(serviceType);
            if (state == null) {
                return;
            }
            state.started = false;
            state.terminalFailure = true;
            dispatchSnapshotLocked();
        }
        dispatchError(new DiscoveryError(
                "discover",
                serviceType,
                "",
                errorCode,
                "Service discovery start failed"));
    }

    private void onStopDiscoveryFailed(int sessionId, String serviceType, int errorCode) {
        dispatchError(new DiscoveryError(
                "stop_discover",
                serviceType,
                "",
                errorCode,
                "Service discovery stop failed"));
    }

    private void onServiceFound(int sessionId, NsdServiceInfo serviceInfo) {
        boolean shouldResolve = false;
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            String instanceKey = buildInstanceKey(serviceInfo.getServiceType(), serviceInfo.getServiceName());
            ServiceRecord record = session.recordsByInstanceKey.get(instanceKey);
            if (record == null) {
                record = new ServiceRecord(instanceKey, serviceInfo.getServiceType(), serviceInfo.getServiceName());
                session.recordsByInstanceKey.put(instanceKey, record);
            }
            if (!record.resolved
                    && !session.pendingResolveKeySet.contains(instanceKey)
                    && !instanceKey.equals(session.activeResolveKey)) {
                session.pendingResolveKeys.addLast(instanceKey);
                session.pendingResolveKeySet.add(instanceKey);
                shouldResolve = true;
            }
        }
        if (shouldResolve) {
            processResolveQueue();
        }
    }

    private void onServiceLost(int sessionId, NsdServiceInfo serviceInfo) {
        NsdManager.ResolveListener listenerToStop = null;
        boolean shouldResolveNext = false;
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            String instanceKey = buildInstanceKey(serviceInfo.getServiceType(), serviceInfo.getServiceName());
            session.recordsByInstanceKey.remove(instanceKey);
            session.pendingResolveKeys.remove(instanceKey);
            session.pendingResolveKeySet.remove(instanceKey);
            if (instanceKey.equals(session.activeResolveKey) && session.activeResolveListener != null) {
                listenerToStop = session.activeResolveListener;
                session.activeResolveListener = null;
                session.activeResolveKey = null;
                shouldResolveNext = true;
            }
            dispatchSnapshotLocked();
        }
        if (listenerToStop != null) {
            safeStopResolution(listenerToStop);
        }
        if (shouldResolveNext) {
            processResolveQueue();
        }
    }

    private void onServiceResolved(int sessionId, String instanceKey, NsdServiceInfo serviceInfo) {
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            clearActiveResolveLocked(session, instanceKey);
            ServiceRecord record = session.recordsByInstanceKey.get(instanceKey);
            if (record != null) {
                record.resolved = true;
                record.hostAddress = ReceiverDiscoverySupport.selectPreferredAddress(
                        serviceInfo.getHost(),
                        getResolvedAddresses(serviceInfo));
                record.port = serviceInfo.getPort();
                record.txtAttributes = ReceiverDiscoverySupport.sanitizeTxtAttributes(serviceInfo.getAttributes());
            }
            dispatchSnapshotLocked();
        }
        processResolveQueue();
    }

    private void onResolveFailed(
            int sessionId,
            String instanceKey,
            String serviceType,
            String serviceName,
            int errorCode
    ) {
        boolean shouldRetry = false;
        synchronized (lock) {
            Session session = getSessionLocked(sessionId);
            if (session == null) {
                return;
            }
            clearActiveResolveLocked(session, instanceKey);
            ServiceRecord record = session.recordsByInstanceKey.get(instanceKey);
            if (record != null) {
                record.resolveAttempts += 1;
                if (!record.resolved
                        && record.resolveAttempts < 2
                        && !session.pendingResolveKeySet.contains(instanceKey)) {
                    session.pendingResolveKeys.addLast(instanceKey);
                    session.pendingResolveKeySet.add(instanceKey);
                    shouldRetry = true;
                }
            }
        }
        dispatchError(new DiscoveryError(
                "resolve",
                serviceType,
                serviceName,
                errorCode,
                shouldRetry ? "Service resolution failed, retrying" : "Service resolution failed"));
        processResolveQueue();
    }

    private void processResolveQueue() {
        ResolveWork work = null;
        synchronized (lock) {
            Session session = activeSession;
            if (session == null || session.activeResolveListener != null) {
                return;
            }
            while (!session.pendingResolveKeys.isEmpty()) {
                String instanceKey = session.pendingResolveKeys.removeFirst();
                session.pendingResolveKeySet.remove(instanceKey);
                ServiceRecord record = session.recordsByInstanceKey.get(instanceKey);
                if (record == null) {
                    continue;
                }
                ResolveRequest resolveListener = new ResolveRequest(
                        session.sessionId,
                        instanceKey,
                        record.serviceType,
                        record.serviceName);
                session.activeResolveKey = instanceKey;
                session.activeResolveListener = resolveListener;
                NsdServiceInfo request = new NsdServiceInfo();
                request.setServiceType(record.serviceType);
                request.setServiceName(record.serviceName);
                work = new ResolveWork(request, resolveListener);
                break;
            }
        }
        if (work == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                nsdManager.resolveService(work.request, appContext.getMainExecutor(), work.resolveListener);
            } else {
                nsdManager.resolveService(work.request, work.resolveListener);
            }
        } catch (RuntimeException exception) {
            synchronized (lock) {
                Session session = getSessionLocked(work.resolveListener.sessionId);
                if (session != null) {
                    clearActiveResolveLocked(session, work.resolveListener.instanceKey);
                    ServiceRecord record = session.recordsByInstanceKey.get(work.resolveListener.instanceKey);
                    if (record != null) {
                        record.resolveAttempts += 1;
                        if (!record.resolved
                                && record.resolveAttempts < 2
                                && !session.pendingResolveKeySet.contains(record.instanceKey)) {
                            session.pendingResolveKeys.addLast(record.instanceKey);
                            session.pendingResolveKeySet.add(record.instanceKey);
                        }
                    }
                }
            }
            dispatchError(new DiscoveryError(
                    "resolve",
                    work.resolveListener.serviceType,
                    work.resolveListener.serviceName,
                    NsdManager.FAILURE_INTERNAL_ERROR,
                    exception.getMessage() == null ? "Service resolution crashed" : exception.getMessage()));
            processResolveQueue();
        }
    }

    private void clearActiveResolveLocked(Session session, String instanceKey) {
        if (instanceKey.equals(session.activeResolveKey)) {
            session.activeResolveKey = null;
            session.activeResolveListener = null;
        }
    }

    private Session getSessionLocked(int sessionId) {
        if (activeSession == null || activeSession.sessionId != sessionId) {
            return null;
        }
        return activeSession;
    }

    private void acquireMulticastLockLocked() {
        if (multicastLock == null) {
            multicastLock = wifiManager.createMulticastLock("shengdu:airplay-discovery");
            multicastLock.setReferenceCounted(false);
        }
        if (!multicastLock.isHeld()) {
            multicastLock.acquire();
        }
    }

    private void releaseMulticastLockLocked() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private void dispatchSnapshotLocked() {
        dispatchSnapshot(buildSnapshotLocked());
    }

    private DiscoverySnapshot buildSnapshotLocked() {
        Session session = activeSession;
        if (session == null) {
            return new DiscoverySnapshot(Collections.emptyList(), false);
        }
        List<DeviceAccumulator> devices = new ArrayList<>();
        Map<String, DeviceAccumulator> deviceByCandidateKey = new HashMap<>();
        for (ServiceRecord record : session.recordsByInstanceKey.values()) {
            if (!record.resolved || record.hostAddress.isEmpty() || record.port <= 0) {
                continue;
            }
            List<String> candidateKeys = ReceiverDiscoverySupport.buildCandidateKeys(
                    record.serviceType,
                    record.serviceName,
                    record.hostAddress,
                    record.txtAttributes);
            LinkedHashSet<DeviceAccumulator> matches = new LinkedHashSet<>();
            for (String candidateKey : candidateKeys) {
                DeviceAccumulator existing = deviceByCandidateKey.get(candidateKey);
                if (existing != null) {
                    matches.add(existing);
                }
            }
            DeviceAccumulator accumulator;
            if (matches.isEmpty()) {
                accumulator = new DeviceAccumulator();
                devices.add(accumulator);
            } else {
                accumulator = matches.iterator().next();
                for (DeviceAccumulator other : matches) {
                    if (other != accumulator) {
                        accumulator.absorb(other);
                        devices.remove(other);
                        for (String candidateKey : other.candidateKeys) {
                            deviceByCandidateKey.put(candidateKey, accumulator);
                        }
                    }
                }
            }
            accumulator.add(record, candidateKeys);
            for (String candidateKey : candidateKeys) {
                deviceByCandidateKey.put(candidateKey, accumulator);
            }
        }
        List<ReceiverDevice> snapshotDevices = new ArrayList<>();
        for (DeviceAccumulator device : devices) {
            snapshotDevices.add(device.toReceiverDevice());
        }
        snapshotDevices.sort(DEVICE_COMPARATOR);
        return new DiscoverySnapshot(snapshotDevices, isScanningLocked(session));
    }

    private boolean isScanningLocked(Session session) {
        boolean pending = false;
        for (DiscoveryState state : session.discoveryStates.values()) {
            if (state.started) {
                return true;
            }
            if (!state.terminalFailure && !state.stopObserved) {
                pending = true;
            }
        }
        return pending;
    }

    private void dispatchSnapshot(DiscoverySnapshot snapshot) {
        mainHandler.post(() -> {
            listener.onSnapshot(snapshot);
            if (lastDeliveredScanning == null || snapshot.isScanning() != lastDeliveredScanning) {
                lastDeliveredScanning = snapshot.isScanning();
                listener.onScanningChanged(snapshot.isScanning());
            }
        });
    }

    private void dispatchError(DiscoveryError error) {
        mainHandler.post(() -> listener.onError(error));
    }

    private void safeStopDiscovery(NsdManager.DiscoveryListener discoveryListener) {
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (RuntimeException ignored) {
            // NSD teardown is best-effort and can race with platform callbacks.
        }
    }

    private void safeStopResolution(NsdManager.ResolveListener resolveListener) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                nsdManager.stopServiceResolution(resolveListener);
            } catch (RuntimeException ignored) {
                // Resolution may already be finished when teardown runs.
            }
        }
    }

    private List<java.net.InetAddress> getResolvedAddresses(NsdServiceInfo serviceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            return serviceInfo.getHostAddresses();
        }
        return Collections.emptyList();
    }

    private static String buildInstanceKey(String serviceType, String serviceName) {
        return serviceType + "|" + serviceName;
    }

    private final class ServiceDiscoveryListener implements NsdManager.DiscoveryListener {
        private final int sessionId;
        private final String serviceType;

        private ServiceDiscoveryListener(int sessionId, String serviceType) {
            this.sessionId = sessionId;
            this.serviceType = serviceType;
        }

        @Override
        public void onDiscoveryStarted(String regType) {
            AirPlayDiscovery.this.onDiscoveryStarted(sessionId, serviceType);
        }

        @Override
        public void onServiceFound(NsdServiceInfo serviceInfo) {
            AirPlayDiscovery.this.onServiceFound(sessionId, serviceInfo);
        }

        @Override
        public void onServiceLost(NsdServiceInfo serviceInfo) {
            AirPlayDiscovery.this.onServiceLost(sessionId, serviceInfo);
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
            AirPlayDiscovery.this.onDiscoveryStopped(sessionId, this.serviceType);
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            AirPlayDiscovery.this.onStartDiscoveryFailed(sessionId, this.serviceType, errorCode);
            safeStopDiscovery(this);
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            AirPlayDiscovery.this.onStopDiscoveryFailed(sessionId, this.serviceType, errorCode);
            safeStopDiscovery(this);
        }
    }

    private final class ResolveRequest implements NsdManager.ResolveListener {
        private final int sessionId;
        private final String instanceKey;
        private final String serviceType;
        private final String serviceName;

        private ResolveRequest(int sessionId, String instanceKey, String serviceType, String serviceName) {
            this.sessionId = sessionId;
            this.instanceKey = instanceKey;
            this.serviceType = serviceType;
            this.serviceName = serviceName;
        }

        @Override
        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
            AirPlayDiscovery.this.onResolveFailed(sessionId, instanceKey, serviceType, serviceName, errorCode);
        }

        @Override
        public void onServiceResolved(NsdServiceInfo serviceInfo) {
            AirPlayDiscovery.this.onServiceResolved(sessionId, instanceKey, serviceInfo);
        }
    }

    private static final class ResolveWork {
        private final NsdServiceInfo request;
        private final ResolveRequest resolveListener;

        private ResolveWork(NsdServiceInfo request, ResolveRequest resolveListener) {
            this.request = request;
            this.resolveListener = resolveListener;
        }
    }

    private static final class Session {
        private final int sessionId;
        private final Map<String, DiscoveryState> discoveryStates = new LinkedHashMap<>();
        private final Map<String, ServiceRecord> recordsByInstanceKey = new LinkedHashMap<>();
        private final ArrayDeque<String> pendingResolveKeys = new ArrayDeque<>();
        private final Set<String> pendingResolveKeySet = new HashSet<>();

        private String activeResolveKey;
        private NsdManager.ResolveListener activeResolveListener;

        private Session(int sessionId) {
            this.sessionId = sessionId;
        }
    }

    private static final class DiscoveryState {
        private NsdManager.DiscoveryListener discoveryListener;
        private boolean startIssued;
        private boolean started;
        private boolean stopObserved;
        private boolean terminalFailure;
    }

    private static final class ServiceRecord {
        private final String instanceKey;
        private final String serviceType;
        private final String serviceName;

        private String hostAddress = "";
        private int port;
        private Map<String, String> txtAttributes = Collections.emptyMap();
        private int resolveAttempts;
        private boolean resolved;

        private ServiceRecord(String instanceKey, String serviceType, String serviceName) {
            this.instanceKey = instanceKey;
            this.serviceType = serviceType;
            this.serviceName = serviceName;
        }
    }

    private static final class DeviceAccumulator {
        private final List<ServiceRecord> records = new ArrayList<>();
        private final LinkedHashSet<String> candidateKeys = new LinkedHashSet<>();

        private void add(ServiceRecord record, Collection<String> keys) {
            if (!records.contains(record)) {
                records.add(record);
            }
            candidateKeys.addAll(keys);
        }

        private void absorb(DeviceAccumulator other) {
            for (ServiceRecord record : other.records) {
                add(record, Collections.emptyList());
            }
            candidateKeys.addAll(other.candidateKeys);
        }

        private ReceiverDevice toReceiverDevice() {
            ServiceRecord primary = choosePrimaryRecord();
            Map<String, String> mergedTxt = new LinkedHashMap<>();
            for (ServiceRecord record : records) {
                if (record == primary) {
                    continue;
                }
                mergedTxt.putAll(record.txtAttributes);
            }
            mergedTxt.putAll(primary.txtAttributes);
            List<ServiceRecord> sortedRaopRecords = sortedRaopRecords();
            if (!sortedRaopRecords.isEmpty()) {
                List<Map<String, String>> raopAttributesByPreference = new ArrayList<>();
                for (ServiceRecord record : sortedRaopRecords) {
                    raopAttributesByPreference.add(record.txtAttributes);
                }
                mergedTxt = ReceiverDiscoverySupport.applyRaopCompatibilityAttributes(
                        mergedTxt,
                        raopAttributesByPreference);
            }
            LinkedHashSet<String> advertisedTypes = new LinkedHashSet<>();
            for (ServiceRecord record : records) {
                advertisedTypes.add(record.serviceType);
            }
            String stableKey = ReceiverDiscoverySupport.chooseCanonicalKey(candidateKeys);
            if (stableKey.isEmpty()) {
                stableKey = ReceiverDiscoverySupport.buildStableKey(
                        primary.serviceType,
                        primary.serviceName,
                        primary.hostAddress,
                        primary.txtAttributes);
            }
            return new ReceiverDevice(
                    stableKey,
                    ReceiverDiscoverySupport.buildDisplayName(
                            primary.serviceType,
                            primary.serviceName,
                            primary.txtAttributes),
                    primary.serviceType,
                    primary.serviceName,
                    primary.hostAddress,
                    primary.port,
                    mergedTxt,
                    advertisedTypes);
        }

        private ServiceRecord choosePrimaryRecord() {
            List<ServiceRecord> sortedRaopRecords = sortedRaopRecords();
            if (!sortedRaopRecords.isEmpty()) {
                return sortedRaopRecords.get(0);
            }
            return records.get(0);
        }

        private List<ServiceRecord> sortedRaopRecords() {
            List<ServiceRecord> raopRecords = new ArrayList<>();
            for (ServiceRecord record : records) {
                if (ReceiverDiscoverySupport.SERVICE_TYPE_RAOP.equals(record.serviceType)) {
                    raopRecords.add(record);
                }
            }
            raopRecords.sort((left, right) -> {
                int comparison = Integer.compare(
                        ReceiverDiscoverySupport.raopCompatibilityPreferredScore(right.txtAttributes),
                        ReceiverDiscoverySupport.raopCompatibilityPreferredScore(left.txtAttributes));
                if (comparison != 0) {
                    return comparison;
                }
                comparison = Integer.compare(
                        ReceiverDiscoverySupport.raopCompatibilityCoverageScore(right.txtAttributes),
                        ReceiverDiscoverySupport.raopCompatibilityCoverageScore(left.txtAttributes));
                if (comparison != 0) {
                    return comparison;
                }
                comparison = left.serviceName.compareToIgnoreCase(right.serviceName);
                if (comparison != 0) {
                    return comparison;
                }
                return left.instanceKey.compareTo(right.instanceKey);
            });
            return raopRecords;
        }
    }
}
