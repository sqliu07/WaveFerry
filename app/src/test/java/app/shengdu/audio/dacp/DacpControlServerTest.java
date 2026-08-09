package app.shengdu.audio.dacp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.shengdu.audio.dacp.DacpControlServer.Command;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class DacpControlServerTest {
    @Test
    public void startStopIsIdempotentAndServerCanRestart() throws Exception {
        List<Command> commands = new ArrayList<>();
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                commands::add)) {
            int firstPort = server.start();
            int secondPort = server.start();

            assertEquals(firstPort, secondPort);
            assertTrue(server.isRunning());

            server.stop();
            server.stop();

            assertFalse(server.isRunning());

            int restartedPort = server.start();
            HttpResponse response = sendRequest(
                    restartedPort,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(204, response.statusCode);
            assertEquals(List.of(Command.PAUSE), commands);
        }
    }

    @Test
    public void recognizedCommandPathsDispatchCallback() throws Exception {
        List<Command> commands = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(5);
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                command -> {
                    commands.add(command);
                    latch.countDown();
                })) {
            int port = server.start();

            assertEquals(204, sendCommand(port, "play").statusCode);
            assertEquals(204, sendCommand(port, "pause").statusCode);
            assertEquals(204, sendCommand(port, "playpause").statusCode);
            assertEquals(204, sendCommand(port, "nextitem").statusCode);
            assertEquals(204, sendCommand(port, "previtem?dmcp.device-id=abc").statusCode);

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(
                    List.of(
                            Command.PLAY,
                            Command.PAUSE,
                            Command.PLAY_PAUSE,
                            Command.NEXT_ITEM,
                            Command.PREVIOUS_ITEM),
                    commands);
        }
    }

    @Test
    public void missingOrWrongActiveRemoteIsForbidden() throws Exception {
        List<Command> commands = new ArrayList<>();
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                commands::add)) {
            int port = server.start();

            HttpResponse missing = sendRequest(
                    port,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "\r\n");
            HttpResponse wrong = sendRequest(
                    port,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 99999\r\n"
                            + "\r\n");

            assertEquals(403, missing.statusCode);
            assertEquals(403, wrong.statusCode);
            assertTrue(commands.isEmpty());
        }
    }

    @Test
    public void unexpectedPeerAddressIsForbidden() throws Exception {
        List<Command> commands = new ArrayList<>();
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getByName("127.0.0.1"),
                0,
                InetAddress.getByName("127.0.0.2"),
                "12345",
                commands::add)) {
            int port = server.start();

            HttpResponse response = sendRequest(
                    port,
                    InetAddress.getByName("127.0.0.1"),
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(403, response.statusCode);
            assertTrue(commands.isEmpty());
        }
    }

    @Test
    public void expectedPeerAddressWithCorrectTokenDispatchesCommand() throws Exception {
        List<Command> commands = new ArrayList<>();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DacpControlServer server = new DacpControlServer(
                loopback,
                0,
                loopback,
                "12345",
                commands::add)) {
            int port = server.start();

            HttpResponse response = sendRequest(
                    port,
                    loopback,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(204, response.statusCode);
            assertEquals(List.of(Command.PAUSE), commands);
        }
    }

    @Test
    public void unknownPathReturnsNotFound() throws Exception {
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                command -> {
                    throw new AssertionError("unexpected command " + command);
                })) {
            int port = server.start();

            HttpResponse response = sendRequest(
                    port,
                    "GET /ctrl-int/1/shuffle HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(404, response.statusCode);
        }
    }

    @Test
    public void nonGetRequestReturnsMethodNotAllowed() throws Exception {
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                command -> {
                    throw new AssertionError("unexpected command " + command);
                })) {
            int port = server.start();

            HttpResponse response = sendRequest(
                    port,
                    "POST /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(405, response.statusCode);
            assertEquals("GET", response.headers.get("Allow"));
        }
    }

    @Test
    public void duplicateActiveRemoteHeaderReturnsBadRequest() throws Exception {
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                command -> {
                    throw new AssertionError("unexpected command " + command);
                })) {
            int port = server.start();

            HttpResponse response = sendRequest(
                    port,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(400, response.statusCode);
        }
    }

    @Test
    public void oversizedHeaderReturns431() throws Exception {
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                command -> {
                    throw new AssertionError("unexpected command " + command);
                })) {
            int port = server.start();

            String largeValue = "a".repeat(3000);
            HttpResponse response = sendRequest(
                    port,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "X-Large: " + largeValue + "\r\n"
                            + "\r\n");

            assertEquals(431, response.statusCode);
        }
    }

    @Test
    public void listenerFailureReturnsInternalServerError() throws Exception {
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                command -> {
                    throw new IOException("boom");
                })) {
            int port = server.start();

            HttpResponse response = sendRequest(
                    port,
                    "GET /ctrl-int/1/pause HTTP/1.1\r\n"
                            + "Host: 127.0.0.1\r\n"
                            + "Active-Remote: 12345\r\n"
                            + "\r\n");

            assertEquals(500, response.statusCode);
        }
    }

    @Test
    public void stopClosesHalfOpenClientQuicklyAndDoesNotDispatchCommand() throws Exception {
        CopyOnWriteArrayList<Command> commands = new CopyOnWriteArrayList<>();
        try (DacpControlServer server = new DacpControlServer(
                InetAddress.getLoopbackAddress(),
                0,
                "12345",
                commands::add)) {
            int port = server.start();
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(1000);
                OutputStream output = socket.getOutputStream();
                output.write(("GET /ctrl-int/1/pause HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();

                long startNanos = System.nanoTime();
                server.stop();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                assertFalse(server.isRunning());
                assertTrue("stop took " + elapsedMs + "ms", elapsedMs < 1000L);
                assertTrue(commands.isEmpty());
                try {
                    assertEquals(-1, socket.getInputStream().read());
                } catch (SocketException expectedReset) {
                    // Closing a socket with an incomplete request may surface as EOF or RST.
                }
            }
        }
    }

    private static HttpResponse sendCommand(int port, String path) throws IOException {
        return sendRequest(
                port,
                InetAddress.getLoopbackAddress(),
                "GET /ctrl-int/1/" + path + " HTTP/1.1\r\n"
                        + "Host: 127.0.0.1\r\n"
                        + "Active-Remote: 12345\r\n"
                        + "\r\n");
    }

    private static HttpResponse sendRequest(int port, String request) throws IOException {
        return sendRequest(port, InetAddress.getLoopbackAddress(), request);
    }

    private static HttpResponse sendRequest(int port, InetAddress localAddress, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port, localAddress, 0)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return readResponse(socket.getInputStream());
        }
    }

    private static HttpResponse readResponse(InputStream input) throws IOException {
        String statusLine = readLine(input);
        if (statusLine == null) {
            throw new EOFException("missing HTTP status line");
        }
        String[] parts = statusLine.split(" ", 3);
        int statusCode = Integer.parseInt(parts[1]);

        HttpResponse response = new HttpResponse(statusCode);
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new EOFException("unexpected EOF while reading headers");
            }
            if (line.isEmpty()) {
                return response;
            }
            int separator = line.indexOf(':');
            response.headers.put(line.substring(0, separator), line.substring(separator + 2));
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new EOFException("unexpected EOF inside line");
            }
            if (value == '\r') {
                int next = input.read();
                if (next != '\n') {
                    throw new IOException("malformed line ending");
                }
                return buffer.toString(StandardCharsets.US_ASCII);
            }
            buffer.write(value);
        }
    }

    private static final class HttpResponse {
        final int statusCode;
        final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();

        HttpResponse(int statusCode) {
            this.statusCode = statusCode;
        }
    }
}
