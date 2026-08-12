package app.shengdu.audio.dlna;

import app.shengdu.audio.format.AudioStreamFormat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Small per-session HTTP endpoint that exposes only a tokenized live PCM/WAV stream. */
final class DlnaLocalAudioServer implements AutoCloseable {
    private static final int MAX_REQUEST_LINE_BYTES = 2_048;
    // PlaybackCapture emits 1,024-frame blocks (about 23 ms at 44.1 kHz). Keep only
    // a small jitter window; a larger queue turns brief TV/network stalls into lasting lag.
    private static final int MAX_QUEUED_BUFFERS = 3;
    private static final long BUFFER_WAIT_MS = 100L;
    private static final int SILENCE_DURATION_MS = 100;

    private final InetAddress receiverAddress;
    private final AudioStreamFormat format;
    private final byte[] silenceBuffer;
    private final BlockingQueue<byte[]> pcmBuffers = new ArrayBlockingQueue<>(MAX_QUEUED_BUFFERS);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Socket> activeAudioSocket = new AtomicReference<>();
    private final ExecutorService connections = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "dlna-http-connection");
        thread.setDaemon(true);
        return thread;
    });
    private final String token;

    private ServerSocket serverSocket;
    private Thread serverThread;
    private URI audioUri;
    private volatile byte[] artworkJpeg;

    DlnaLocalAudioServer(InetAddress receiverAddress, AudioStreamFormat format) {
        this.receiverAddress = receiverAddress;
        this.format = format;
        int silenceFrames = Math.max(1, format.sampleRateHz() * SILENCE_DURATION_MS / 1_000);
        silenceBuffer = new byte[silenceFrames * format.bytesPerFrame()];
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    URI start() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(0));
        serverSocket = server;
        InetAddress localAddress = routeAddress(receiverAddress);
        serverThread = new Thread(this::serve, "dlna-live-audio");
        serverThread.setDaemon(true);
        serverThread.start();
        audioUri = buildAudioUri(localAddress, server.getLocalPort(), token);
        return audioUri;
    }

    static URI buildAudioUri(InetAddress localAddress, int port, String pathToken) {
        String host = localAddress.getHostAddress();
        if (localAddress instanceof Inet6Address) {
            int scopeDelimiter = host.indexOf('%');
            if (scopeDelimiter >= 0) {
                // An interface scope is local to this phone and is meaningless to the renderer.
                host = host.substring(0, scopeDelimiter);
            }
            host = "[" + host + "]";
        }
        return URI.create("http://" + host + ":" + port
                + "/waveferry/" + pathToken + ".wav");
    }

    URI artworkUri(int contentVersion) {
        URI currentAudioUri = audioUri;
        if (currentAudioUri == null) {
            throw new IllegalStateException("DLNA audio server has not started");
        }
        return URI.create(currentAudioUri.toString().replace(token + ".wav", token + ".jpg")
                + "?v=" + Integer.toUnsignedString(contentVersion, 16));
    }

    void updateArtwork(byte[] jpeg) {
        artworkJpeg = jpeg == null || jpeg.length == 0
                ? null
                : Arrays.copyOf(jpeg, jpeg.length);
    }

    void discardBufferedPcm() {
        pcmBuffers.clear();
    }

    void writePcm(byte[] source, int offset, int byteCount) {
        if (closed.get() || byteCount <= 0) {
            return;
        }
        byte[] copy = new byte[byteCount];
        System.arraycopy(source, offset, copy, 0, byteCount);
        // Real-time capture must never wait for a slow renderer.
        if (!pcmBuffers.offer(copy)) {
            // Live playback should skip stale audio instead of accumulating latency.
            pcmBuffers.poll();
            pcmBuffers.offer(copy);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pcmBuffers.clear();
        artworkJpeg = null;
        closeQuietly(activeAudioSocket.getAndSet(null));
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing an already closed listener is harmless.
            }
        }
        connections.shutdownNow();
    }

    private void serve() {
        while (!closed.get()) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                Socket accepted = socket;
                connections.execute(() -> handle(accepted));
            } catch (IOException ignored) {
                if (!closed.get()) {
                    // A renderer may abandon a stream while seeking/retrying. Keep listening.
                }
            } catch (RejectedExecutionException ignored) {
                closeQuietly(socket);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            if (!receiverAddress.equals(socket.getInetAddress())) {
                return;
            }
            HttpRequest request = readRequest(socket);
            if (request == null) {
                return;
            }
            if (request.rangeStart < 0
                    || (request.resource == Resource.AUDIO
                    && request.rangeStart >= DlnaWavStream.LIVE_CONTENT_LENGTH_BYTES)) {
                writeRangeNotSatisfiable(socket.getOutputStream(), request.resource);
            } else if (request.resource == Resource.ARTWORK) {
                serveArtwork(socket.getOutputStream(), request);
            } else if ("HEAD".equals(request.method)) {
                writeAudioHeaders(socket.getOutputStream(), request.hasRange, request.rangeStart);
            } else {
                replaceActiveAudioSocket(socket);
                try {
                    stream(socket.getOutputStream(), request.hasRange, request.rangeStart);
                } finally {
                    activeAudioSocket.compareAndSet(socket, null);
                }
            }
        } catch (IOException ignored) {
            // Renderers commonly close an old live connection while replacing its URI.
        }
    }

    private HttpRequest readRequest(Socket socket) throws IOException {
        BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
        String requestLine = readLine(input);
        String audioPath = "/waveferry/" + token + ".wav";
        String artworkPath = "/waveferry/" + token + ".jpg";
        String[] parts = requestLine.split(" ", 3);
        String requestPath = parts.length < 2 ? "" : parts[1];
        int queryStart = requestPath.indexOf('?');
        String pathWithoutQuery = queryStart < 0 ? requestPath : requestPath.substring(0, queryStart);
        if (parts.length != 3
                || !("GET".equals(parts[0]) || "HEAD".equals(parts[0]))
                || !(audioPath.equals(pathWithoutQuery) || artworkPath.equals(pathWithoutQuery))) {
            return null;
        }
        Resource resource = artworkPath.equals(pathWithoutQuery) ? Resource.ARTWORK : Resource.AUDIO;
        boolean hasRange = false;
        long rangeStart = 0;
        while (true) {
            String line = readLine(input);
            if (line.isEmpty()) {
                return new HttpRequest(parts[0], resource, hasRange, rangeStart);
            }
            int delimiter = line.indexOf(':');
            if (delimiter > 0 && "range".equalsIgnoreCase(line.substring(0, delimiter).trim())) {
                String value = line.substring(delimiter + 1).trim();
                if (value.startsWith("bytes=") && value.endsWith("-")) {
                    try {
                        rangeStart = Long.parseLong(value.substring(6, value.length() - 1));
                        hasRange = true;
                    } catch (NumberFormatException ignored) {
                        rangeStart = -1;
                    }
                }
            }
        }
    }

    private void stream(OutputStream output, boolean partialResponse, long rangeStart) throws IOException {
        writeAudioHeaders(output, partialResponse, rangeStart);
        byte[] wavHeader = DlnaWavStream.header(format);
        if (rangeStart < wavHeader.length) {
            output.write(wavHeader, (int) rangeStart, wavHeader.length - (int) rangeStart);
        }
        output.flush();
        while (!closed.get()) {
            try {
                byte[] buffer = pcmBuffers.poll(BUFFER_WAIT_MS, TimeUnit.MILLISECONDS);
                // LG validates the media stream while SetAVTransportURI is still pending.
                // Feed real-time silence until Android capture starts so the control call
                // and capture startup cannot deadlock waiting on each other.
                output.write(buffer == null ? silenceBuffer : buffer);
                output.flush();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void serveArtwork(OutputStream output, HttpRequest request) throws IOException {
        byte[] jpeg = artworkJpeg;
        if (jpeg == null || request.rangeStart >= jpeg.length) {
            if (request.hasRange) {
                writeRangeNotSatisfiable(output, Resource.ARTWORK);
            } else {
                writeNotFound(output);
            }
            return;
        }
        int offset = request.hasRange ? (int) request.rangeStart : 0;
        writeArtworkHeaders(output, jpeg.length, request.hasRange, offset);
        if (!"HEAD".equals(request.method)) {
            output.write(jpeg, offset, jpeg.length - offset);
            output.flush();
        }
    }

    private static void writeAudioHeaders(
            OutputStream output, boolean partialResponse, long rangeStart) throws IOException {
        long lastByte = DlnaWavStream.LIVE_CONTENT_LENGTH_BYTES - 1L;
        long responseLength = DlnaWavStream.LIVE_CONTENT_LENGTH_BYTES - rangeStart;
        output.write(((partialResponse ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                + "Content-Type: audio/wav\r\n"
                + "Content-Disposition: inline; filename=\"WaveFerry.wav\"\r\n"
                + "Content-Length: " + responseLength + "\r\n"
                + "Connection: close\r\n"
                + "Accept-Ranges: bytes\r\n"
                + (partialResponse ? "Content-Range: bytes " + rangeStart + "-" + lastByte + "/"
                        + DlnaWavStream.LIVE_CONTENT_LENGTH_BYTES + "\r\n" : "")
                + "transferMode.dlna.org: Streaming\r\n"
                + "contentFeatures.dlna.org: " + DlnaLiveAudioMetadata.contentFeatures()
                + "\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeArtworkHeaders(
            OutputStream output, int byteCount, boolean partialResponse, int rangeStart) throws IOException {
        int responseLength = byteCount - rangeStart;
        output.write(((partialResponse ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
                + "Content-Type: image/jpeg\r\n"
                + "Content-Length: " + responseLength + "\r\n"
                + "Connection: close\r\n"
                + "Accept-Ranges: bytes\r\n"
                + (partialResponse ? "Content-Range: bytes " + rangeStart + "-" + (byteCount - 1) + "/"
                        + byteCount + "\r\n" : "")
                + "transferMode.dlna.org: Interactive\r\n"
                + "contentFeatures.dlna.org: DLNA.ORG_PN=JPEG_SM;DLNA.ORG_CI=0\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeRangeNotSatisfiable(OutputStream output, Resource resource) throws IOException {
        long length = resource == Resource.AUDIO ? DlnaWavStream.LIVE_CONTENT_LENGTH_BYTES : 0;
        output.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                + "Content-Range: bytes */" + length
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void writeNotFound(OutputStream output) throws IOException {
        output.write(("HTTP/1.1 404 Not Found\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.flush();
    }

    private void replaceActiveAudioSocket(Socket socket) {
        Socket previous = activeAudioSocket.getAndSet(socket);
        if (previous != socket) {
            closeQuietly(previous);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort shutdown of an obsolete renderer connection.
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() <= MAX_REQUEST_LINE_BYTES) {
            int next = input.read();
            if (next == -1) {
                throw new IOException("DLNA renderer closed HTTP request");
            }
            if (next == '\n') {
                return line.toString().replace("\r", "");
            }
            line.append((char) next);
        }
        throw new IOException("DLNA HTTP request line too long");
    }

    private static InetAddress routeAddress(InetAddress receiver) throws IOException {
        try (Socket routeProbe = new Socket()) {
            routeProbe.connect(new InetSocketAddress(receiver, 9), 1_000);
            return routeProbe.getLocalAddress();
        } catch (IOException ignored) {
            // UDP avoids an actual service dependency while still asking the network stack for the route.
            try (java.net.DatagramSocket routeProbe = new java.net.DatagramSocket()) {
                routeProbe.connect(receiver, 9);
                return routeProbe.getLocalAddress();
            }
        }
    }

    private static final class HttpRequest {
        private final String method;
        private final Resource resource;
        private final boolean hasRange;
        private final long rangeStart;

        private HttpRequest(String method, Resource resource, boolean hasRange, long rangeStart) {
            this.method = method;
            this.resource = resource;
            this.hasRange = hasRange;
            this.rangeStart = rangeStart;
        }
    }

    private enum Resource {
        AUDIO,
        ARTWORK
    }
}
