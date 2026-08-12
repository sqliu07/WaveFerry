package app.shengdu.audio.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class DlnaSsdpResponseTest {
    @Test
    public void parsesMediaRendererResponse() {
        String response = "HTTP/1.1 200 OK\r\n"
                + "LOCATION: http://192.168.1.8:49152/desc.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n"
                + "USN: uuid:renderer::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";

        DlnaSsdpResponse parsed = DlnaSsdpResponse.parse(
                response.getBytes(StandardCharsets.ISO_8859_1), response.length());

        assertNotNull(parsed);
        assertEquals("192.168.1.8", parsed.location().getHost());
        assertEquals("uuid:renderer::urn:schemas-upnp-org:device:MediaRenderer:1", parsed.usn());
    }

    @Test
    public void parsesGenericSsdpResponseForLaterDeviceDescriptionFiltering() {
        String response = "HTTP/1.1 200 OK\r\n"
                + "LOCATION: http://192.168.1.1/desc.xml\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n";

        assertNotNull(DlnaSsdpResponse.parse(
                response.getBytes(StandardCharsets.ISO_8859_1), response.length()));
    }

    @Test
    public void parsesNotifyAliveBroadcast() {
        String notify = "NOTIFY * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "NT: upnp:rootdevice\r\n"
                + "NTS: ssdp:alive\r\n"
                + "LOCATION: http://192.168.1.10:8080/description.xml\r\n\r\n";

        DlnaSsdpResponse parsed = DlnaSsdpResponse.parse(
                notify.getBytes(StandardCharsets.ISO_8859_1), notify.length());

        assertNotNull(parsed);
        assertEquals("192.168.1.10", parsed.location().getHost());
    }
}
