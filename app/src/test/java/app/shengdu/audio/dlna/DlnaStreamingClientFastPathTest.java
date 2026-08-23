package app.shengdu.audio.dlna;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class DlnaStreamingClientFastPathTest {
    private static final String AV_TRANSPORT_TYPE = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final AudioStreamFormat PCM_16_STEREO_44K =
            new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE);

    @Test
    public void prepareOnlyPreflightsTransportBeforeExplicitPlaybackStart() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();

                assertEquals(Arrays.asList("GetTransportInfo"), renderer.actionNames());
                client.writePcm(new byte[] { 1, 2, 3, 4 }, 0, 4);
                assertFalse(renderer.actionNames().contains("SetAVTransportURI"));
                assertFalse(renderer.actionNames().contains("Play"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void startPlaybackSkipsSettlePollingAfterSuccessfulStopAndSetUri() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("PLAYING");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.startPlayback();

                assertEquals(
                        Arrays.asList(
                                "GetTransportInfo",
                                "GetTransportInfo",
                                "Stop",
                                "SetAVTransportURI",
                                "Play"),
                        renderer.actionNames());
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void artworkRefreshPublishesANewVersionedUri() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("PLAYING");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.updateMetadata(
                        "Song",
                        "Artist",
                        "Album",
                        "track-1",
                        245_000L,
                        0L,
                        new byte[] { 1, 2, 3 },
                        true);
                client.startPlayback();

                String initialSetUriBody = renderer.firstRequestBody("SetAVTransportURI");
                if (!initialSetUriBody.contains("Song")
                        || !initialSetUriBody.contains("Artist")
                        || !initialSetUriBody.contains("Album")) {
                    throw new AssertionError("initial SetAVTransportURI did not include cached metadata");
                }
                URI artworkUri = artworkUriFrom(initialSetUriBody);
                assertArrayEquals(new byte[] { 1, 2, 3 }, readArtwork(artworkUri));

                client.updateMetadata(
                        "Song",
                        "Artist",
                        "Album",
                        "track-1",
                        245_000L,
                        0L,
                        new byte[] { 9, 8, 7 },
                        true);

                assertEquals(2, renderer.actionCount("SetAVTransportURI"));
                URI refreshedArtworkUri = artworkUriFrom(
                        renderer.requestBody("SetAVTransportURI", 1));
                assertNotEquals(artworkUri, refreshedArtworkUri);
                assertArrayEquals(new byte[] { 9, 8, 7 }, readArtwork(refreshedArtworkUri));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void artworkArrivingAfterTextPublishesANewArtworkUri() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.updateMetadata(
                        "Song", "Artist", "Album", "track-late-artwork",
                        245_000L, 0L, null, true);
                client.startPlayback();

                String initialSetUriBody = renderer.firstRequestBody("SetAVTransportURI");
                if (initialSetUriBody.contains(".jpg")) {
                    throw new AssertionError("metadata without artwork advertised a stale image URL");
                }

                client.updateMetadata(
                        "Song", "Artist", "Album", "track-late-artwork",
                        245_000L, 0L, new byte[] { 7, 8, 9 }, true);

                assertEquals(2, renderer.actionCount("SetAVTransportURI"));
                URI artworkUri = artworkUriFrom(renderer.requestBody("SetAVTransportURI", 1));
                assertArrayEquals(new byte[] { 7, 8, 9 }, readArtwork(artworkUri));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void positionSyncRetriesAfterRendererFinishesTransitioning() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");
            renderer.failNextWith701("Seek");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.updateMetadata(
                        "Song", "Artist", "Album", "track-position",
                        245_000L, 42_000L, null, true);
                client.startPlayback();

                renderer.awaitActionCount("Seek", 2);
                assertEquals(2, renderer.actionCount("Seek"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void positionRetryBackoffDoesNotBlockSessionClose() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");
            renderer.failNextWith701("Seek");

            DlnaStreamingClient client = newClient(renderer);
            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                client.prepare();
                client.updateMetadata(
                        "Song", "Artist", "Album", "track-close-during-position-retry",
                        245_000L, 42_000L, null, true);
                client.startPlayback();
                renderer.awaitActionCount("Seek", 1);

                Future<?> close = closer.submit(client::close);
                close.get(80L, TimeUnit.MILLISECONDS);
            } finally {
                closer.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    public void hangingPositionSyncCanBeCancelledByClose() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");
            renderer.hangNext("Seek");

            DlnaStreamingClient client = newClient(renderer);
            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                client.prepare();
                client.updateMetadata(
                        "Song", "Artist", "Album", "track-hanging-position",
                        245_000L, 42_000L, null, true);
                client.startPlayback();
                renderer.awaitActionCount("Seek", 1);

                Future<?> close = closer.submit(client::close);
                close.get(750L, TimeUnit.MILLISECONDS);
            } finally {
                closer.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    public void hangingTransportPollCanBeCancelledByClose() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                client.prepare();
                client.startPlayback();
                renderer.hangNext("GetTransportInfo");
                renderer.awaitActionCount("GetTransportInfo", 3);

                Future<?> close = closer.submit(client::close);
                close.get(750L, TimeUnit.MILLISECONDS);
            } finally {
                closer.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    public void hangingMetadataTransitionCanBeCancelledByClose() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            ExecutorService operations = Executors.newFixedThreadPool(2);
            try {
                client.prepare();
                client.startPlayback();
                renderer.hangNext("SetAVTransportURI");
                Future<?> metadata = operations.submit(() -> {
                    try {
                        client.updateMetadata(
                                "Next song", "Artist", "Album", "track-hanging-metadata",
                                245_000L, 0L, null, true);
                    } catch (IOException expected) {
                        // Session close is expected to cancel the obsolete SOAP transition.
                    }
                });
                renderer.awaitActionCount("SetAVTransportURI", 2);

                Future<?> close = operations.submit(client::close);
                close.get(750L, TimeUnit.MILLISECONDS);
                metadata.get(750L, TimeUnit.MILLISECONDS);
            } finally {
                operations.shutdownNow();
                client.close();
            }
        }
    }

    @Test
    public void hangingPositionSyncIsCancelledByPause() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");
            renderer.hangNext("Seek");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.updateMetadata(
                        "Song", "Artist", "Album", "track-pause-cancels-position",
                        245_000L, 42_000L, null, true);
                client.startPlayback();
                renderer.awaitActionCount("Seek", 1);

                long startedNanos = System.nanoTime();
                client.pause();

                assertEquals(1, renderer.actionCount("Pause"));
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos) >= 750L) {
                    throw new AssertionError("pause waited for the obsolete position sync");
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void cachedMetadataIsUsedWhenPlaybackStartsBeforeQueuedApply() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.cacheMetadata(
                        "Cached song", "Artist", "Album", "track-cached-before-start",
                        245_000L, 0L, new byte[] { 4, 5, 6 }, true);
                client.startPlayback();
                List<String> actionsAfterStart = renderer.actionNames();

                client.updateMetadata(
                        "Cached song", "Artist", "Album", "track-cached-before-start",
                        245_000L, 0L, new byte[] { 4, 5, 6 }, true);

                assertEquals(actionsAfterStart, renderer.actionNames());
                if (!renderer.firstRequestBody("SetAVTransportURI").contains("Cached song")) {
                    throw new AssertionError("cached metadata was not used for the initial URI");
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void staleQueuedMetadataCannotReplaceTheLatestSnapshot() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                long staleRevision = client.cacheMetadata(
                        "Old song", "Old artist", "Old album", "track-old",
                        180_000L, 70_000L, new byte[] { 1, 2, 3 }, true);
                long latestRevision = client.cacheMetadata(
                        "New song", "New artist", "New album", "track-new",
                        240_000L, 0L, new byte[] { 7, 8, 9 }, true);

                client.startPlayback();
                client.applyCachedMetadata(staleRevision);
                client.applyCachedMetadata(latestRevision);

                assertEquals(1, renderer.actionCount("SetAVTransportURI"));
                String body = renderer.firstRequestBody("SetAVTransportURI");
                if (!body.contains("New song") || body.contains("Old song")) {
                    throw new AssertionError("stale metadata replaced the latest snapshot");
                }
                assertArrayEquals(
                        new byte[] { 7, 8, 9 },
                        readArtwork(artworkUriFrom(body)));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void cachedPausedMetadataDefersPlayUntilSourceResumes() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.updateMetadata(
                        "Paused song", "Artist", "Album", "track-paused",
                        180_000L, 30_000L, null, false);
                client.startPlayback();

                assertFalse(renderer.actionNames().contains("Play"));

                client.resume();
                client.updateMetadata(
                        "Paused song", "Artist", "Album", "track-paused",
                        180_000L, 30_000L, null, true);

                renderer.awaitActionCount("Seek", 1);
                List<String> actions = renderer.actionNames();
                assertEquals("Play", actions.get(actions.size() - 2));
                assertEquals("Seek", actions.get(actions.size() - 1));
                if (!renderer.firstRequestBody("Seek").contains("<Target>0:00:30.000</Target>")) {
                    throw new AssertionError("resume did not restore the cached source position");
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void prepareToleratesUnsupportedTransportStateQuery() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.failNextWithControlError("GetTransportInfo", 501);
            renderer.enqueueState("STOPPED");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.startPlayback();

                assertEquals(
                        Arrays.asList(
                                "GetTransportInfo",
                                "GetTransportInfo",
                                "SetAVTransportURI",
                                "Play"),
                        renderer.actionNames());
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void prepareRejectsHttpFailureWithoutUpnpErrorCode() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.failNextWithControlError("GetTransportInfo", 0);

            DlnaStreamingClient client = newClient(renderer);
            try {
                try {
                    client.prepare();
                    throw new AssertionError("expected an unrecognized preflight failure");
                } catch (IOException expected) {
                    assertEquals(Arrays.asList("GetTransportInfo"), renderer.actionNames());
                }
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void transitionFaultRetriesOnlyTheRejectedAction() throws Exception {
        try (FakeRenderer renderer = new FakeRenderer()) {
            renderer.enqueueState("STOPPED");
            renderer.enqueueState("STOPPED");
            renderer.failNextWith701("SetAVTransportURI");

            DlnaStreamingClient client = newClient(renderer);
            try {
                client.prepare();
                client.startPlayback();

                assertEquals(
                        Arrays.asList(
                                "GetTransportInfo",
                                "GetTransportInfo",
                                "SetAVTransportURI",
                                "SetAVTransportURI",
                                "Play"),
                        renderer.actionNames());
            } finally {
                client.close();
            }
        }
    }

    private static DlnaStreamingClient newClient(FakeRenderer renderer) throws Exception {
        return new DlnaStreamingClient(
                InetAddress.getByName("127.0.0.1"),
                new DlnaRendererDescription(
                        "uuid:test-renderer",
                        "Renderer",
                        renderer.descriptionUri(),
                        renderer.controlUri(),
                        AV_TRANSPORT_TYPE,
                        null,
                        "",
                        null),
                PCM_16_STEREO_44K);
    }

    private static URI artworkUriFrom(String setUriBody) {
        int jpgEnd = setUriBody.indexOf(".jpg");
        int start = jpgEnd < 0 ? -1 : setUriBody.lastIndexOf("http://", jpgEnd);
        int end = jpgEnd < 0 ? -1 : setUriBody.indexOf("&lt;", jpgEnd);
        if (start < 0 || end < start) {
            throw new AssertionError("SetAVTransportURI did not contain an artwork URI");
        }
        return URI.create(setUriBody.substring(start, end));
    }

    private static byte[] readArtwork(URI uri) throws Exception {
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(1_000);
            OutputStream output = socket.getOutputStream();
            String target = uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            output.write(("GET " + target + " HTTP/1.1\r\nHost: "
                    + uri.getHost() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            String headers = FakeRenderer.readHeaders(input);
            return input.readNBytes(FakeRenderer.contentLength(headers));
        }
    }

    private static final class FakeRenderer implements AutoCloseable {
        private final Object monitor = new Object();
        private final List<RecordedRequest> requests = new ArrayList<>();
        private final Deque<String> transportStates = new ArrayDeque<>();
        private final ServerSocket server;
        private final Thread serverThread;
        private String failNextAction;
        private int failNextErrorCode;
        private String hangNextAction;

        private FakeRenderer() throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            serverThread = new Thread(this::serve, "dlna-fast-path-renderer");
            serverThread.setDaemon(true);
            serverThread.start();
        }

        URI controlUri() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/av");
        }

        URI descriptionUri() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort() + "/desc.xml");
        }

        void enqueueState(String state) {
            synchronized (monitor) {
                transportStates.addLast(state);
            }
        }

        void failNextWith701(String action) {
            failNextWithControlError(action, 701);
        }

        void failNextWithControlError(String action, int errorCode) {
            synchronized (monitor) {
                failNextAction = action;
                failNextErrorCode = errorCode;
            }
        }

        void hangNext(String action) {
            synchronized (monitor) {
                hangNextAction = action;
            }
        }

        List<String> actionNames() {
            synchronized (monitor) {
                ArrayList<String> names = new ArrayList<>();
                for (RecordedRequest request : requests) {
                    names.add(request.action);
                }
                return names;
            }
        }

        int actionCount(String action) {
            int count = 0;
            synchronized (monitor) {
                for (RecordedRequest request : requests) {
                    if (action.equals(request.action)) {
                        count++;
                    }
                }
            }
            return count;
        }

        void awaitActionCount(String action, int expectedCount) throws Exception {
            long deadline = System.nanoTime() + 2_000_000_000L;
            synchronized (monitor) {
                while (actionCount(action) < expectedCount && System.nanoTime() < deadline) {
                    monitor.wait(25L);
                }
            }
            if (actionCount(action) < expectedCount) {
                throw new AssertionError("timed out waiting for " + expectedCount + " "
                        + action + " actions: " + actionNames());
            }
        }

        String firstRequestBody(String action) {
            return requestBody(action, 0);
        }

        String requestBody(String action, int occurrence) {
            synchronized (monitor) {
                int seen = 0;
                for (RecordedRequest request : requests) {
                    if (action.equals(request.action)) {
                        if (seen == occurrence) {
                            return request.body;
                        }
                        seen++;
                    }
                }
                throw new AssertionError("no request recorded for action " + action
                        + " at occurrence " + occurrence);
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            serverThread.join(1_000L);
        }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handleControl(socket);
                } catch (IOException ignored) {
                    if (!server.isClosed()) {
                        // The assertions below expose an incomplete request sequence.
                    }
                }
            }
        }

        private void handleControl(Socket socket) throws IOException {
            InputStream input = socket.getInputStream();
            String headers = readHeaders(input);
            byte[] body = input.readNBytes(contentLength(headers));
            String action = soapAction(headers);
            boolean failWithTransition;
            boolean hangWithoutResponse;
            int errorCode;
            synchronized (monitor) {
                requests.add(new RecordedRequest(action, new String(body, StandardCharsets.UTF_8)));
                monitor.notifyAll();
                failWithTransition = action.equals(failNextAction);
                hangWithoutResponse = action.equals(hangNextAction);
                errorCode = failNextErrorCode;
                if (failWithTransition) {
                    failNextAction = null;
                    failNextErrorCode = 0;
                }
                if (hangWithoutResponse) {
                    hangNextAction = null;
                }
            }
            if (hangWithoutResponse) {
                socket.setSoTimeout(2_000);
                try {
                    while (input.read() >= 0) {
                        // Wait until the client cancels this optional SOAP action.
                    }
                } catch (IOException ignored) {
                    // The timeout only keeps a failing regression test bounded.
                }
                return;
            }
            OutputStream output = socket.getOutputStream();
            if (failWithTransition) {
                writeFault(output, errorCode, errorCode == 701
                        ? "Transition not available" : "Action failed");
                return;
            }
            if ("GetTransportInfo".equals(action)) {
                writeXml(output, transportInfoResponse(nextState()));
                return;
            }
            writeXml(output, "");
        }

        private String nextState() {
            synchronized (monitor) {
                return transportStates.isEmpty() ? "STOPPED" : transportStates.removeFirst();
            }
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            while ((current = input.read()) != -1) {
                output.write(current);
                if (previous == '\r' && current == '\n') {
                    byte[] bytes = output.toByteArray();
                    int length = bytes.length;
                    if (length >= 4
                            && bytes[length - 4] == '\r'
                            && bytes[length - 3] == '\n'
                            && bytes[length - 2] == '\r'
                            && bytes[length - 1] == '\n') {
                        return output.toString(StandardCharsets.ISO_8859_1.name());
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

        private static String soapAction(String headers) {
            String header = header(headers, "soapaction");
            int separator = header.lastIndexOf('#');
            int end = header.lastIndexOf('"');
            if (separator < 0 || end <= separator) {
                return header;
            }
            return header.substring(separator + 1, end);
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

        private static String transportInfoResponse(String state) {
            return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<s:Body><u:GetTransportInfoResponse xmlns:u=\"" + AV_TRANSPORT_TYPE + "\">"
                    + "<CurrentTransportState>" + state + "</CurrentTransportState>"
                    + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                    + "</u:GetTransportInfoResponse></s:Body></s:Envelope>";
        }

        private static void writeXml(OutputStream output, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: "
                    + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.flush();
        }

        private static void writeFault(OutputStream output, int code, String description)
                throws IOException {
            String body = "<s:Envelope><s:Body><s:Fault><detail><UPnPError>"
                    + "<errorCode>" + code + "</errorCode><errorDescription>"
                    + description + "</errorDescription>"
                    + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            output.write(("HTTP/1.1 500 Internal Server Error\r\nContent-Type: text/xml; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.write(bytes);
            output.flush();
        }

        private static final class RecordedRequest {
            private final String action;
            private final String body;

            private RecordedRequest(String action, String body) {
                this.action = action;
                this.body = body;
            }
        }
    }
}
