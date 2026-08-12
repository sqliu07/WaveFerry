package app.shengdu.audio.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ReceiverDiscoverySupport {
    static final String SERVICE_TYPE_RAOP = "_raop._tcp.";
    static final String SERVICE_TYPE_AIRPLAY = "_airplay._tcp.";
    static final String SERVICE_TYPE_DLNA = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final List<String> RAOP_COMPATIBILITY_KEYS =
            List.of("tp", "cn", "sr", "ss", "ch", "et");

    private static final Comparator<String> STRING_COMPARATOR = String::compareToIgnoreCase;

    private ReceiverDiscoverySupport() {
    }

    static String buildStableKey(
            String serviceType,
            String serviceName,
            String hostAddress,
            Map<String, String> txtAttributes
    ) {
        String deviceId = resolveDeviceId(serviceName, txtAttributes);
        if (!deviceId.isEmpty()) {
            return "deviceid:" + deviceId;
        }
        if (!hostAddress.isEmpty()) {
            return "host:" + hostAddress.toLowerCase(Locale.US);
        }
        return "service:" + normalizeToken(serviceType) + ":" + normalizeToken(cleanDisplayName(serviceName));
    }

    static List<String> buildCandidateKeys(
            String serviceType,
            String serviceName,
            String hostAddress,
            Map<String, String> txtAttributes
    ) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        String deviceId = resolveDeviceId(serviceName, txtAttributes);
        if (!deviceId.isEmpty()) {
            keys.add("deviceid:" + deviceId);
        }
        if (!hostAddress.isEmpty()) {
            keys.add("host:" + hostAddress.toLowerCase(Locale.US));
        }
        if (keys.isEmpty()) {
            keys.add("service:" + normalizeToken(serviceType) + ":" + normalizeToken(cleanDisplayName(serviceName)));
        }
        return Collections.unmodifiableList(new ArrayList<>(keys));
    }

    static String chooseCanonicalKey(Collection<String> candidateKeys) {
        String fallback = "";
        for (String key : candidateKeys) {
            if (key.startsWith("deviceid:")) {
                return key;
            }
            if (fallback.isEmpty() || fallback.startsWith("service:")) {
                fallback = key;
            }
        }
        return fallback;
    }

    static String buildDisplayName(String serviceType, String serviceName, Map<String, String> txtAttributes) {
        for (String key : Arrays.asList("fn", "name")) {
            String value = lookupAttribute(txtAttributes, key);
            if (!value.isEmpty()) {
                return value;
            }
        }
        if (SERVICE_TYPE_RAOP.equals(serviceType)) {
            int delimiter = serviceName.indexOf('@');
            if (delimiter >= 0 && delimiter + 1 < serviceName.length()) {
                return serviceName.substring(delimiter + 1);
            }
        }
        String cleanedServiceName = cleanDisplayName(serviceName);
        if (!cleanedServiceName.isEmpty()) {
            return cleanedServiceName;
        }
        for (String key : Arrays.asList("model", "manufacturer")) {
            String value = lookupAttribute(txtAttributes, key);
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "AirPlay 设备";
    }

    static String selectPreferredAddress(InetAddress host, List<InetAddress> hostAddresses) {
        List<InetAddress> candidates = new ArrayList<>();
        if (hostAddresses != null) {
            candidates.addAll(hostAddresses);
        }
        if (host != null) {
            candidates.add(host);
        }
        String ipv6Candidate = "";
        for (InetAddress candidate : candidates) {
            String value = sanitizeHostAddress(candidate);
            if (value.isEmpty()) {
                continue;
            }
            if (candidate instanceof Inet4Address) {
                return value;
            }
            if (ipv6Candidate.isEmpty()) {
                ipv6Candidate = value;
            }
        }
        return ipv6Candidate;
    }

    static String sanitizeHostAddress(InetAddress address) {
        if (address == null) {
            return "";
        }
        String hostAddress = address.getHostAddress();
        if (hostAddress == null || hostAddress.isEmpty()) {
            return "";
        }
        int scopeIndex = hostAddress.indexOf('%');
        if (scopeIndex >= 0) {
            return hostAddress.substring(0, scopeIndex);
        }
        return hostAddress;
    }

    static Map<String, String> sanitizeTxtAttributes(Map<String, byte[]> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = new ArrayList<>(attributes.keySet());
        keys.sort(STRING_COMPARATOR);
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        for (String key : keys) {
            if (key == null || key.isEmpty()) {
                continue;
            }
            byte[] rawValue = attributes.get(key);
            sanitized.put(key, decodeTxtValue(rawValue));
        }
        return Collections.unmodifiableMap(sanitized);
    }

    static Map<String, String> mergeTxtAttributes(
            Map<String, String> primary,
            Map<String, String> secondary
    ) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (secondary != null) {
            List<String> secondaryKeys = new ArrayList<>(secondary.keySet());
            secondaryKeys.sort(STRING_COMPARATOR);
            for (String key : secondaryKeys) {
                merged.put(key, secondary.get(key));
            }
        }
        if (primary != null) {
            List<String> primaryKeys = new ArrayList<>(primary.keySet());
            primaryKeys.sort(STRING_COMPARATOR);
            for (String key : primaryKeys) {
                merged.put(key, primary.get(key));
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    static Map<String, String> applyRaopCompatibilityAttributes(
            Map<String, String> mergedAttributes,
            List<Map<String, String>> raopAttributesByPreference
    ) {
        LinkedHashMap<String, String> sanitized = new LinkedHashMap<>();
        if (mergedAttributes != null) {
            for (Map.Entry<String, String> entry : mergedAttributes.entrySet()) {
                if (!isRaopCompatibilityKey(entry.getKey())) {
                    sanitized.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (raopAttributesByPreference != null) {
            for (String key : RAOP_COMPATIBILITY_KEYS) {
                for (Map<String, String> attributes : raopAttributesByPreference) {
                    String value = lookupAttribute(attributes, key);
                    if (!value.isEmpty()) {
                        sanitized.put(key, value);
                        break;
                    }
                }
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    static boolean isRaopCompatibilityKey(String key) {
        if (key == null) {
            return false;
        }
        for (String candidate : RAOP_COMPATIBILITY_KEYS) {
            if (candidate.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    static int raopCompatibilityPreferredScore(Map<String, String> attributes) {
        int score = 0;
        score += preferredTokenScore(attributes, "tp", "TCP", "UDP");
        score += preferredTokenScore(attributes, "cn", "1");
        score += preferredTokenScore(attributes, "sr", "44100");
        score += preferredTokenScore(attributes, "ss", "16");
        score += preferredTokenScore(attributes, "ch", "2");
        score += preferredTokenScore(attributes, "et", "0", "1");
        return score;
    }

    static int raopCompatibilityCoverageScore(Map<String, String> attributes) {
        int score = 0;
        for (String key : RAOP_COMPATIBILITY_KEYS) {
            if (!lookupAttribute(attributes, key).isEmpty()) {
                score += 1;
            }
        }
        return score;
    }

    static Set<String> mergeServiceTypes(Set<String> left, Set<String> right) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return Collections.unmodifiableSet(merged);
    }

    static String normalizeDeviceId(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace(":", "").replace("-", "").toLowerCase(Locale.US);
        if (normalized.matches("[0-9a-f]{12}")) {
            return normalized;
        }
        return "";
    }

    static String extractRaopDeviceId(String serviceName) {
        if (serviceName == null) {
            return "";
        }
        int separator = serviceName.indexOf('@');
        String rawPrefix = separator >= 0 ? serviceName.substring(0, separator) : serviceName;
        return normalizeDeviceId(rawPrefix);
    }

    private static String resolveDeviceId(String serviceName, Map<String, String> txtAttributes) {
        String fromTxt = lookupAttribute(txtAttributes, "deviceid");
        if (fromTxt.isEmpty()) {
            fromTxt = lookupAttribute(txtAttributes, "deviceId");
        }
        if (fromTxt.isEmpty()) {
            fromTxt = lookupAttribute(txtAttributes, "macAddress");
        }
        String normalized = normalizeDeviceId(fromTxt);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return extractRaopDeviceId(serviceName);
    }

    private static String decodeTxtValue(byte[] rawValue) {
        if (rawValue == null || rawValue.length == 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(rawValue));
            String text = decoded.toString();
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isISOControl(current) && !Character.isWhitespace(current)) {
                    return toHex(rawValue);
                }
            }
            return text;
        } catch (CharacterCodingException exception) {
            return toHex(rawValue);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }

    private static String lookupAttribute(Map<String, String> txtAttributes, String key) {
        if (txtAttributes == null || txtAttributes.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String> entry : txtAttributes.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? "" : entry.getValue().trim();
            }
        }
        return "";
    }

    private static int preferredTokenScore(
            Map<String, String> attributes,
            String key,
            String... expectedTokens
    ) {
        String raw = lookupAttribute(attributes, key);
        if (raw.isEmpty()) {
            return 0;
        }
        List<String> tokens = splitTokens(raw);
        for (String token : tokens) {
            for (String expectedToken : expectedTokens) {
                if (expectedToken.equalsIgnoreCase(token)) {
                    return 1;
                }
            }
        }
        return 0;
    }

    private static List<String> splitTokens(String raw) {
        String[] parts = raw.split("[,;/\\s]+");
        ArrayList<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalizeToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US);
    }

    private static String cleanDisplayName(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
