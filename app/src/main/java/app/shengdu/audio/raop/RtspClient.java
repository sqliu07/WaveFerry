/* SPDX-License-Identifier: LGPL-2.1-or-later */
package app.shengdu.audio.raop;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class RtspClient implements Closeable {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SO_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final String USER_AGENT = "iTunes/11.0.4 (Windows; N)";

    private final InetAddress host;
    private final int port;
    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private int cseq = 1;
    private String clientInstance;
    private String dacpId;
    private String activeRemote;

    RtspClient(InetAddress host, int port) {
        this.host = host;
        this.port = port;
    }

    void connect() throws IOException {
        if (socket != null) {
            return;
        }

        Socket created = new Socket();
        created.connect(new InetSocketAddress(host, port), DEFAULT_CONNECT_TIMEOUT_MS);
        created.setSoTimeout(DEFAULT_SO_TIMEOUT_MS);
        this.socket = created;
        this.input = created.getInputStream();
        this.output = created.getOutputStream();
    }

    InetAddress localAddress() {
        return socket.getLocalAddress();
    }

    RtspResponse options(
            String clientInstance,
            String appleChallenge,
            String dacpId,
            String activeRemote) throws IOException {
        this.clientInstance = clientInstance;
        this.dacpId = dacpId;
        this.activeRemote = activeRemote;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Client-Instance", clientInstance);
        headers.put("Apple-Challenge", appleChallenge);
        return send("OPTIONS", "*", headers, null);
    }

    RtspResponse announce(String uri, String sdp) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/sdp");
        return send("ANNOUNCE", uri, headers, sdp.getBytes(StandardCharsets.US_ASCII));
    }

    RtspResponse setup(String uri, String transport) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Transport", transport);
        return send("SETUP", uri, headers, null);
    }

    RtspResponse record(String uri, String sessionId, int sequenceNumber, long timestamp) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        headers.put("Range", "npt=0-");
        headers.put("RTP-Info", "seq=" + sequenceNumber + ";rtptime=" + Long.toUnsignedString(timestamp));
        return send("RECORD", uri, headers, null);
    }

    RtspResponse setParameterVolume(String uri, String sessionId, double volumeDb) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        headers.put("Content-Type", "text/parameters");
        String body = "volume: " + volumeDb + "\r\n";
        return send("SET_PARAMETER", uri, headers, body.getBytes(StandardCharsets.US_ASCII));
    }

    RtspResponse setParameterDmap(String uri, String sessionId, long rtpTimestamp, byte[] dmapBody)
            throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        headers.put("Content-Type", "application/x-dmap-tagged");
        headers.put("RTP-Info", "rtptime=" + Long.toUnsignedString(rtpTimestamp));
        if (clientInstance != null) {
            headers.put("Client-Instance", clientInstance);
        }
        return send("SET_PARAMETER", uri, headers, dmapBody);
    }

    RtspResponse setParameterArtwork(String uri, String sessionId, long rtpTimestamp, byte[] jpeg)
            throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        headers.put("Content-Type", "image/jpeg");
        headers.put("RTP-Info", "rtptime=" + Long.toUnsignedString(rtpTimestamp));
        if (clientInstance != null) {
            headers.put("Client-Instance", clientInstance);
        }
        return send("SET_PARAMETER", uri, headers, jpeg);
    }

    RtspResponse setParameterProgress(String uri, String sessionId, long start, long curr, long end)
            throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        headers.put("Content-Type", "text/parameters");
        if (clientInstance != null) {
            headers.put("Client-Instance", clientInstance);
        }
        String body = "progress: "
                + Long.toUnsignedString(start) + "/"
                + Long.toUnsignedString(curr) + "/"
                + Long.toUnsignedString(end) + "\r\n";
        return send("SET_PARAMETER", uri, headers, body.getBytes(StandardCharsets.US_ASCII));
    }

    RtspResponse flush(String uri, String sessionId, int sequenceNumber, long timestamp) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        headers.put("RTP-Info", "seq=" + sequenceNumber + ";rtptime=" + Long.toUnsignedString(timestamp));
        return send("FLUSH", uri, headers, null);
    }

    RtspResponse teardown(String uri, String sessionId) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("Session", sessionId);
        return send("TEARDOWN", uri, headers, null);
    }

    private RtspResponse send(
            String method,
            String uri,
            LinkedHashMap<String, String> headers,
            byte[] body) throws IOException {
        ensureConnected();

        StringBuilder request = new StringBuilder();
        request.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        request.append("CSeq: ").append(cseq++).append("\r\n");
        request.append("User-Agent: ").append(USER_AGENT).append("\r\n");
        if (dacpId != null && activeRemote != null) {
            request.append("DACP-ID: ").append(dacpId).append("\r\n");
            request.append("Active-Remote: ").append(activeRemote).append("\r\n");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            request.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }
        if (body != null) {
            request.append("Content-Length: ").append(body.length).append("\r\n");
        }
        request.append("\r\n");

        output.write(request.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) {
            output.write(body);
        }
        output.flush();

        RtspResponse response = readResponse();
        if (response.statusCode == 401) {
            throw new RaopException.AuthenticationException("RAOP receiver requires a password (RTSP 401)");
        }
        if (response.statusCode == 403) {
            throw new RaopException.PairingException("RAOP receiver requires pairing (RTSP 403)");
        }
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new RaopException(
                    "RTSP " + method + " failed with status " + response.statusCode + " " + response.reasonPhrase);
        }
        return response;
    }

    private RtspResponse readResponse() throws IOException {
        String statusLine = readLineSkippingInterleaved();
        if (statusLine == null) {
            throw new EOFException("RTSP connection closed");
        }

        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("Malformed RTSP status line: " + statusLine);
        }

        int statusCode;
        try {
            statusCode = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IOException("Malformed RTSP status code: " + statusLine, exception);
        }

        String reason = parts.length >= 3 ? parts[2] : "";
        Map<String, String> headers = new LinkedHashMap<>();
        int bytesRead = statusLine.length() + 2;
        while (true) {
            String line = readLineSkippingInterleaved();
            if (line == null) {
                throw new EOFException("RTSP connection closed while reading headers");
            }
            bytesRead += line.length() + 2;
            if (bytesRead > MAX_RESPONSE_BYTES) {
                throw new IOException("RTSP response headers exceeded " + MAX_RESPONSE_BYTES + " bytes");
            }
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Malformed RTSP header: " + line);
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            headers.put(name, value);
        }

        int contentLength = 0;
        String lengthHeader = headers.get("content-length");
        if (lengthHeader != null && !lengthHeader.isEmpty()) {
            try {
                contentLength = Integer.parseInt(lengthHeader);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid Content-Length: " + lengthHeader, exception);
            }
        }
        if (contentLength < 0 || bytesRead + contentLength > MAX_RESPONSE_BYTES) {
            throw new IOException("RTSP response body exceeded bounds");
        }

        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = input.read(body, offset, contentLength - offset);
            if (read < 0) {
                throw new EOFException("RTSP connection closed while reading body");
            }
            offset += read;
        }
        return new RtspResponse(statusCode, reason, headers, body);
    }

    private String readLineSkippingInterleaved() throws IOException {
        while (true) {
            int first = input.read();
            if (first < 0) {
                return null;
            }
            if (first == 0x24) {
                skipInterleavedFrame();
                continue;
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                if (first == '\r') {
                    int next = input.read();
                    if (next == '\n') {
                        break;
                    }
                    if (next < 0) {
                        throw new EOFException("Unexpected EOF inside RTSP line");
                    }
                    buffer.write(first);
                    first = next;
                    continue;
                }

                buffer.write(first);
                if (buffer.size() > MAX_LINE_BYTES) {
                    throw new IOException("RTSP line exceeded " + MAX_LINE_BYTES + " bytes");
                }

                first = input.read();
                if (first < 0) {
                    throw new EOFException("Unexpected EOF inside RTSP line");
                }
            }
            return new String(buffer.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    private void skipInterleavedFrame() throws IOException {
        int channel = input.read();
        int sizeHigh = input.read();
        int sizeLow = input.read();
        if (channel < 0 || sizeHigh < 0 || sizeLow < 0) {
            throw new EOFException("Unexpected EOF in interleaved RTSP frame");
        }
        int size = (sizeHigh << 8) | sizeLow;
        int skipped = 0;
        while (skipped < size) {
            long count = input.skip(size - skipped);
            if (count <= 0) {
                if (input.read() < 0) {
                    throw new EOFException("Unexpected EOF in interleaved RTSP frame payload");
                }
                count = 1;
            }
            skipped += (int) count;
        }
    }

    private void ensureConnected() {
        if (socket == null || input == null || output == null) {
            throw new IllegalStateException("RTSP socket is not connected");
        }
    }

    @Override
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
            socket = null;
            input = null;
            output = null;
        }
    }

    static final class RtspResponse {
        final int statusCode;
        final String reasonPhrase;
        final Map<String, String> headers;
        final byte[] body;

        RtspResponse(int statusCode, String reasonPhrase, Map<String, String> headers, byte[] body) {
            this.statusCode = statusCode;
            this.reasonPhrase = reasonPhrase;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
            this.body = body.clone();
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }
}
