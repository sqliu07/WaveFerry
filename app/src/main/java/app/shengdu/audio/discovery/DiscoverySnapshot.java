package app.shengdu.audio.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class DiscoverySnapshot {
    private final List<ReceiverDevice> devices;
    private final boolean scanning;

    public DiscoverySnapshot(List<ReceiverDevice> devices, boolean scanning) {
        this.devices = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(devices, "devices")));
        this.scanning = scanning;
    }

    public List<ReceiverDevice> getDevices() {
        return devices;
    }

    public boolean isScanning() {
        return scanning;
    }
}
