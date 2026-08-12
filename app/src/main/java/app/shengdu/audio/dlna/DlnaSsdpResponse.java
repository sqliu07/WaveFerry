package app.shengdu.audio.dlna;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Parsed, bounded SSDP M-SEARCH response for a UPnP MediaRenderer. */
public final class DlnaSsdpResponse {
    public static final String MEDIA_RENDERER_DEVICE_TYPE =
            "urn:schemas-upnp-org:device:MediaRenderer:";

    private final URI location;
    private final String usn;
    private final String searchTarget;

    private DlnaSsdpResponse(URI location, String usn, String searchTarget) {
        this.location = location;
        this.usn = usn;
        this.searchTarget = searchTarget;
    }

    public URI location() {
        return location;
    }

    public String usn() {
        return usn;
    }

    public String searchTarget() {
        return searchTarget;
    }

    public static DlnaSsdpResponse parse(byte[] packet, int length) {
        if (packet == null || length <= 0 || length > packet.length) {
            return null;
        }
        String message = new String(packet, 0, length, StandardCharsets.ISO_8859_1);
        String[] lines = message.split("\\r?\\n");
        if (lines.length == 0 || !isDiscoveryMessage(lines[0])) {
            return null;
        }
        Map<String, String> headers = headers(lines);
        String locationValue = headers.get("location");
        String target = headers.getOrDefault("st", headers.getOrDefault("nt", ""));
        if (locationValue == null) {
            return null;
        }
        try {
            URI location = new URI(locationValue.trim());
            String scheme = location.getScheme();
            if (scheme == null || !"http".equalsIgnoreCase(scheme)
                    || location.getHost() == null) {
                return null;
            }
            return new DlnaSsdpResponse(location, headers.getOrDefault("usn", ""), target.trim());
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static Map<String, String> headers(String[] lines) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int delimiter = line.indexOf(':');
            if (delimiter <= 0) {
                continue;
            }
            String key = line.substring(0, delimiter).trim().toLowerCase(Locale.US);
            String value = line.substring(delimiter + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                result.putIfAbsent(key, value);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean isDiscoveryMessage(String firstLine) {
        String normalized = firstLine.trim().toUpperCase(Locale.US);
        return normalized.startsWith("HTTP/1.1 200") || normalized.startsWith("NOTIFY * HTTP/");
    }

}
