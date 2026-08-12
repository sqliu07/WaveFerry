package app.shengdu.audio.dlna;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class DlnaLocalAudioServerTest {
    @Test
    public void bracketsIpv6AddressInAdvertisedAudioUri() throws Exception {
        InetAddress address = Inet6Address.getByAddress(
                null,
                new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1},
                -1);

        URI uri = DlnaLocalAudioServer.buildAudioUri(address, 49152, "test-token");

        assertEquals("http://[2001:db8:0:0:0:0:0:1]:49152/waveferry/test-token.wav",
                uri.toString());
        assertEquals(49152, uri.getPort());
    }

    @Test
    public void answersRendererHeadProbeWithDlnaHeaders() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            OutputStream output = socket.getOutputStream();
            output.write(("HEAD " + uri.getRawPath() + " HTTP/1.1\r\nHost: "
                    + uri.getHost() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String headers = readHeaders(socket.getInputStream());
            assertTrue(headers.startsWith("HTTP/1.1 200 OK"));
            assertTrue(headers.contains("Content-Length: " + Integer.MAX_VALUE));
            assertTrue(headers.contains("Accept-Ranges: bytes"));
            assertTrue(headers.contains("contentFeatures.dlna.org: DLNA.ORG_OP=01"));
        } finally {
            server.close();
        }
    }

    @Test
    public void sendsSilenceBeforeCapturedPcmArrives() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(1_000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + uri.getRawPath() + " HTTP/1.1\r\nHost: "
                    + uri.getHost() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            InputStream input = socket.getInputStream();
            readHeaders(input);
            byte[] wavAndSilence = input.readNBytes(48);
            assertEquals(48, wavAndSilence.length);
            assertEquals('R', wavAndSilence[0]);
            assertEquals(0, wavAndSilence[44]);
            assertEquals(0, wavAndSilence[47]);
        } finally {
            server.close();
        }
    }

    @Test
    public void servesArtworkWhileTheLiveAudioConnectionIsOpen() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        URI audioUri = server.start();
        server.updateArtwork(jpeg);
        URI artworkUri = server.artworkUri(java.util.Arrays.hashCode(jpeg));

        try (Socket audioSocket = new Socket(audioUri.getHost(), audioUri.getPort())) {
            audioSocket.setSoTimeout(1_000);
            writeRequest(audioSocket, "GET", audioUri);
            readHeaders(audioSocket.getInputStream());
            assertEquals(44, audioSocket.getInputStream().readNBytes(44).length);

            try (Socket artworkSocket = new Socket(artworkUri.getHost(), artworkUri.getPort())) {
                artworkSocket.setSoTimeout(1_000);
                writeRequest(artworkSocket, "GET", artworkUri);
                InputStream input = artworkSocket.getInputStream();
                String headers = readHeaders(input);
                assertTrue(headers.startsWith("HTTP/1.1 200 OK"));
                assertTrue(headers.contains("Content-Type: image/jpeg"));
                assertTrue(headers.contains("DLNA.ORG_PN=JPEG_SM"));
                assertArrayEquals(jpeg, input.readNBytes(jpeg.length));
            }
        } finally {
            server.close();
        }
    }

    @Test
    public void acceptsNonZeroAudioRangeForRendererPositionSync() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();
        byte[] currentPcm = new byte[]{1, 2, 3, 4};
        server.writePcm(currentPcm, 0, currentPcm.length);

        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(1_000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + uri.getRawPath() + " HTTP/1.1\r\nHost: "
                    + uri.getHost() + "\r\nRange: bytes=176444-\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();

            InputStream input = socket.getInputStream();
            String headers = readHeaders(input);
            assertTrue(headers.startsWith("HTTP/1.1 206 Partial Content"));
            assertTrue(headers.contains("Content-Range: bytes 176444-"));
            assertArrayEquals(currentPcm, input.readNBytes(currentPcm.length));
        } finally {
            server.close();
        }
    }

    private static void writeRequest(Socket socket, String method, URI uri) throws Exception {
        String target = uri.getRawPath()
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        OutputStream output = socket.getOutputStream();
        output.write((method + " " + target + " HTTP/1.1\r\nHost: "
                + uri.getHost() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matched = 0;
        int next;
        while ((next = input.read()) >= 0) {
            output.write(next);
            int expected = matched == 0 || matched == 2 ? '\r' : '\n';
            matched = next == expected ? matched + 1 : 0;
            if (matched == 4) {
                return output.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new AssertionError("HTTP headers were not completed");
    }
}
