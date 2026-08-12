package app.shengdu.audio.dlna;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.URI;

import org.junit.Test;

public final class DlnaEndpointPolicyTest {
    private static final InetAddress DEVICE = address("192.168.1.8");

    @Test
    public void acceptsHttpDescriptionFromItsSsdpSender() throws Exception {
        assertTrue(DlnaEndpointPolicy.acceptsDescription(
                new URI("http://192.168.1.8:49152/desc.xml"), DEVICE));
    }

    @Test
    public void rejectsLocationPointingAwayFromSsdpSender() throws Exception {
        assertFalse(DlnaEndpointPolicy.acceptsDescription(
                new URI("http://192.168.1.9:49152/desc.xml"), DEVICE));
    }

    @Test
    public void rejectsHostnameToPreventDnsRebinding() throws Exception {
        assertFalse(DlnaEndpointPolicy.acceptsDescription(
                new URI("http://renderer.local:49152/desc.xml"), DEVICE));
        assertFalse(DlnaEndpointPolicy.acceptsControlUri(
                new URI("http://renderer.local:1400/avtransport"), DEVICE));
    }

    @Test
    public void rejectsHttpsAndEmbeddedCredentials() throws Exception {
        assertFalse(DlnaEndpointPolicy.acceptsDescription(
                new URI("https://192.168.1.8/desc.xml"), DEVICE));
        assertFalse(DlnaEndpointPolicy.acceptsDescription(
                new URI("http://user@192.168.1.8/desc.xml"), DEVICE));
    }

    @Test
    public void allowsDifferentControlPortOnSameDeviceOnly() throws Exception {
        assertTrue(DlnaEndpointPolicy.acceptsControlUri(
                new URI("http://192.168.1.8:1400/avtransport"), DEVICE));
        assertFalse(DlnaEndpointPolicy.acceptsControlUri(
                new URI("http://192.168.1.9:1400/avtransport"), DEVICE));
    }

    @Test
    public void acceptsMatchingIpv6Literal() throws Exception {
        InetAddress ipv6 = address("fd00::8");
        assertTrue(DlnaEndpointPolicy.acceptsDescription(
                new URI("http://[fd00::8]:49152/desc.xml"), ipv6));
    }

    @Test
    public void rejectsIpv6ScopeIdentifierFromRemoteDescription() throws Exception {
        InetAddress ipv6 = address("fe80::8");
        assertFalse(DlnaEndpointPolicy.acceptsDescription(
                new URI("http://[fe80::8%25wlan0]:49152/desc.xml"), ipv6));
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
