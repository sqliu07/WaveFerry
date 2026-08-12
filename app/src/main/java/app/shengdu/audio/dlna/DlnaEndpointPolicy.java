package app.shengdu.audio.dlna;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;

/** Keeps SSDP-provided HTTP endpoints bound to the device that sent the response. */
final class DlnaEndpointPolicy {
    private DlnaEndpointPolicy() {
    }

    static boolean acceptsDescription(URI uri, InetAddress senderAddress) {
        return isPlainHttpUri(uri)
                && isLanAddress(senderAddress)
                && hostResolvesTo(uri, senderAddress);
    }

    static boolean acceptsControlUri(URI uri, InetAddress deviceAddress) {
        return isPlainHttpUri(uri) && hostResolvesTo(uri, deviceAddress);
    }

    private static boolean isPlainHttpUri(URI uri) {
        if (uri == null
                || !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            return false;
        }
        int port = uri.getPort();
        return port == -1 || port > 0;
    }

    private static boolean hostResolvesTo(URI uri, InetAddress expectedAddress) {
        if (expectedAddress == null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int scopeDelimiter = host.indexOf('%');
        if (scopeDelimiter >= 0) {
            return false;
        }
        byte[] literalAddress = parseNumericAddress(host);
        return literalAddress != null
                && Arrays.equals(literalAddress, expectedAddress.getAddress());
    }

    private static byte[] parseNumericAddress(String host) {
        if (host.indexOf(':') >= 0) {
            // A colon distinguishes an IPv6 literal. Restrict the character set before
            // asking InetAddress to parse so this path can never perform DNS resolution.
            if (!host.matches("[0-9a-fA-F:.]+")) {
                return null;
            }
            try {
                byte[] address = InetAddress.getByName(host).getAddress();
                return address.length == 16 ? address : null;
            } catch (Exception ignored) {
                return null;
            }
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] address = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || !part.matches("[0-9]{1,3}")) {
                return null;
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (value > 255) {
                return null;
            }
            address[index] = (byte) value;
        }
        return address;
    }

    private static boolean isLanAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        // IPv6 unique-local addresses (fc00::/7).
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
