package app.shengdu.audio.dlna;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.InetAddress;
import java.net.URI;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/** Parses a MediaRenderer description without resolving external XML entities. */
public final class DlnaDeviceDescriptionParser {
    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:";
    private static final String CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:";
    private static final String RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:";
    private static final String ACCESS_EXTERNAL_DTD =
            "http://javax.xml.XMLConstants/property/accessExternalDTD";
    private static final String ACCESS_EXTERNAL_SCHEMA =
            "http://javax.xml.XMLConstants/property/accessExternalSchema";

    private DlnaDeviceDescriptionParser() {
    }

    public static DlnaRendererDescription parse(String xml, URI descriptionUri) throws Exception {
        return parse(xml, descriptionUri, InetAddress.getByName(descriptionUri.getHost()));
    }

    static DlnaRendererDescription parse(
            String xml, URI descriptionUri, InetAddress deviceAddress) throws Exception {
        if (xml == null || xml.length() > 512 * 1024 || descriptionUri == null) {
            throw new IllegalArgumentException("Invalid DLNA device description");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setAttributeIfSupported(factory, ACCESS_EXTERNAL_DTD, "");
        setAttributeIfSupported(factory, ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException ignored) {
            // Not exposed by every Android XML implementation.
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        Document document = builder.parse(new InputSource(new StringReader(xml)));

        Element device = findRenderer(document.getDocumentElement());
        if (device == null) {
            throw new IllegalArgumentException("Description is not a MediaRenderer");
        }
        String urlBase = text(document.getDocumentElement(), "URLBase");
        URI baseUri = urlBase.isEmpty() ? descriptionUri : descriptionUri.resolve(urlBase);
        String udn = text(device, "UDN");
        String name = text(device, "friendlyName");
        if (udn.isEmpty()) {
            udn = "location:" + descriptionUri;
        }
        if (name.isEmpty()) {
            name = descriptionUri.getHost();
        }
        return new DlnaRendererDescription(
                udn,
                name,
                descriptionUri,
                serviceControlUri(device, baseUri, AV_TRANSPORT, deviceAddress),
                serviceType(device, AV_TRANSPORT),
                serviceControlUri(device, baseUri, CONNECTION_MANAGER, deviceAddress),
                serviceType(device, CONNECTION_MANAGER),
                serviceControlUri(device, baseUri, RENDERING_CONTROL, deviceAddress));
    }

    private static URI serviceControlUri(
            Element device,
            URI baseUri,
            String expectedServiceType,
            InetAddress deviceAddress) {
        NodeList services = device.getElementsByTagNameNS("*", "service");
        for (int index = 0; index < services.getLength(); index++) {
            Node node = services.item(index);
            if (!(node instanceof Element)) {
                continue;
            }
            Element service = (Element) node;
            if (!DlnaProtocol.isVersionedServiceType(
                    text(service, "serviceType"), expectedServiceType)) {
                continue;
            }
            String controlUrl = text(service, "controlURL");
            if (controlUrl.isEmpty()) {
                return null;
            }
            URI resolved = baseUri.resolve(controlUrl);
            return DlnaEndpointPolicy.acceptsControlUri(resolved, deviceAddress)
                    ? resolved
                    : null;
        }
        return null;
    }

    private static String serviceType(Element device, String expectedServiceType) {
        NodeList services = device.getElementsByTagNameNS("*", "service");
        for (int index = 0; index < services.getLength(); index++) {
            Node node = services.item(index);
            if (node instanceof Element) {
                String type = text((Element) node, "serviceType");
                if (DlnaProtocol.isVersionedServiceType(type, expectedServiceType)) {
                    return type;
                }
            }
        }
        return "";
    }

    private static void setFeatureIfSupported(
            DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException | AbstractMethodError ignored) {
            // Android XML implementations expose different subsets of JAXP features.
        }
    }

    private static void setAttributeIfSupported(
            DocumentBuilderFactory factory, String attribute, Object value) {
        try {
            factory.setAttribute(attribute, value);
        } catch (IllegalArgumentException | AbstractMethodError ignored) {
            // The entity resolver remains installed even when this JAXP property is absent.
        }
    }

    private static Element findRenderer(Element root) {
        NodeList nodes = root.getElementsByTagNameNS("*", "device");
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element) {
                Element device = (Element) nodes.item(index);
                if (text(device, "deviceType")
                        .startsWith(DlnaSsdpResponse.MEDIA_RENDERER_DEVICE_TYPE)
                        || !serviceType(device, AV_TRANSPORT).isEmpty()) {
                    return device;
                }
            }
        }
        return null;
    }

    private static String text(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            return "";
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? "" : value.trim();
    }

}
