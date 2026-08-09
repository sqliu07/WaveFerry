package app.shengdu.audio.dacp;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal reverse-control HTTP server for DACP requests such as
 * {@code /ctrl-int/1/pause}. This stays standalone so higher-level Android
 * components can inject their own transport lifecycle and command callback.
 */
public final class DacpControlServer implements Closeable {
    private static final int DEFAULT_BACKLOG = 4;
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final int MAX_REQUEST_LINE_BYTES = 2048;
    private static final int MAX_HEADER_LINE_BYTES = 2048;
    private static final int MAX_HEADER_BYTES = 8192;
    private static final int MAX_HEADER_COUNT = 32;

    private final InetAddress bindAddress;
    private final int bindPort;
    private final InetAddress expectedPeerAddress;
    private final String activeRemoteToken;
    private final Listener listener;
    private final Object lifecycleLock = new Object();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Socket activeClientSocket;
    private boolean running;
    private int localPort;

    public DacpControlServer(InetAddress bindAddress, int bindPort, String activeRemoteToken, Listener listener) {
        this(bindAddress, bindPort, null, activeRemoteToken, listener);
    }

    public DacpControlServer(
            InetAddress bindAddress,
            int bindPort,
            InetAddress expectedPeerAddress,
            String activeRemoteToken,
            Listener listener) {
        if (bindPort < 0 || bindPort > 65535) {
            throw new IllegalArgumentException("bindPort out of range: " + bindPort);
        }
        String normalizedToken = Objects.requireNonNull(activeRemoteToken, "activeRemoteToken").trim();
        if (normalizedToken.isEmpty()) {
            throw new IllegalArgumentException("activeRemoteToken must not be empty");
        }
        this.bindAddress = bindAddress;
        this.bindPort = bindPort;
        this.expectedPeerAddress = expectedPeerAddress;
        this.activeRemoteToken = normalizedToken;
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public int start() throws IOException {
        synchronized (lifecycleLock) {
            if (running && serverSocket != null && !serverSocket.isClosed()) {
                return localPort;
            }

            ServerSocket created = new ServerSocket();
            created.setReuseAddress(true);
            if (bindAddress == null) {
                created.bind(new InetSocketAddress(bindPort), DEFAULT_BACKLOG);
            } else {
                created.bind(new InetSocketAddress(bindAddress, bindPort), DEFAULT_BACKLOG);
            }

            serverSocket = created;
            localPort = created.getLocalPort();
            running = true;
            Thread thread = new Thread(this::acceptLoop, "dacp-control-server");
            thread.setDaemon(true);
            acceptThread = thread;
            thread.start();
            return localPort;
        }
    }

    public boolean isRunning() {
        synchronized (lifecycleLock) {
            return running;
        }
    }

    public int localPort() {
        synchronized (lifecycleLock) {
            return localPort;
        }
    }

    public void stop() {
        ServerSocket socketToClose;
        Socket clientToClose;
        Thread threadToJoin;
        synchronized (lifecycleLock) {
            running = false;
            socketToClose = serverSocket;
            clientToClose = activeClientSocket;
            threadToJoin = acceptThread;
            serverSocket = null;
            acceptThread = null;
            activeClientSocket = null;
            localPort = 0;
        }

        closeQuietly(socketToClose);
        closeQuietly(clientToClose);
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(SOCKET_TIMEOUT_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void acceptLoop() {
        while (true) {
            ServerSocket currentServer;
            synchronized (lifecycleLock) {
                if (!running || serverSocket == null) {
                    return;
                }
                currentServer = serverSocket;
            }

            try (Socket socket = currentServer.accept()) {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                setActiveClientSocket(socket);
                handleConnection(socket);
            } catch (SocketException exception) {
                synchronized (lifecycleLock) {
                    if (!running || currentServer != serverSocket) {
                        return;
                    }
                }
            } catch (IOException ignored) {
                synchronized (lifecycleLock) {
                    if (!running) {
                        return;
                    }
                }
            } finally {
                clearActiveClientSocket();
            }
        }
    }

    private void handleConnection(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        try {
            Request request = readRequest(input);
            if (request == null) {
                return;
            }
            if (!isRunning()) {
                return;
            }
            dispatch(socket.getInetAddress(), request);
            writeResponse(output, 204, "No Content", null);
        } catch (HttpException exception) {
            writeResponse(output, exception.statusCode, exception.reasonPhrase, exception.extraHeaders);
        } catch (Exception exception) {
            writeResponse(output, 500, "Internal Server Error", null);
        }
    }

    private void dispatch(InetAddress peerAddress, Request request) throws Exception {
        if (!"GET".equals(request.method)) {
            throw new HttpException(405, "Method Not Allowed", Map.of("Allow", "GET"));
        }
        if (!"HTTP/1.1".equals(request.version) && !"HTTP/1.0".equals(request.version)) {
            throw new HttpException(505, "HTTP Version Not Supported");
        }

        String token = request.headers.get("active-remote");
        if (!activeRemoteToken.equals(token)) {
            throw new HttpException(403, "Forbidden");
        }
        if (expectedPeerAddress != null && !expectedPeerAddress.equals(peerAddress)) {
            throw new HttpException(403, "Forbidden");
        }

        Command command = Command.fromTarget(request.target);
        if (command == null) {
            throw new HttpException(404, "Not Found");
        }
        if (!isRunning()) {
            return;
        }

        listener.onCommand(command);
    }

    private Request readRequest(InputStream input) throws IOException {
        String requestLine = readAsciiLine(
                input,
                MAX_REQUEST_LINE_BYTES,
                414,
                "URI Too Long");
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            throw new HttpException(400, "Bad Request");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = requestLine.length() + 2;
        for (int count = 0; count < MAX_HEADER_COUNT; count++) {
            String line = readAsciiLine(
                    input,
                    MAX_HEADER_LINE_BYTES,
                    431,
                    "Request Header Fields Too Large");
            if (line == null) {
                throw new EOFException("Unexpected EOF while reading headers");
            }
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new HttpException(431, "Request Header Fields Too Large");
            }
            if (line.isEmpty()) {
                return new Request(parts[0], parts[1], parts[2], headers);
            }

            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new HttpException(400, "Bad Request");
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (name.isEmpty() || headers.containsKey(name)) {
                throw new HttpException(400, "Bad Request");
            }
            headers.put(name, value);
        }

        throw new HttpException(431, "Request Header Fields Too Large");
    }

    private static String readAsciiLine(
            InputStream input,
            int maxBytes,
            int tooLargeStatus,
            String tooLargeReason) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new EOFException("Unexpected EOF inside HTTP line");
            }
            if (value == '\r') {
                int next = input.read();
                if (next != '\n') {
                    throw new HttpException(400, "Bad Request");
                }
                return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (value == '\n' || value > 0x7f) {
                throw new HttpException(400, "Bad Request");
            }
            buffer.write(value);
            if (buffer.size() > maxBytes) {
                throw new HttpException(tooLargeStatus, tooLargeReason);
            }
        }
    }

