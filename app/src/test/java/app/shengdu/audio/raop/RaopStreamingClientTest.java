package app.shengdu.audio.raop;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class RaopStreamingClientTest {
    @Test
    public void acceptsNegotiated48KFormatWhenReceiverAdvertisesIt() {
        RaopStreamingClient sender = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("sr", "48000", "ss", "16", "ch", "2", "cn", "1", "et", "0"),
                new AudioStreamFormat(48000, 2, SampleFormat.PCM16_LE));

        assertEquals(RaopCompatibility.LEGACY_CLEAR, sender.compatibility());
        assertEquals(48000, sender.sampleRateHz());
    }

    @Test
    public void alacWrapperKeepsPcmAlignedAfterThe55BitHeader() {
        byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
        byte[] encoded = AlacPacketBuilder.encodeUncompressedFrame(
                pcm, 0, AlacPacketBuilder.PCM_BYTES_PER_TCP_PACKET);

        assertEquals(AlacPacketBuilder.ALAC_PACKET_OVERHEAD_BYTES + pcm.length, encoded.length);
        assertArrayEquals(
                hex("2000120000200022446688aaccef10"),
                slice(encoded, 0, 15));
        assertArrayEquals(pcm, decodeUncompressedAlac(encoded));
    }

    @Test
    public void aesEncryptionOnlyTouchesFullBlocksAndRtpHeaderIsExact() throws Exception {
        RaopCrypto crypto = new RaopCrypto(
                hex("000102030405060708090a0b0c0d0e0f"),
                hex("101112131415161718191a1b1c1d1e1f"));

        byte[] payload = new byte[39];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }

        byte[] packet = RtpPacketizer.buildTcpPacket(
                0x1234,
                0x01020304L,
                0x11223344L,
                payload.clone(),
                crypto);

        assertArrayEquals(
                hex("2400003380e012340102030411223344"),
                slice(packet, 0, 16));
        assertArrayEquals(
                hex("954f64f2e4e86e9eee82d20216684899a93b9ddb22e8ab104c61e728831d6d5a"),
                slice(packet, 16, 32));
        assertArrayEquals(
                new byte[]{32, 33, 34, 35, 36, 37, 38},
                slice(packet, 48, 7));
    }

    @Test
    public void rsaKeyAndIvUseStandardPaddedBase64() throws Exception {
        RaopCrypto crypto = new RaopCrypto(
                hex("00112233445566778899aabbccddeeff"),
                hex("0f1e2d3c4b5a69788796a5b4c3d2e1f0"));

        String ivBase64 = crypto.aesIvBase64();
        String rsaBase64 = crypto.encryptedKeyBase64(new FixedSecureRandom((byte) 0x5a));

        assertEquals("Dx4tPEtaaXiHlqW0w9Lh8A==", ivBase64);
        assertTrue(rsaBase64.endsWith("=="));
        assertEquals(256, Base64.getDecoder().decode(rsaBase64).length);
        assertTrue(rsaBase64.length() > 300);
    }

    @Test
    public void senderPerformsTcpHandshakeParsesTransportAndAccumulatesPartialPcm() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer()) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    Collections.singletonMap("et", "0".getBytes(StandardCharsets.US_ASCII)),
                    new FixedSecureRandom((byte) 0x11));

            byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
            sender.connect();
            sender.writePcm(pcm, 0, 512);
            assertFalse(server.awaitAudioPacket(150));
            sender.writePcm(pcm, 512, pcm.length - 512);
            sender.close();

            assertTrue(server.awaitFinished());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "FLUSH", "TEARDOWN"),
                    server.methodSequence());
            for (String method : server.methodSequence()) {
                RecordedRequest request = server.request(method);
                assertNull(request.headers.get("DACP-ID"));
                assertNull(request.headers.get("Active-Remote"));
            }

            RecordedRequest announce = server.request("ANNOUNCE");
            assertNotNull(announce);
            assertEquals(Integer.parseInt(announce.headers.get("Content-Length")), announce.body.length);
            String announceBody = new String(announce.body, StandardCharsets.US_ASCII);
            assertTrue(announceBody.contains("a=fmtp:96 4096 0 16 40 10 14 2 255 0 0 44100"));
            assertFalse(announceBody.contains("a=rsaaeskey:"));

            RecordedRequest setup = server.request("SETUP");
            assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1;mode=record", setup.headers.get("Transport"));
            assertEquals("seq=55266;rtptime=16909060", server.request("RECORD").headers.get("RTP-Info"));
            assertEquals("seq=55267;rtptime=16913156", server.request("FLUSH").headers.get("RTP-Info"));

            byte[] audioPacket = server.audioPacket();
            assertNotNull(audioPacket);
            assertEquals(16 + AlacPacketBuilder.ALAC_PACKET_OVERHEAD_BYTES + pcm.length, audioPacket.length);
            assertArrayEquals(
                    hex("2400401480e0d7e20102030401020304"),
                    slice(audioPacket, 0, 16));
            assertArrayEquals(pcm, decodeUncompressedAlac(
                    slice(audioPacket, 16, audioPacket.length - 16)));
        }
    }

    @Test
    public void senderIncludesDacpCredentialsOnEveryRtspRequest() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer()) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    Collections.singletonMap("et", "0".getBytes(StandardCharsets.US_ASCII)),
                    new FixedSecureRandom((byte) 0x11),
                    "0123456789ABCDEF",
                    "4294967295");

            sender.connect();
            sender.close();

            assertTrue(server.awaitFinished());
            for (String method : server.methodSequence()) {
                RecordedRequest request = server.request(method);
                assertEquals("0123456789ABCDEF", request.headers.get("DACP-ID"));
                assertEquals("4294967295", request.headers.get("Active-Remote"));
            }
        }
    }

    @Test
    public void senderRejectsIncompleteOrMalformedDacpCredentials() throws Exception {
        Map<String, byte[]> txt = Collections.singletonMap(
                "et", "0".getBytes(StandardCharsets.US_ASCII));

        assertInvalidDacpCredentials(txt, null, "1");
        assertInvalidDacpCredentials(txt, "0123456789ABCDEF", null);
        assertInvalidDacpCredentials(txt, "not-hexadecimal", "1");
        assertInvalidDacpCredentials(txt, "0123456789ABCDEF", "4294967296");
        assertInvalidDacpCredentials(txt, "0123456789ABCDEF", "\u0661");
    }

    @Test
    public void senderPerformsUdpTimingSyncAndRetransmitRoundTrip() throws Exception {
        try (UdpLoopbackRaopServer server = new UdpLoopbackRaopServer()) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    txt("tp", "UDP", "et", "0", "cn", "1", "sr", "44100", "ss", "16", "ch", "2"),
                    new FixedSecureRandom((byte) 0x11));

            byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_UDP_PACKET);
            sender.connect();
            sender.writePcm(pcm, 0, 256);
            assertFalse(server.awaitUdpRoundTrip(150));
            sender.writePcm(pcm, 256, pcm.length - 256);
            assertTrue(server.awaitUdpRoundTrip(5000));
            sender.close();

            assertTrue(server.awaitFinished());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "FLUSH", "TEARDOWN"),
                    server.methodSequence());

            RecordedRequest announce = server.request("ANNOUNCE");
            assertNotNull(announce);
            assertTrue(new String(announce.body, StandardCharsets.US_ASCII)
                    .contains("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100"));

            RecordedRequest setup = server.request("SETUP");
            assertTrue(setup.headers.get("Transport").startsWith("RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port="));
            assertEquals("seq=55266;rtptime=16909060", server.request("RECORD").headers.get("RTP-Info"));
            assertEquals("seq=55267;rtptime=16909412", server.request("FLUSH").headers.get("RTP-Info"));

            assertArrayEquals(hex("80d30007"), slice(server.initialTimingPacket(), 0, 4));
            assertArrayEquals(slice(server.initialTimingPacket(), 8, 8), slice(server.initialTimingPacket(), 16, 8));
            assertArrayEquals(slice(server.initialTimingPacket(), 16, 8), slice(server.initialTimingPacket(), 24, 8));

            byte[] syncPacket = server.syncPacket();
            assertNotNull(syncPacket);
            assertArrayEquals(hex("90d40007"), slice(syncPacket, 0, 4));
            assertArrayEquals(hex("01020304"), slice(syncPacket, 16, 4));

            byte[] audioPacket = server.audioPacket();
            assertNotNull(audioPacket);
            assertEquals(12 + AlacPacketBuilder.ALAC_PACKET_OVERHEAD_BYTES + pcm.length, audioPacket.length);
            assertArrayEquals(
                    hex("80e0d7e20102030401020304"),
                    slice(audioPacket, 0, 12));
            assertArrayEquals(pcm, decodeUncompressedAlac(
                    slice(audioPacket, 12, audioPacket.length - 12)));

            byte[] timingReply = server.timingReplyPacket();
            assertNotNull(timingReply);
            assertArrayEquals(hex("80d30007"), slice(timingReply, 0, 4));
            assertArrayEquals(hex("3333333344444444"), slice(timingReply, 8, 8));
            assertFalse(allZero(slice(timingReply, 16, 8)));
            assertFalse(allZero(slice(timingReply, 24, 8)));

            byte[] retransmitPacket = server.retransmitPacket();
            assertNotNull(retransmitPacket);
            assertArrayEquals(hex("80d6d7e2"), slice(retransmitPacket, 0, 4));
            assertArrayEquals(audioPacket, slice(retransmitPacket, 4, audioPacket.length));
        }
    }

    @Test
    public void mdTxtFieldControlsTextArtworkAndProgressSupport() {
        RaopStreamingClient advertised = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("md", "0,1,2", "et", "0"));
        assertEquals(MetadataSupport.ADVERTISED, advertised.textMetadataSupport());
        assertEquals(MetadataSupport.ADVERTISED, advertised.artworkSupport());
        assertEquals(MetadataSupport.ADVERTISED, advertised.progressSupport());

        RaopStreamingClient textAndProgressOnly = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("md", "0,2", "et", "0"));
        assertEquals(MetadataSupport.ADVERTISED, textAndProgressOnly.textMetadataSupport());
        assertEquals(MetadataSupport.NOT_ADVERTISED, textAndProgressOnly.artworkSupport());
        assertEquals(MetadataSupport.ADVERTISED, textAndProgressOnly.progressSupport());

        RaopStreamingClient unknown = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("et", "0"));
        assertEquals(MetadataSupport.UNKNOWN, unknown.textMetadataSupport());
        assertEquals(MetadataSupport.UNKNOWN, unknown.artworkSupport());
        assertEquals(MetadataSupport.UNKNOWN, unknown.progressSupport());

        RaopStreamingClient noText = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("md", "1,2", "et", "0"));
        assertEquals(MetadataSupport.NOT_ADVERTISED, noText.textMetadataSupport());
        assertEquals(MetadataSupport.ADVERTISED, noText.artworkSupport());
        assertEquals(MetadataSupport.ADVERTISED, noText.progressSupport());

        RaopStreamingClient uppercaseKey = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("MD", "0", "et", "0"));
        assertEquals(MetadataSupport.ADVERTISED, uppercaseKey.textMetadataSupport());
        assertEquals(MetadataSupport.NOT_ADVERTISED, uppercaseKey.progressSupport());
    }

    @Test
    public void setParameterSendsDmapTextWithSessionRtpInfoAndClientInstance() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(200, 200, 200, 200, 200, 200, 200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    txt("md", "0", "et", "0"),
                    new FixedSecureRandom((byte) 0x11));

            byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
            sender.connect();
            sender.writePcm(pcm, 0, pcm.length);
            sender.sendTrackMetadata("歌", "Artist", "Album");
            sender.close();

            assertTrue(server.awaitFinished());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "SET_PARAMETER", "FLUSH", "TEARDOWN"),
                    server.methodSequence());

            RecordedRequest setParameter = server.request("SET_PARAMETER");
            assertNotNull(setParameter);
            assertEquals("123456", setParameter.headers.get("Session"));
            assertEquals("application/x-dmap-tagged", setParameter.headers.get("Content-Type"));
            // One full 4096-frame packet advanced the stream: 16909060 + 4096 = 16913156.
            assertEquals("rtptime=16913156", setParameter.headers.get("RTP-Info"));
            assertEquals("11111111111111111111111111111111", setParameter.headers.get("Client-Instance"));
            assertEquals(Integer.parseInt(setParameter.headers.get("Content-Length")), setParameter.body.length);
            assertArrayEquals(DmapEncoder.encodeTextMetadata("歌", "Artist", "Album"), setParameter.body);

            byte[] audioPacket = server.audioPacket();
            assertNotNull(audioPacket);
            assertArrayEquals(pcm, decodeUncompressedAlac(
                    slice(audioPacket, 16, audioPacket.length - 16)));
        }
    }

    @Test
    public void failedMetadataSendDoesNotAbortAudioSession() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(
                true, 200, 200, 200, 200, 500, 200, 200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    txt("md", "0", "et", "0"),
                    new FixedSecureRandom((byte) 0x11));

            byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
            sender.connect();
            sender.writePcm(pcm, 0, pcm.length);
            IOException metadataFailure = expectThrows(
                    IOException.class,
                    () -> sender.sendTrackMetadata("Title", "Artist", "Album"));
            assertTrue(metadataFailure.getMessage().contains("500"));
            sender.close();

            assertTrue(server.awaitFinished());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "SET_PARAMETER", "FLUSH", "TEARDOWN"),
                    server.methodSequence());

            byte[] audioPacket = server.audioPacket();
            assertNotNull(audioPacket);
            assertArrayEquals(pcm, decodeUncompressedAlac(
                    slice(audioPacket, 16, audioPacket.length - 16)));
        }
    }

    @Test
    public void sendTrackMetadataRequiresAConnectedSession() {
        RaopStreamingClient sender = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("md", "0", "et", "0"));

        IllegalStateException exception = expectThrows(
                IllegalStateException.class,
                () -> sender.sendTrackMetadata("Title", "Artist", "Album"));
        assertTrue(exception.getMessage().contains("not connected"));
    }

    @Test
    public void setParameterArtworkSendsJpegBodyWithRtpInfo() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(200, 200, 200, 200, 200, 200, 200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    txt("md", "0,1", "et", "0"),
                    new FixedSecureRandom((byte) 0x11));

            byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
            byte[] jpeg = hex("ffd8ffe000104a46494600e941c3b5ffd9");
            sender.connect();
            sender.writePcm(pcm, 0, pcm.length);
            sender.sendTrackArtwork(jpeg);
            sender.close();

            assertTrue(server.awaitFinished());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "SET_PARAMETER", "FLUSH", "TEARDOWN"),
                    server.methodSequence());

            RecordedRequest setParameter = server.request("SET_PARAMETER");
            assertNotNull(setParameter);
            assertEquals("123456", setParameter.headers.get("Session"));
            assertEquals("image/jpeg", setParameter.headers.get("Content-Type"));
            assertEquals("rtptime=16913156", setParameter.headers.get("RTP-Info"));
            assertEquals("11111111111111111111111111111111", setParameter.headers.get("Client-Instance"));
            assertEquals(Integer.parseInt(setParameter.headers.get("Content-Length")), setParameter.body.length);
            assertArrayEquals(jpeg, setParameter.body);

            byte[] audioPacket = server.audioPacket();
            assertNotNull(audioPacket);
            assertArrayEquals(pcm, decodeUncompressedAlac(
                    slice(audioPacket, 16, audioPacket.length - 16)));
        }
    }

    @Test
    public void setParameterProgressSendsTextParametersBodyWithoutRtpInfo() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(200, 200, 200, 200, 200, 200, 200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    txt("md", "0,1,2", "et", "0"),
                    new FixedSecureRandom((byte) 0x11));

            byte[] pcm = samplePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
            sender.connect();
            sender.writePcm(pcm, 0, pcm.length);
            sender.sendProgress(4_293_321_296L, 1_000_000, 6_292_000);
            sender.close();

            assertTrue(server.awaitFinished());
            RecordedRequest setParameter = server.request("SET_PARAMETER");
            assertNotNull(setParameter);
            assertEquals("123456", setParameter.headers.get("Session"));
            assertEquals("text/parameters", setParameter.headers.get("Content-Type"));
            assertEquals("11111111111111111111111111111111", setParameter.headers.get("Client-Instance"));
            assertNull(setParameter.headers.get("RTP-Info"));
            assertEquals(
                    "progress: 4293321296/1000000/6292000\r\n",
                    new String(setParameter.body, StandardCharsets.US_ASCII));

            byte[] audioPacket = server.audioPacket();
            assertNotNull(audioPacket);
            assertArrayEquals(pcm, decodeUncompressedAlac(
                    slice(audioPacket, 16, audioPacket.length - 16)));
        }
    }

    @Test
    public void flushAudioSendsFlushWithoutTeardownKeepsTimestampAndDropsPartialPcm() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(
                true, 200, 200, 200, 200, 200, 200, 200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    txt("md", "0,1,2", "et", "0"),
                    new FixedSecureRandom((byte) 0x11));

            byte[] prePauseTail = repeatedPcmByte(0x11, 512);
            byte[] resumedPacket = repeatedPcmByte(0x66, AlacPacketBuilder.PCM_BYTES_PER_TCP_PACKET);

            sender.connect();
            sender.writePcm(prePauseTail, 0, prePauseTail.length);
            long pausedTimestamp = sender.currentRtpTimestamp();

            sender.flushAudio();

            assertEquals(pausedTimestamp, sender.currentRtpTimestamp());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "FLUSH"),
                    server.methodSequence());
            assertEquals("seq=55266;rtptime=16909060", server.request("FLUSH", 0).headers.get("RTP-Info"));
            assertFalse(server.awaitAudioPacketCount(1, 150));

            sender.writePcm(resumedPacket, 0, resumedPacket.length);
            assertEquals(pausedTimestamp + AlacPacketBuilder.FRAMES_PER_TCP_PACKET, sender.currentRtpTimestamp());
            sender.close();

            assertTrue(server.awaitFinished());
            assertEquals(
                    List.of("OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "FLUSH", "FLUSH", "TEARDOWN"),
                    server.methodSequence());
            assertEquals("seq=55267;rtptime=16913156", server.request("FLUSH", 1).headers.get("RTP-Info"));
            assertEquals(1, server.audioPackets().size());

            byte[] audioPacket = server.audioPackets().get(0);
            assertArrayEquals(resumedPacket, decodeUncompressedAlac(
                    slice(audioPacket, 16, audioPacket.length - 16)));
        }
    }

    @Test
    public void connectMaps401And403ToClearExceptions() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(401)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    Collections.emptyMap());
            try {
                sender.connect();
                fail("Expected authentication exception");
            } catch (RaopException.AuthenticationException expected) {
                assertTrue(expected.getMessage().contains("401"));
            }
        }

        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(403)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    Collections.emptyMap());
            try {
                sender.connect();
                fail("Expected pairing exception");
            } catch (RaopException.PairingException expected) {
                assertTrue(expected.getMessage().contains("403"));
            }
        }
    }

    @Test
    public void fairPlayOnlyTxtIsRejectedBeforeNetworking() throws Exception {
        RaopStreamingClient sender = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                Collections.singletonMap("et", "4".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(RaopCompatibility.UNSUPPORTED_AIRPLAY2_ONLY, sender.compatibility());
        assertTrue(sender.incompatibilityReason().contains("AirPlay 2"));

        try {
            sender.connect();
            fail("Expected unsupported receiver exception");
        } catch (RaopException.UnsupportedReceiverException expected) {
            assertEquals(RaopCompatibility.UNSUPPORTED_AIRPLAY2_ONLY, expected.compatibility());
        }
    }

    @Test
    public void txtCapabilityAndReasonChecksArePrecise() {
        RaopStreamingClient unsupportedTransport = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("tp", "HTTP", "et", "0"));
        assertEquals(RaopCompatibility.UNSUPPORTED_LEGACY_CAPABILITIES, unsupportedTransport.compatibility());
        assertTrue(unsupportedTransport.incompatibilityReason().contains("tp"));

        RaopStreamingClient unsupportedRate = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("sr", "48000", "et", "0"));
        assertEquals(RaopCompatibility.UNSUPPORTED_LEGACY_CAPABILITIES, unsupportedRate.compatibility());
        assertTrue(unsupportedRate.incompatibilityReason().contains("sr"));

        RaopStreamingClient supportedUdp = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                txt("TP", "udp", "ET", "0", "CN", "1", "SR", "44100", "SS", "16", "CH", "2"));
        assertEquals(RaopCompatibility.LEGACY_CLEAR, supportedUdp.compatibility());
        assertNull(supportedUdp.incompatibilityReason());
    }

    @Test
    public void txtEncryptionSelectionPrefersClearAndRejectsUnsupportedModes() {
        RaopStreamingClient clearPreferred = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                Collections.singletonMap("et", "0,1,4".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(RaopCompatibility.LEGACY_CLEAR, clearPreferred.compatibility());

        RaopStreamingClient rsaOnly = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                Collections.singletonMap("et", "1,4".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(RaopCompatibility.LEGACY_RSA, rsaOnly.compatibility());

        RaopStreamingClient unsupported = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                Collections.singletonMap("et", "2,3,5".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(RaopCompatibility.UNSUPPORTED_ENCRYPTION, unsupported.compatibility());
        assertTrue(unsupported.incompatibilityReason().contains("et"));
    }

    @Test
    public void connectRejectsMalformedSetupPortsAndSession() throws Exception {
        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(
                "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record;server_port=0",
                ";timeout=60",
                false,
                200,
                200,
                200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    Collections.singletonMap("et", "0".getBytes(StandardCharsets.US_ASCII)),
                    new FixedSecureRandom((byte) 0x11));
            IOException exception = expectThrows(IOException.class, sender::connect);
            assertTrue(exception.getMessage().contains("empty Session header"));
        }

        try (TcpLoopbackRaopServer server = new TcpLoopbackRaopServer(
                "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record;server_port=0",
                "123456;timeout=60",
                false,
                200,
                200,
                200)) {
            RaopStreamingClient sender = new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    server.controlPort(),
                    Collections.singletonMap("et", "0".getBytes(StandardCharsets.US_ASCII)),
                    new FixedSecureRandom((byte) 0x11));
            IOException exception = expectThrows(IOException.class, sender::connect);
            assertTrue(exception.getMessage().contains("Invalid RTSP server_port"));
        }
    }

    @Test
    public void writePcmRejectsOverflowingOffsetAndLength() {
        RaopStreamingClient sender = new RaopStreamingClient(
                InetAddress.getLoopbackAddress(),
                7000,
                Collections.emptyMap());

        IndexOutOfBoundsException exception = expectThrows(
                IndexOutOfBoundsException.class,
                () -> sender.writePcm(new byte[4], Integer.MAX_VALUE, 1));
        assertTrue(exception.getMessage().contains("offset=2147483647"));
    }

    private static byte[] samplePcm(int frames) {
        byte[] pcm = new byte[frames * AlacPacketBuilder.PCM_BYTES_PER_FRAME];
        for (int offset = 0; offset < pcm.length; offset += 8) {
            pcm[offset] = 0x22;
            pcm[offset + 1] = 0x11;
            pcm[offset + 2] = 0x44;
            pcm[offset + 3] = 0x33;
            pcm[offset + 4] = 0x66;
            pcm[offset + 5] = 0x55;
            pcm[offset + 6] = (byte) 0x88;
            pcm[offset + 7] = 0x77;
        }
        return pcm;
    }

    private static byte[] repeatedPcmByte(int value, int length) {
        byte[] pcm = new byte[length];
        Arrays.fill(pcm, (byte) value);
        return pcm;
    }

    private static byte[] decodeUncompressedAlac(byte[] packet) {
        int[] bitOffset = {0};
        assertEquals(1, readBits(packet, bitOffset, 3));
        assertEquals(0, readBits(packet, bitOffset, 4));
        assertEquals(0, readBits(packet, bitOffset, 8));
        assertEquals(0, readBits(packet, bitOffset, 4));
        assertEquals(1, readBits(packet, bitOffset, 1));
        assertEquals(0, readBits(packet, bitOffset, 2));
        assertEquals(1, readBits(packet, bitOffset, 1));
        int frames = readBits(packet, bitOffset, 32);

        byte[] pcm = new byte[frames * AlacPacketBuilder.PCM_BYTES_PER_FRAME];
        for (int frame = 0; frame < frames; frame++) {
            int left = readBits(packet, bitOffset, 16);
            int right = readBits(packet, bitOffset, 16);
            int offset = frame * AlacPacketBuilder.PCM_BYTES_PER_FRAME;
            pcm[offset] = (byte) (left & 0xff);
            pcm[offset + 1] = (byte) ((left >>> 8) & 0xff);
            pcm[offset + 2] = (byte) (right & 0xff);
            pcm[offset + 3] = (byte) ((right >>> 8) & 0xff);
        }
        assertEquals(7, readBits(packet, bitOffset, 3));
        return pcm;
    }

    private static int readBits(byte[] packet, int[] bitOffset, int count) {
        int value = 0;
        for (int index = 0; index < count; index++) {
            int absoluteBit = bitOffset[0]++;
            int bit = (packet[absoluteBit / 8] >>> (7 - (absoluteBit % 8))) & 1;
            value = (value << 1) | bit;
        }
        return value;
    }

    private static byte[] slice(byte[] value, int offset, int length) {
        byte[] slice = new byte[length];
        System.arraycopy(value, offset, slice, 0, length);
        return slice;
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int start = index * 2;
            bytes[index] = (byte) Integer.parseInt(value.substring(start, start + 2), 16);
        }
        return bytes;
    }

    private static Map<String, byte[]> txt(String... entries) {
        Map<String, byte[]> txt = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            txt.put(entries[index], entries[index + 1].getBytes(StandardCharsets.US_ASCII));
        }
        return txt;
    }

    private static <T extends Throwable> T expectThrows(Class<T> type, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError("Expected " + type.getName() + " but caught " + throwable, throwable);
        }
        throw new AssertionError("Expected " + type.getName() + " to be thrown");
    }

    private static final class FixedSecureRandom extends SecureRandom {
        private final byte value;

        private FixedSecureRandom(byte value) {
            this.value = value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, value);
        }

        @Override
        public int nextInt() {
            return 0x01020304;
        }

        @Override
        public int nextInt(int bound) {
            if (bound == 0x10000) {
                return 0xd7e2;
            }
            return Math.floorMod(nextInt(), bound);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class TcpLoopbackRaopServer implements AutoCloseable {
        private final ServerSocket controlServer;
        private final ServerSocket audioServer;
        private final Thread controlThread;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final int[] statuses;
        private final String setupTransportHeader;
        private final String setupSessionHeader;
        private final boolean acceptAudioConnection;
        private final boolean keepReadingAfterError;
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final List<byte[]> audioPackets = Collections.synchronizedList(new ArrayList<>());
        private volatile Throwable failure;

        private TcpLoopbackRaopServer(int... statuses) throws IOException {
            this(
                    "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record;server_port=",
                    "123456;timeout=60",
                    true,
                    false,
                    normalizeStatuses(statuses));
        }

        private TcpLoopbackRaopServer(boolean keepReadingAfterError, int... statuses) throws IOException {
            this(
                    "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record;server_port=",
                    "123456;timeout=60",
                    true,
                    keepReadingAfterError,
                    normalizeStatuses(statuses));
        }

        private TcpLoopbackRaopServer(
                String setupTransportHeader,
                String setupSessionHeader,
                boolean acceptAudioConnection,
                int... statuses) throws IOException {
            this(setupTransportHeader, setupSessionHeader, acceptAudioConnection, false, statuses);
        }

        private TcpLoopbackRaopServer(
                String setupTransportHeader,
                String setupSessionHeader,
                boolean acceptAudioConnection,
                boolean keepReadingAfterError,
                int... statuses) throws IOException {
            this.statuses = normalizeStatuses(statuses);
            this.setupTransportHeader = setupTransportHeader;
            this.setupSessionHeader = setupSessionHeader;
            this.acceptAudioConnection = acceptAudioConnection;
            this.keepReadingAfterError = keepReadingAfterError;
            this.controlServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.audioServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.audioServer.setSoTimeout(1500);
            this.controlThread = new Thread(this::runServer, "raop-test-tcp-server");
            this.controlThread.start();
        }

        int controlPort() {
            return controlServer.getLocalPort();
        }

        List<String> methodSequence() {
            synchronized (requests) {
                List<String> methods = new ArrayList<>(requests.size());
                for (RecordedRequest request : requests) {
                    methods.add(request.method);
                }
                return methods;
            }
        }

        RecordedRequest request(String method) {
            synchronized (requests) {
                for (RecordedRequest request : requests) {
                    if (request.method.equals(method)) {
                        return request;
                    }
                }
            }
            return null;
        }

        RecordedRequest request(String method, int occurrence) {
            int seen = 0;
            synchronized (requests) {
                for (RecordedRequest request : requests) {
                    if (request.method.equals(method)) {
                        if (seen == occurrence) {
                            return request;
                        }
                        seen++;
                    }
                }
            }
            return null;
        }

        byte[] audioPacket() {
            synchronized (audioPackets) {
                return audioPackets.isEmpty() ? null : audioPackets.get(0);
            }
        }

        List<byte[]> audioPackets() {
            synchronized (audioPackets) {
                return new ArrayList<>(audioPackets);
            }
        }

        boolean awaitAudioPacket(long timeoutMs) throws InterruptedException {
            return awaitAudioPacketCount(1, timeoutMs);
        }

        boolean awaitAudioPacketCount(int count, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                synchronized (audioPackets) {
                    if (audioPackets.size() >= count) {
                        return true;
                    }
                }
                Thread.sleep(10);
            }
            synchronized (audioPackets) {
                return audioPackets.size() >= count;
            }
        }

        boolean awaitFinished() throws InterruptedException {
            boolean done = finished.await(5, TimeUnit.SECONDS);
            if (failure != null) {
                throw new AssertionError(failure);
            }
            return done;
        }

        private void runServer() {
            try (Socket controlSocket = controlServer.accept()) {
                controlSocket.setSoTimeout(5000);
                InputStream controlIn = controlSocket.getInputStream();
                OutputStream controlOut = controlSocket.getOutputStream();
                Socket acceptedAudio = null;
                for (int index = 0; index < statuses.length; index++) {
                    RecordedRequest request = readRequest(controlIn);
                    requests.add(request);
                    writeResponse(controlOut, request.method, statuses[index]);
                    if (statuses[index] != 200 && !keepReadingAfterError) {
                        break;
                    }
                    if ("SETUP".equals(request.method) && acceptAudioConnection) {
                        acceptedAudio = audioServer.accept();
                    }
                }

                if (acceptedAudio != null) {
                    try (Socket audioSocket = acceptedAudio) {
                        audioSocket.setSoTimeout(2000);
                        collectAudioPackets(audioSocket.getInputStream());
                    }
                }
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                finished.countDown();
            }
        }

        private void collectAudioPackets(InputStream input) throws IOException {
            while (true) {
                try {
                    byte[] packet = readSingleTcpAudioPacket(input);
                    synchronized (audioPackets) {
                        audioPackets.add(packet);
                    }
                } catch (SocketTimeoutException | EOFException endOfStream) {
                    return;
                }
            }
        }

        private void writeResponse(OutputStream output, String method, int status) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("RTSP/1.0 ").append(status).append(' ')
                    .append(status == 200 ? "OK" : "ERR").append("\r\n");
            if (status == 200 && "SETUP".equals(method)) {
                response.append("Transport: ").append(setupTransportHeader);
                if (setupTransportHeader.endsWith("server_port=")) {
                    response.append(audioServer.getLocalPort());
                }
                response.append("\r\n");
                response.append("Session: ").append(setupSessionHeader).append("\r\n");
            }
            response.append("\r\n");
            output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            controlServer.close();
            audioServer.close();
            controlThread.join(5000);
            if (failure != null) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class UdpLoopbackRaopServer implements AutoCloseable {
        private final ServerSocket controlServer;
        private final DatagramSocket audioSocket;
        private final DatagramSocket controlSocket;
        private final DatagramSocket timingSocket;
        private final Thread controlThread;
        private final CountDownLatch udpRoundTrip = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private volatile int clientControlPort;
        private volatile int clientTimingPort;
        private volatile byte[] initialTimingPacket;
        private volatile byte[] syncPacket;
        private volatile byte[] audioPacket;
        private volatile byte[] timingReplyPacket;
        private volatile byte[] retransmitPacket;
        private volatile Throwable failure;

        private UdpLoopbackRaopServer() throws IOException {
            this.controlServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            this.audioSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.controlSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.timingSocket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            this.audioSocket.setSoTimeout(5000);
            this.controlSocket.setSoTimeout(5000);
            this.timingSocket.setSoTimeout(5000);
            this.controlThread = new Thread(this::runServer, "raop-test-udp-server");
            this.controlThread.start();
        }

        int controlPort() {
            return controlServer.getLocalPort();
        }

        List<String> methodSequence() {
            synchronized (requests) {
                List<String> methods = new ArrayList<>(requests.size());
                for (RecordedRequest request : requests) {
                    methods.add(request.method);
                }
                return methods;
            }
        }

        RecordedRequest request(String method) {
            synchronized (requests) {
                for (RecordedRequest request : requests) {
                    if (request.method.equals(method)) {
                        return request;
                    }
                }
            }
            return null;
        }

        byte[] initialTimingPacket() {
            return initialTimingPacket;
        }

        byte[] syncPacket() {
            return syncPacket;
        }

        byte[] audioPacket() {
            return audioPacket;
        }

        byte[] timingReplyPacket() {
            return timingReplyPacket;
        }

        byte[] retransmitPacket() {
            return retransmitPacket;
        }

        boolean awaitUdpRoundTrip(long timeoutMs) throws InterruptedException {
            return udpRoundTrip.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        boolean awaitFinished() throws InterruptedException {
            boolean done = finished.await(5, TimeUnit.SECONDS);
            if (failure != null) {
                throw new AssertionError(failure);
            }
            return done;
        }

        private void runServer() {
            try (Socket control = controlServer.accept()) {
                control.setSoTimeout(5000);
                InputStream controlIn = control.getInputStream();
                OutputStream controlOut = control.getOutputStream();
                for (int index = 0; index < 6; index++) {
                    RecordedRequest request = readRequest(controlIn);
                    requests.add(request);
                    if ("SETUP".equals(request.method)) {
                        clientControlPort = parsePortFromTransport(request.headers.get("Transport"), "control_port");
                        clientTimingPort = parsePortFromTransport(request.headers.get("Transport"), "timing_port");
                    }
                    writeUdpResponse(controlOut, request.method);
                    if ("RECORD".equals(request.method)) {
                        collectUdpRoundTrip();
                        udpRoundTrip.countDown();
                    }
                }
            } catch (Throwable throwable) {
                failure = throwable;
                udpRoundTrip.countDown();
            } finally {
                finished.countDown();
            }
        }

        private void collectUdpRoundTrip() throws IOException {
            initialTimingPacket = receiveDatagram(timingSocket);
            syncPacket = receiveDatagram(controlSocket);
            audioPacket = receiveDatagram(audioSocket);

            byte[] timingRequest = buildTimingRequest();
            timingSocket.send(new DatagramPacket(
                    timingRequest,
                    timingRequest.length,
                    InetAddress.getLoopbackAddress(),
                    clientTimingPort));
            timingReplyPacket = receiveDatagram(timingSocket);

            byte[] retransmitRequest = {
                    (byte) 0x80, (byte) 0xd5, 0x00, 0x00, (byte) 0xd7, (byte) 0xe2, 0x00, 0x01
            };
            controlSocket.send(new DatagramPacket(
                    retransmitRequest,
                    retransmitRequest.length,
                    InetAddress.getLoopbackAddress(),
                    clientControlPort));
            retransmitPacket = receiveDatagram(controlSocket);
        }

        private void writeUdpResponse(OutputStream output, String method) throws IOException {
            StringBuilder response = new StringBuilder();
            response.append("RTSP/1.0 200 OK\r\n");
            if ("SETUP".equals(method)) {
                response.append("Transport: RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;server_port=")
                        .append(audioSocket.getLocalPort())
                        .append(";control_port=").append(controlSocket.getLocalPort())
                        .append(";timing_port=").append(timingSocket.getLocalPort())
                        .append("\r\n");
                response.append("Session: 123456;timeout=60\r\n");
            }
            response.append("\r\n");
            output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override
        public void close() throws Exception {
            controlServer.close();
            audioSocket.close();
            controlSocket.close();
            timingSocket.close();
            controlThread.join(5000);
            if (failure != null) {
                throw new AssertionError(failure);
            }
        }
    }

    private static int[] normalizeStatuses(int... statuses) {
        if (statuses == null || statuses.length == 0) {
            return new int[]{200, 200, 200, 200, 200, 200};
        }
        if (statuses.length == 1) {
            return new int[]{statuses[0], 200, 200, 200, 200, 200};
        }
        return statuses;
    }

    private static void assertInvalidDacpCredentials(
            Map<String, byte[]> txt,
            String dacpId,
            String activeRemote) throws Exception {
        try {
            new RaopStreamingClient(
                    InetAddress.getLoopbackAddress(),
                    5000,
                    txt,
                    new FixedSecureRandom((byte) 0x11),
                    dacpId,
                    activeRemote);
            fail("Expected invalid DACP credentials to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("DACP")
                    || expected.getMessage().contains("Active-Remote"));
        }
    }

    private static RecordedRequest readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null) {
            throw new EOFException("Unexpected EOF while reading RTSP request line");
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 3) {
            throw new IOException("Malformed RTSP request: " + requestLine);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new EOFException("Unexpected EOF while reading RTSP headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            headers.put(line.substring(0, separator), line.substring(separator + 2));
        }

        int contentLength = headers.containsKey("Content-Length")
                ? Integer.parseInt(headers.get("Content-Length"))
                : 0;
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = input.read(body, offset, contentLength - offset);
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading RTSP body");
            }
            offset += read;
        }
        return new RecordedRequest(parts[0], parts[1], headers, body);
    }

    private static byte[] readSingleTcpAudioPacket(InputStream input) throws IOException {
        byte[] header = input.readNBytes(4);
        if (header.length < 4) {
            throw new EOFException("Missing TCP interleaved header");
        }
        int size = ((header[2] & 0xff) << 8) | (header[3] & 0xff);
        byte[] payload = input.readNBytes(size);
        if (payload.length < size) {
            throw new EOFException("Short audio packet");
        }
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(header);
        packet.write(payload);
        return packet.toByteArray();
    }

    private static byte[] receiveDatagram(DatagramSocket socket) throws IOException {
        byte[] buffer = new byte[2048];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        socket.receive(datagram);
        return slice(datagram.getData(), 0, datagram.getLength());
    }

    private static byte[] buildTimingRequest() {
        byte[] packet = new byte[32];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) 0xd2;
        packet[2] = 0x00;
        packet[3] = 0x07;
        System.arraycopy(hex("1111111122222222"), 0, packet, 8, 8);
        System.arraycopy(hex("aaaaaaaa55555555"), 0, packet, 16, 8);
        System.arraycopy(hex("3333333344444444"), 0, packet, 24, 8);
        return packet;
    }

    private static int parsePortFromTransport(String transport, String key) {
        String prefix = key + "=";
        for (String part : transport.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return Integer.parseInt(trimmed.substring(prefix.length()));
            }
        }
        throw new AssertionError("Missing " + key + " in " + transport);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new EOFException("Unexpected EOF inside RTSP line");
            }
            if (value == '\r') {
                int next = input.read();
                if (next != '\n') {
                    throw new IOException("Malformed RTSP line ending");
                }
                return buffer.toString(StandardCharsets.US_ASCII);
            }
            buffer.write(value);
        }
    }

    private static final class RecordedRequest {
        final String method;
        final String uri;
        final Map<String, String> headers;
        final byte[] body;

        private RecordedRequest(String method, String uri, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
        }
    }
}
