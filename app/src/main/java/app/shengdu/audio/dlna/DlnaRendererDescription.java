package app.shengdu.audio.dlna;

import java.net.URI;
import java.util.Objects;

/** Control endpoints obtained from a MediaRenderer device-description document. */
public final class DlnaRendererDescription {
    private final String udn;
    private final String friendlyName;
    private final URI descriptionUri;
    private final URI avTransportControlUri;
    private final String avTransportServiceType;
    private final URI connectionManagerControlUri;
    private final String connectionManagerServiceType;
    private final URI renderingControlUri;
    private final DlnaSinkCapabilities.WavSupport wavSupport;

    public DlnaRendererDescription(
            String udn,
            String friendlyName,
            URI descriptionUri,
            URI avTransportControlUri,
            String avTransportServiceType,
            URI connectionManagerControlUri,
            String connectionManagerServiceType,
            URI renderingControlUri) {
        this(
                udn,
                friendlyName,
                descriptionUri,
                avTransportControlUri,
                avTransportServiceType,
                connectionManagerControlUri,
                connectionManagerServiceType,
                renderingControlUri,
                DlnaSinkCapabilities.WavSupport.UNKNOWN);
    }

    public DlnaRendererDescription(
            String udn,
            String friendlyName,
            URI descriptionUri,
            URI avTransportControlUri,
            String avTransportServiceType,
            URI connectionManagerControlUri,
            String connectionManagerServiceType,
            URI renderingControlUri,
            DlnaSinkCapabilities.WavSupport wavSupport) {
        this.udn = Objects.requireNonNull(udn, "udn");
        this.friendlyName = Objects.requireNonNull(friendlyName, "friendlyName");
        this.descriptionUri = Objects.requireNonNull(descriptionUri, "descriptionUri");
        this.avTransportControlUri = avTransportControlUri;
        this.avTransportServiceType = avTransportServiceType == null ? "" : avTransportServiceType;
        this.connectionManagerControlUri = connectionManagerControlUri;
        this.connectionManagerServiceType = connectionManagerServiceType == null
                ? "" : connectionManagerServiceType;
        this.renderingControlUri = renderingControlUri;
        this.wavSupport = Objects.requireNonNull(wavSupport, "wavSupport");
    }

    public String udn() { return udn; }
    public String friendlyName() { return friendlyName; }
    public URI descriptionUri() { return descriptionUri; }
    public URI avTransportControlUri() { return avTransportControlUri; }
    public String avTransportServiceType() { return avTransportServiceType; }
    public URI connectionManagerControlUri() { return connectionManagerControlUri; }
    public String connectionManagerServiceType() { return connectionManagerServiceType; }
    public URI renderingControlUri() { return renderingControlUri; }
    public DlnaSinkCapabilities.WavSupport wavSupport() { return wavSupport; }

    public DlnaRendererDescription withWavSupport(DlnaSinkCapabilities.WavSupport support) {
        return new DlnaRendererDescription(
                udn,
                friendlyName,
                descriptionUri,
                avTransportControlUri,
                avTransportServiceType,
                connectionManagerControlUri,
                connectionManagerServiceType,
                renderingControlUri,
                support);
    }

    public boolean canReceiveMedia() {
        return avTransportControlUri != null;
    }
}
