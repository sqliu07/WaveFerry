package app.shengdu.audio.dlna;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        URI artworkUri = server.artworkUri("track-1".hashCode());

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
                assertTrue(headers.contains("Cache-Control: no-cache, no-store, must-revalidate"));
                assertTrue(headers.contains("DLNA.ORG_PN=JPEG_SM"));
                assertArrayEquals(jpeg, input.readNBytes(jpeg.length));
            }
        } finally {
            server.close();
        }
    }

    @Test
    public void waitsBrieflyForArtworkThatArrivesAfterTheRendererRequest() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        server.start();
        URI artworkUri = server.artworkUri("track-late-artwork".hashCode());

        try (Socket artworkSocket = new Socket(artworkUri.getHost(), artworkUri.getPort())) {
            artworkSocket.setSoTimeout(1_000);
            writeRequest(artworkSocket, "GET", artworkUri);
            Thread.sleep(50L);
            byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, 4, 5, (byte) 0xff, (byte) 0xd9};
            server.updateArtwork(jpeg);

            InputStream input = artworkSocket.getInputStream();
            String headers = readHeaders(input);
            assertTrue(headers.startsWith("HTTP/1.1 200 OK"));
            assertArrayEquals(jpeg, input.readNBytes(jpeg.length));
        } finally {
            server.close();
        }
    }

    @Test
    public void missingArtworkResponseIsNotCachedBeforeTheSameUriBecomesAvailable() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        server.start();
        URI artworkUri = server.artworkUri("track-artwork-timeout".hashCode());

        try {
            try (Socket firstRequest = new Socket(artworkUri.getHost(), artworkUri.getPort())) {
                firstRequest.setSoTimeout(1_000);
                writeRequest(firstRequest, "GET", artworkUri);
                String headers = readHeaders(firstRequest.getInputStream());
                assertTrue(headers.startsWith("HTTP/1.1 404 Not Found"));
                assertTrue(headers.contains("Cache-Control: no-cache, no-store, must-revalidate"));
            }

            byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, 6, 7, (byte) 0xff, (byte) 0xd9};
            server.updateArtwork(jpeg);
            try (Socket retry = new Socket(artworkUri.getHost(), artworkUri.getPort())) {
                retry.setSoTimeout(1_000);
                writeRequest(retry, "GET", artworkUri);
                InputStream input = retry.getInputStream();
                assertTrue(readHeaders(input).startsWith("HTTP/1.1 200 OK"));
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

    @Test
    public void dropsOldestBufferedPcmInsteadOfAccumulatingPlaybackLag() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();
        server.writePcm(new byte[]{1, 1, 1, 1}, 0, 4);
        server.writePcm(new byte[]{2, 2, 2, 2}, 0, 4);
        server.writePcm(new byte[]{3, 3, 3, 3}, 0, 4);
        server.writePcm(new byte[]{4, 4, 4, 4}, 0, 4);

        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(1_000);
            writeRequest(socket, "GET", uri);
            InputStream input = socket.getInputStream();
            readHeaders(input);
            input.readNBytes(44);

            assertArrayEquals(
                    new byte[]{2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4},
                    input.readNBytes(12));
        } finally {
            server.close();
        }
    }

    @Test
    public void rejectsARequestWithTooManyHeaders() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();

        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(1_000);
            StringBuilder request = new StringBuilder("GET ")
                    .append(uri.getRawPath())
                    .append(" HTTP/1.1\r\n");
            for (int index = 0; index < 40; index++) {
                request.append("X-Test-").append(index).append(": value\r\n");
            }
            request.append("\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            assertEquals(-1, socket.getInputStream().read());
        } finally {
            server.close();
        }
    }

    @Test
    public void incompleteRequestIsClosedAfterTheReadDeadline() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();

        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(DlnaLocalAudioServer.HTTP_REQUEST_TIMEOUT_MS + 1_000);
            socket.getOutputStream().write(("GET " + uri.getRawPath())
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            assertEquals(-1, socket.getInputStream().read());
        } finally {
            server.close();
        }
    }

    @Test
    public void concurrentMissingArtworkRequestsDoNotStarveTheAudioStream() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI audioUri = server.start();
        URI artworkUri = server.artworkUri("track-parallel-artwork".hashCode());
        List<Socket> artworkRequests = new ArrayList<>();

        try {
            for (int index = 0; index < 4; index++) {
                Socket artwork = new Socket(artworkUri.getHost(), artworkUri.getPort());
                artworkRequests.add(artwork);
                writeRequest(artwork, "GET", artworkUri);
            }
            Thread.sleep(75L);

            try (Socket audio = new Socket(audioUri.getHost(), audioUri.getPort())) {
                audio.setSoTimeout(200);
                writeRequest(audio, "GET", audioUri);
                InputStream input = audio.getInputStream();
                assertTrue(readHeaders(input).startsWith("HTTP/1.1 200 OK"));
                assertEquals(48, input.readNBytes(48).length);
            }
        } finally {
            for (Socket artwork : artworkRequests) {
                artwork.close();
            }
            server.close();
        }
    }

    @Test
    public void fourIncompleteArtworkRequestsDoNotBlockTheAudioStream() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI audioUri = server.start();
        URI artworkUri = server.artworkUri("track-slow-artwork".hashCode());
        List<Socket> incompleteRequests = new ArrayList<>();

        try {
            for (int index = 0; index < 4; index++) {
                Socket artwork = new Socket(artworkUri.getHost(), artworkUri.getPort());
                incompleteRequests.add(artwork);
                artwork.getOutputStream().write(("GET " + artworkUri.getRawPath()
                        + " HTTP/1.1\r\nHost: " + artworkUri.getHost())
                        .getBytes(StandardCharsets.US_ASCII));
                artwork.getOutputStream().flush();
            }
            Thread.sleep(75L);

            try (Socket audio = new Socket(audioUri.getHost(), audioUri.getPort())) {
                audio.setSoTimeout(300);
                writeRequest(audio, "GET", audioUri);
                InputStream input = audio.getInputStream();
                assertTrue(readHeaders(input).startsWith("HTTP/1.1 200 OK"));
                assertEquals(48, input.readNBytes(48).length);
            }
        } finally {
            for (Socket request : incompleteRequests) {
                request.close();
            }
            server.close();
        }
    }

    @Test
    public void rejectsConnectionsBeyondTheBoundedPendingQueue() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();
        List<Socket> occupied = new ArrayList<>();

        try {
            int acceptedCapacity = DlnaLocalAudioServer.CONNECTION_WORKER_COUNT
                    + DlnaLocalAudioServer.MAX_PENDING_CONNECTIONS;
            for (int index = 0; index < acceptedCapacity; index++) {
                Socket socket = new Socket(uri.getHost(), uri.getPort());
                occupied.add(socket);
                socket.getOutputStream().write(("GET " + uri.getRawPath())
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            Thread.sleep(100L);

            try (Socket overflow = new Socket(uri.getHost(), uri.getPort())) {
                overflow.setSoTimeout(500);
                overflow.getOutputStream().write(("GET " + uri.getRawPath())
                        .getBytes(StandardCharsets.US_ASCII));
                overflow.getOutputStream().flush();
                assertEquals(-1, overflow.getInputStream().read());
            }
        } finally {
            for (Socket socket : occupied) {
                socket.close();
            }
            server.close();
        }
    }

    @Test
    public void closeClosesRunningAndQueuedConnections() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        DlnaLocalAudioServer server = new DlnaLocalAudioServer(
                loopback, new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE));
        URI uri = server.start();
        List<Socket> accepted = new ArrayList<>();

        try {
            int acceptedCapacity = DlnaLocalAudioServer.CONNECTION_WORKER_COUNT
                    + DlnaLocalAudioServer.MAX_PENDING_CONNECTIONS;
            for (int index = 0; index < acceptedCapacity; index++) {
                Socket socket = new Socket(uri.getHost(), uri.getPort());
                socket.setSoTimeout(500);
                accepted.add(socket);
                socket.getOutputStream().write(("GET " + uri.getRawPath())
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            Thread.sleep(100L);

            server.close();

            for (Socket socket : accepted) {
                try {
                    assertEquals(-1, socket.getInputStream().read());
                } catch (IOException expected) {
                    // A peer close may surface as EOF or a connection reset.
                }
            }
        } finally {
            for (Socket socket : accepted) {
                socket.close();
            }
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
