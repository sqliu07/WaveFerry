package app.shengdu.audio.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.net.ServerSocket;
import java.net.Socket;

import org.junit.Test;

public final class DlnaAvTransportClientTest {
    @Test
    public void sendsExpectedSoapActions() throws Exception {
        List<String> actions = Collections.synchronizedList(new ArrayList<>());
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread serverThread = new Thread(() -> serve(server, actions), "dlna-soap-test");
        serverThread.start();
        try {
            String type = "urn:schemas-upnp-org:service:AVTransport:1";
            DlnaAvTransportClient client = new DlnaAvTransportClient(
                    new URI("http://127.0.0.1:" + server.getLocalPort() + "/av"), type);

            client.setUri(new URI("http://192.168.1.2/audio.wav?x=1&y=2"), "");
            client.play();
            client.pause();
            client.seekTo(245_678L);
            client.stop();

            assertEquals(5, actions.size());
            assertTrue(actions.get(0).contains("#SetAVTransportURI"));
            assertTrue(actions.get(0).contains("<u:SetAVTransportURI"));
            assertTrue(actions.get(0).contains("x=1&amp;y=2"));
            assertTrue(actions.get(1).contains("#Play"));
            assertTrue(actions.get(2).contains("#Pause"));
            assertTrue(actions.get(3).contains("#Seek"));
            assertTrue(actions.get(3).contains("<Unit>REL_TIME</Unit>"));
            assertTrue(actions.get(3).contains("<Target>0:04:05.678</Target>"));
            assertTrue(actions.get(4).contains("#Stop"));
        } finally {
            server.close();
            serverThread.join(1_000L);
        }
    }

    @Test
    public void includesUpnpFaultDetailsInSetUriFailure() throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread serverThread = new Thread(() -> serveSetUriFailure(server), "dlna-soap-fault-test");
        serverThread.start();
        try {
            DlnaAvTransportClient client = new DlnaAvTransportClient(
                    new URI("http://127.0.0.1:" + server.getLocalPort() + "/av"),
                    "urn:schemas-upnp-org:service:AVTransport:1");

            try {
                client.setUri(new URI("http://192.168.1.2/audio.wav"), "");
                throw new AssertionError("expected SetAVTransportURI failure");
            } catch (IOException error) {
                assertTrue(error.getMessage().contains("HTTP 500"));
                assertTrue(error.getMessage().contains("714 Illegal MIME-Type"));
            }
        } finally {
            server.close();
            serverThread.join(1_000L);
        }
    }

    @Test
    public void readsCurrentTransportState() throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread serverThread = new Thread(() -> serveTransportState(server), "dlna-transport-state-test");
        serverThread.start();
        try {
            DlnaAvTransportClient client = new DlnaAvTransportClient(
                    new URI("http://127.0.0.1:" + server.getLocalPort() + "/av"),
                    "urn:schemas-upnp-org:service:AVTransport:1");

            assertEquals("PLAYING", client.transportState());
        } finally {
            server.close();
            serverThread.join(1_000L);
        }
    }

    @Test
    public void readsPrefixedCurrentTransportState() throws Exception {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread serverThread = new Thread(
                () -> servePrefixedTransportState(server), "dlna-prefixed-state-test");
        serverThread.start();
        try {
            DlnaAvTransportClient client = new DlnaAvTransportClient(
                    new URI("http://127.0.0.1:" + server.getLocalPort() + "/av"),
                    "urn:schemas-upnp-org:service:AVTransport:1");

            assertEquals("PLAYING", client.transportState());
        } finally {
            server.close();
            serverThread.join(1_000L);
        }
    }

    private static void serve(ServerSocket server, List<String> actions) {
        try {
            for (int request = 0; request < 5; request++) {
                try (Socket socket = server.accept()) {
                    String headers = readHeaders(socket.getInputStream());
                    int contentLength = contentLength(headers);
                    byte[] body = socket.getInputStream().readNBytes(contentLength);
                    String soapAction = header(headers, "soapaction");
                    actions.add(soapAction + "\n" + new String(body, StandardCharsets.UTF_8));
                    OutputStream output = socket.getOutputStream();
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                }
            }
        } catch (Exception ignored) {
            // Assertions below expose an incomplete request sequence.
        }
    }

    private static void serveSetUriFailure(ServerSocket server) {
        String body = "<s:Envelope><s:Body><s:Fault><detail><UPnPError>"
                + "<errorCode>714</errorCode><errorDescription>Illegal MIME-Type</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
        try (Socket socket = server.accept()) {
            String headers = readHeaders(socket.getInputStream());
            socket.getInputStream().readNBytes(contentLength(headers));
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 500 Internal Server Error\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {
            // The assertion in the caller exposes an incomplete fault response.
        }
    }

    private static void serveTransportState(ServerSocket server) {
        String body = "<s:Envelope><s:Body><u:GetTransportInfoResponse>"
                + "<CurrentTransportState>PLAYING</CurrentTransportState>"
                + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                + "</u:GetTransportInfoResponse></s:Body></s:Envelope>";
        try (Socket socket = server.accept()) {
            String headers = readHeaders(socket.getInputStream());
            socket.getInputStream().readNBytes(contentLength(headers));
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {
            // The assertion in the caller exposes an incomplete response.
        }
    }

    private static void servePrefixedTransportState(ServerSocket server) {
        String body = "<s:Envelope><s:Body><u:GetTransportInfoResponse>"
                + "<u:CurrentTransportState>PLAYING</u:CurrentTransportState>"
                + "</u:GetTransportInfoResponse></s:Body></s:Envelope>";
        try (Socket socket = server.accept()) {
            String headers = readHeaders(socket.getInputStream());
            socket.getInputStream().readNBytes(contentLength(headers));
            OutputStream output = socket.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Length: "
                    + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (Exception ignored) {
            // The assertion in the caller exposes an incomplete response.
        }
    }

    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            output.write(current);
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                int length = bytes.length;
                if (length >= 4 && bytes[length - 4] == '\r' && bytes[length - 3] == '\n') {
                    return output.toString(StandardCharsets.ISO_8859_1);
                }
            }
            previous = current;
        }
        throw new AssertionError("unexpected HTTP EOF");
    }

    private static int contentLength(String headers) {
        String value = header(headers, "content-length");
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static String header(String headers, String expectedName) {
        for (String line : headers.split("\r\n")) {
            int delimiter = line.indexOf(':');
            if (delimiter > 0 && expectedName.equalsIgnoreCase(line.substring(0, delimiter))) {
                return line.substring(delimiter + 1).trim();
            }
        }
        return null;
    }
}