    private static void writeResponse(
            OutputStream output,
            int statusCode,
            String reasonPhrase,
            Map<String, String> extraHeaders) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(statusCode).append(' ').append(reasonPhrase).append("\r\n");
        response.append("Content-Length: 0\r\n");
        response.append("Connection: close\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                response.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        response.append("\r\n");
        output.write(response.toString().getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void setActiveClientSocket(Socket socket) {
        synchronized (lifecycleLock) {
            if (!running) {
                closeQuietly(socket);
                return;
            }
            activeClientSocket = socket;
        }
    }

    private void clearActiveClientSocket() {
        synchronized (lifecycleLock) {
            activeClientSocket = null;
        }
    }

    public enum Command {
        PLAY("/ctrl-int/1/play"),
        PAUSE("/ctrl-int/1/pause"),
        PLAY_PAUSE("/ctrl-int/1/playpause"),
        NEXT_ITEM("/ctrl-int/1/nextitem"),
        PREVIOUS_ITEM("/ctrl-int/1/previtem");

        private final String path;

        Command(String path) {
            this.path = path;
        }

        static Command fromTarget(String target) {
            String pathOnly = target;
            int queryIndex = pathOnly.indexOf('?');
            if (queryIndex >= 0) {
                pathOnly = pathOnly.substring(0, queryIndex);
            }
            for (Command command : values()) {
                if (command.path.equals(pathOnly)) {
                    return command;
                }
            }
            return null;
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onCommand(Command command) throws Exception;
    }

    private static final class Request {
        final String method;
        final String target;
        final String version;
        final Map<String, String> headers;

        Request(String method, String target, String version, Map<String, String> headers) {
            this.method = method;
            this.target = target;
            this.version = version;
            this.headers = headers;
        }
    }

    private static final class HttpException extends IOException {
        final int statusCode;
        final String reasonPhrase;
        final Map<String, String> extraHeaders;

        HttpException(int statusCode, String reasonPhrase) {
            this(statusCode, reasonPhrase, null);
        }

        HttpException(int statusCode, String reasonPhrase, Map<String, String> extraHeaders) {
            super(statusCode + " " + reasonPhrase);
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.extraHeaders = extraHeaders;
        }
    }
}
