package app.shengdu.audio.discovery;

import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReceiverDiscoverySupportTest {
    @Test
    public void raopDeviceIdBecomesStableKey() {
        String key = ReceiverDiscoverySupport.buildStableKey(
                ReceiverDiscoverySupport.SERVICE_TYPE_RAOP,
                "001122334455@Living Room",
                "192.168.1.8",
                Map.of());

        assertEquals("deviceid:001122334455", key);
    }

    @Test
    public void hostFallbackCreatesMergeCandidateWhenTxtLacksDeviceId() {
        List<String> keys = ReceiverDiscoverySupport.buildCandidateKeys(
                ReceiverDiscoverySupport.SERVICE_TYPE_AIRPLAY,
                "Living Room",
                "fe80::abcd",
                Map.of());

        assertEquals(List.of("host:fe80::abcd"), keys);
    }

    @Test
    public void txtAttributesDecodeUtf8AndHexFallback() {
        Map<String, byte[]> attributes = new LinkedHashMap<>();
        attributes.put("fn", "客厅电视".getBytes(StandardCharsets.UTF_8));
        attributes.put("bad", new byte[]{0x01, 0x02});

        Map<String, String> sanitized = ReceiverDiscoverySupport.sanitizeTxtAttributes(attributes);

        assertEquals("客厅电视", sanitized.get("fn"));
        assertEquals("0102", sanitized.get("bad"));
    }

    @Test
    public void preferredAddressReturnsIpv4BeforeIpv6() throws Exception {
        String address = ReceiverDiscoverySupport.selectPreferredAddress(
                null,
                List.of(
                        InetAddress.getByName("fe80::1"),
                        InetAddress.getByName("192.168.1.42")));

        assertEquals("192.168.1.42", address);
    }

    @Test
    public void displayNamePrefersFriendlyTxtValue() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("fn", "Bedroom TV");

        String displayName = ReceiverDiscoverySupport.buildDisplayName(
                ReceiverDiscoverySupport.SERVICE_TYPE_RAOP,
                "001122334455@Ignored",
                attributes);

        assertEquals("Bedroom TV", displayName);
    }

    @Test
    public void raopCodecTxtIsNotUsedAsDisplayName() {
        String displayName = ReceiverDiscoverySupport.buildDisplayName(
                ReceiverDiscoverySupport.SERVICE_TYPE_RAOP,
                "001122334455@Living Room",
                Map.of("cn", "0,1"));

        assertEquals("Living Room", displayName);
    }

    @Test
    public void canonicalKeyPrefersDeviceIdOverHost() {
        String key = ReceiverDiscoverySupport.chooseCanonicalKey(
                List.of("host:192.168.1.2", "deviceid:001122334455"));

        assertEquals("deviceid:001122334455", key);
    }

    @Test
    public void mergedTxtLetsPrimaryOverrideSecondary() {
        Map<String, String> secondary = new LinkedHashMap<>();
        secondary.put("model", "AppleTV5,3");
        secondary.put("fn", "Old");
        Map<String, String> primary = new LinkedHashMap<>();
        primary.put("fn", "Living Room");

        Map<String, String> merged = ReceiverDiscoverySupport.mergeTxtAttributes(primary, secondary);

        assertEquals("Living Room", merged.get("fn"));
        assertEquals("AppleTV5,3", merged.get("model"));
        assertTrue(merged.containsKey("fn"));
        assertTrue(merged.containsKey("model"));
    }

    @Test
    public void airPlayCompatibilityKeysCannotPolluteMergedReceiverTxt() {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("fn", "Living Room");
        merged.put("tp", "UDP");
        merged.put("cn", "0");
        merged.put("et", "4");

        Map<String, String> sanitized = ReceiverDiscoverySupport.applyRaopCompatibilityAttributes(
                merged,
                List.of(Map.of("fn", "Living Room")));

        assertEquals("Living Room", sanitized.get("fn"));
        assertTrue(!sanitized.containsKey("tp"));
        assertTrue(!sanitized.containsKey("cn"));
        assertTrue(!sanitized.containsKey("et"));
    }

    @Test
    public void raopCompatibilityAttributesPreferBestRecordAndBackfillMissingKeys() {
        Map<String, String> richerRaop = new LinkedHashMap<>();
        richerRaop.put("tp", "TCP");
        richerRaop.put("cn", "1");
        Map<String, String> backupRaop = new LinkedHashMap<>();
        backupRaop.put("tp", "UDP");
        backupRaop.put("sr", "44100");
        backupRaop.put("et", "0,1");

        Map<String, String> merged = new LinkedHashMap<>();
        merged.put("model", "AppleTV");
        merged.put("tp", "UDP");
        merged.put("sr", "48000");

        Map<String, String> sanitized = ReceiverDiscoverySupport.applyRaopCompatibilityAttributes(
                merged,
                List.of(richerRaop, backupRaop));

        assertEquals("AppleTV", sanitized.get("model"));
        assertEquals("TCP", sanitized.get("tp"));
        assertEquals("1", sanitized.get("cn"));
        assertEquals("44100", sanitized.get("sr"));
        assertEquals("0,1", sanitized.get("et"));
    }

    @Test
    public void preferredCompatibilityScoreRewardsLegacyCompatibleTokens() {
        Map<String, String> compatible = new LinkedHashMap<>();
        compatible.put("tp", "TCP");
        compatible.put("cn", "0,1");
        compatible.put("sr", "44100");
        compatible.put("ss", "16");
        compatible.put("ch", "2");
        compatible.put("et", "0,1");

        Map<String, String> incompatible = new LinkedHashMap<>();
        incompatible.put("tp", "UDP");
        incompatible.put("cn", "0");
        incompatible.put("sr", "48000");
        incompatible.put("ss", "24");
        incompatible.put("ch", "1");
        incompatible.put("et", "4");

        assertTrue(
                ReceiverDiscoverySupport.raopCompatibilityPreferredScore(compatible)
                        > ReceiverDiscoverySupport.raopCompatibilityPreferredScore(incompatible));
        assertEquals(
                ReceiverDiscoverySupport.raopCompatibilityCoverageScore(compatible),
                ReceiverDiscoverySupport.raopCompatibilityCoverageScore(incompatible));

        Map<String, String> udpCompatible = new LinkedHashMap<>(compatible);
        udpCompatible.put("tp", "UDP");
        assertEquals(
                ReceiverDiscoverySupport.raopCompatibilityPreferredScore(compatible),
                ReceiverDiscoverySupport.raopCompatibilityPreferredScore(udpCompatible));
    }
}
