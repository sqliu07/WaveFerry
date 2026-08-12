package app.shengdu.audio.discovery;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ReceiverDevice {
    private final String stableKey;
    private final String displayName;
    private final String serviceType;
    private final String serviceName;
    private final String address;
    private final int port;
    private final Map<String, String> txtAttributes;
    private final Set<String> advertisedServiceTypes;

    public ReceiverDevice(
            String stableKey,
            String displayName,
            String serviceType,
            String serviceName,
            String address,
            int port,
            Map<String, String> txtAttributes,
            Set<String> advertisedServiceTypes
    ) {
        this.stableKey = Objects.requireNonNull(stableKey, "stableKey");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.address = Objects.requireNonNull(address, "address");
        this.port = port;
        this.txtAttributes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(txtAttributes, "txtAttributes")));
        this.advertisedServiceTypes = Collections.unmodifiableSet(new LinkedHashSet<>(
                Objects.requireNonNull(advertisedServiceTypes, "advertisedServiceTypes")));
    }

    public String getStableKey() {
        return stableKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getServiceType() {
        return serviceType;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public Map<String, String> getTxtAttributes() {
        return txtAttributes;
    }

    public Set<String> getAdvertisedServiceTypes() {
        return advertisedServiceTypes;
    }

    public boolean supportsRaop() {
        return advertisedServiceTypes.contains(ReceiverDiscoverySupport.SERVICE_TYPE_RAOP);
    }

    public boolean supportsAirPlay() {
        return advertisedServiceTypes.contains(ReceiverDiscoverySupport.SERVICE_TYPE_AIRPLAY);
    }

    public boolean supportsDlna() {
        return advertisedServiceTypes.contains(ReceiverDiscoverySupport.SERVICE_TYPE_DLNA);
    }

    public String getKey() {
        return getStableKey();
    }

    public String getHostAddress() {
        return getAddress();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReceiverDevice)) {
            return false;
        }
        ReceiverDevice that = (ReceiverDevice) other;
        return port == that.port
                && stableKey.equals(that.stableKey)
                && displayName.equals(that.displayName)
                && serviceType.equals(that.serviceType)
                && serviceName.equals(that.serviceName)
                && address.equals(that.address)
                && txtAttributes.equals(that.txtAttributes)
                && advertisedServiceTypes.equals(that.advertisedServiceTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                stableKey,
                displayName,
                serviceType,
                serviceName,
                address,
                port,
                txtAttributes,
                advertisedServiceTypes);
    }

    @Override
    public String toString() {
        return displayName;
    }
}
