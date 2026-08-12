package app.shengdu.audio.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

public final class DlnaDeviceDescriptionParserTest {
    @Test
    public void resolvesRendererControlEndpoints() throws Exception {
        String xml = "<root><URLBase>http://192.168.1.8:8200/</URLBase><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>Living Room TV</friendlyName><UDN>uuid:tv</UDN><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>/upnp/control/av</controlURL></service>"
                + "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>"
                + "<controlURL>cm</controlURL></service>"
                + "</serviceList></device></root>";

        DlnaRendererDescription description = DlnaDeviceDescriptionParser.parse(
                xml, new URI("http://192.168.1.8:8200/root.xml"));

        assertEquals("Living Room TV", description.friendlyName());
        assertEquals("http://192.168.1.8:8200/upnp/control/av", description.avTransportControlUri().toString());
        assertEquals(DlnaSinkCapabilities.WavSupport.UNKNOWN, description.wavSupport());
        assertTrue(description.canReceiveMedia());
    }

    @Test
    public void findsNestedMediaRendererDevice() throws Exception {
        String xml = "<root><device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>"
                + "<deviceList><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:3</deviceType>"
                + "<friendlyName>TV Renderer</friendlyName><UDN>uuid:renderer</UDN><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType>"
                + "<controlURL>/av</controlURL></service></serviceList>"
                + "</device></deviceList></device></root>";

        DlnaRendererDescription description = DlnaDeviceDescriptionParser.parse(
                xml, new URI("http://192.168.1.9/description.xml"));

        assertEquals("TV Renderer", description.friendlyName());
        assertEquals("urn:schemas-upnp-org:service:AVTransport:2", description.avTransportServiceType());
    }

    @Test
    public void acceptsVendorDeviceTypeWhenItExposesAvTransport() throws Exception {
        String xml = "<root><device><deviceType>urn:lge-com:device:SmartTV:1</deviceType>"
                + "<friendlyName>LG webOS TV</friendlyName><UDN>uuid:lg</UDN><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>/avtransport</controlURL></service></serviceList></device></root>";

        DlnaRendererDescription description = DlnaDeviceDescriptionParser.parse(
                xml, new URI("http://192.168.1.10/description.xml"));

        assertEquals("LG webOS TV", description.friendlyName());
        assertTrue(description.canReceiveMedia());
    }

    @Test
    public void rejectsControlEndpointOnAnotherHost() throws Exception {
        String xml = "<root><URLBase>http://192.168.1.9:8200/</URLBase><device>"
                + "<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"
                + "<friendlyName>Untrusted Renderer</friendlyName><serviceList>"
                + "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>"
                + "<controlURL>/av</controlURL></service></serviceList></device></root>";

        DlnaRendererDescription description = DlnaDeviceDescriptionParser.parse(
                xml, new URI("http://192.168.1.8:8200/root.xml"));

        assertNull(description.avTransportControlUri());
    }
}
