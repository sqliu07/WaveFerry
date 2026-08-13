/* SPDX-License-Identifier: LGPL-2.1-or-later */
package app.shengdu.audio.raop;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RaopStreamingClient implements Closeable {
    private static final AudioStreamFormat DEFAULT_FORMAT =
            new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE);
    private static final String TRANSPORT_TCP = "RTP/AVP/TCP;unicast;interleaved=0-1;mode=record";
    private static final String TRANSPORT_UDP_PREFIX =
            "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=";
    private static final int AUDIO_CONNECT_TIMEOUT_MS = 5000;
    private static final int AUDIO_SO_TIMEOUT_MS = 5000;
    private static final int UDP_PACKET_BUFFER_SIZE = 2048;
    private static final int UDP_RETRANSMIT_CACHE_PACKETS = 512;
    private static final int UDP_MAX_RETRANSMIT_REQUEST_PACKETS = 512;
    private static final int UDP_THREAD_JOIN_TIMEOUT_MS = 2000;

    private final InetAddress host;
    private final int port;
    private final TxtInfo txtInfo;
    private final SecureRandom random;
    private final String dacpId;
    private final String activeRemote;
    private final AudioStreamFormat format;
    private final byte[] partialPcm = new byte[AlacPacketBuilder.PCM_BYTES_PER_TCP_PACKET];

    private int partialPcmLength;
    private volatile Session session;
    private volatile Session closingSession;

    public RaopStreamingClient(InetAddress host, int port, Map<String, byte[]> txtAttributes) {
        this(host, port, txtAttributes, new SecureRandom(), null, null, DEFAULT_FORMAT);
    }

    public RaopStreamingClient(
            InetAddress host,
            int port,
            Map<String, byte[]> txtAttributes,
            AudioStreamFormat format) {
        this(host, port, txtAttributes, new SecureRandom(), null, null, format);
    }

    public RaopStreamingClient(
            InetAddress host,
            int port,
            Map<String, byte[]> txtAttributes,
            String dacpId,
            String activeRemote) {
        this(host, port, txtAttributes, new SecureRandom(), dacpId, activeRemote, DEFAULT_FORMAT);
    }

    public RaopStreamingClient(
            InetAddress host,
            int port,
            Map<String, byte[]> txtAttributes,
            String dacpId,
            String activeRemote,
            AudioStreamFormat format) {
        this(host, port, txtAttributes, new SecureRandom(), dacpId, activeRemote, format);
    }

    RaopStreamingClient(
            InetAddress host,
            int port,
            Map<String, byte[]> txtAttributes,
            SecureRandom random) {
        this(host, port, txtAttributes, random, null, null, DEFAULT_FORMAT);
    }

    RaopStreamingClient(
            InetAddress host,
            int port,
            Map<String, byte[]> txtAttributes,
            SecureRandom random,
            String dacpId,
            String activeRemote) {
        this(host, port, txtAttributes, random, dacpId, activeRemote, DEFAULT_FORMAT);
    }

    RaopStreamingClient(
            InetAddress host,
            int port,
            Map<String, byte[]> txtAttributes,
            SecureRandom random,
            String dacpId,
            String activeRemote,
            AudioStreamFormat format) {
        this.host = host;
        this.port = port;
        if (format == null || format.channelCount() != 2 || format.sampleFormat() != SampleFormat.PCM16_LE) {
            throw new IllegalArgumentException("RAOP currently supports PCM16 stereo only");
        }
        this.format = format;
        this.txtInfo = TxtInfo.from(txtAttributes, format);
        this.random = random;
        validateDacpCredentials(dacpId, activeRemote);
        this.dacpId = dacpId;
        this.activeRemote = activeRemote;
    }

    public synchronized RaopCompatibility compatibility() {
        return txtInfo.compatibility;
    }

    public synchronized String incompatibilityReason() {
        return txtInfo.failureReason;
    }

    /**
     * Whether the receiver is known to accept RAOP 1 text metadata (md bit 0). A missing
     * TXT {@code md} is UNKNOWN, not a refusal: callers may attempt one non-fatal probe.
     */
    public synchronized MetadataSupport textMetadataSupport() {
        return metadataSupport(txtInfo.mdPresent, txtInfo.mdText);
    }

    /** Whether the receiver accepts artwork metadata (md bit 1); same UNKNOWN semantics. */
    public synchronized MetadataSupport artworkSupport() {
        return metadataSupport(txtInfo.mdPresent, txtInfo.mdArtwork);
    }

    /** Whether the receiver accepts progress metadata (md bit 2); same UNKNOWN semantics. */
    public synchronized MetadataSupport progressSupport() {
        return metadataSupport(txtInfo.mdPresent, txtInfo.mdProgress);
    }

    private static MetadataSupport metadataSupport(boolean mdPresent, boolean advertised) {
        if (!mdPresent) {
            return MetadataSupport.UNKNOWN;
        }
        return advertised ? MetadataSupport.ADVERTISED : MetadataSupport.NOT_ADVERTISED;
    }

    /** Current RTP timestamp of the audio stream, or 0 when no session is connected. */
    public synchronized long currentRtpTimestamp() {
        Session current = session;
        return current == null ? 0 : current.timestamp;
    }

    public int sampleRateHz() {
        return format.sampleRateHz();
    }

    /**
     * Sends track text metadata as a DMAP-tagged SET_PARAMETER. A failure is reported to
     * the caller as IOException but never aborts the audio session; the caller decides to
     * stop sending metadata.
     */
    public synchronized void sendTrackMetadata(String title, String artist, String album) throws IOException {
        Session current = session;
        if (current == null) {
            throw new IllegalStateException("RAOP session is not connected");
        }
        current.rtsp.setParameterDmap(
                current.uri,
                current.sessionId,
                current.timestamp,
                DmapEncoder.encodeTextMetadata(title, artist, album));
    }

    /**
     * Sends track artwork as a JPEG SET_PARAMETER. Same failure semantics as
     * {@link #sendTrackMetadata}: never aborts the audio session.
     */
    public synchronized void sendTrackArtwork(byte[] jpeg) throws IOException {
        Session current = session;
        if (current == null) {
            throw new IllegalStateException("RAOP session is not connected");
        }
        current.rtsp.setParameterArtwork(current.uri, current.sessionId, current.timestamp, jpeg);
    }

    /**
     * Sends playback progress as {@code progress: start/curr/end} RTP timestamps. Same
     * failure semantics as {@link #sendTrackMetadata}: never aborts the audio session.
     */
    public synchronized void sendProgress(long start, long curr, long end) throws IOException {
        Session current = session;
        if (current == null) {
            throw new IllegalStateException("RAOP session is not connected");
        }
        current.rtsp.setParameterProgress(current.uri, current.sessionId, start, curr, end);
    }

    public synchronized void flushAudio() throws IOException {
        Session current = session;
        if (current == null) {
            throw new IllegalStateException("RAOP session is not connected");
        }
        partialPcmLength = 0;
        current.resetAfterFlush();
        current.rtsp.flush(current.uri, current.sessionId, current.sequenceNumber, current.timestamp);
    }

    public synchronized void connect() throws IOException {
        if (session != null) {
            return;
        }
        if (!txtInfo.supported()) {
            throw new RaopException.UnsupportedReceiverException(txtInfo.failureReason, txtInfo.compatibility);
        }

        RtspClient rtsp = new RtspClient(host, port);
        Socket audioSocket = null;
        DatagramSocket udpAudioSocket = null;
        DatagramSocket udpControlSocket = null;
        DatagramSocket udpTimingSocket = null;
        Session openedSession = null;
        try {
            rtsp.connect();
            rtsp.options(randomHex(16), randomBase64NoPadding(16), dacpId, activeRemote);

            String streamId = Long.toUnsignedString(random.nextInt() & 0xffffffffL);
            String uri = buildRtspUri(rtsp.localAddress(), streamId);
            RaopCrypto crypto = txtInfo.encryption == EncryptionMode.RSA ? new RaopCrypto(random) : null;
            rtsp.announce(uri, buildSdp(rtsp.localAddress(), streamId, crypto));

            String transportHeader;
            if (txtInfo.transport == TransportMode.TCP) {
                transportHeader = TRANSPORT_TCP;
            } else {
                udpControlSocket = openUdpSocket(rtsp.localAddress());
                udpTimingSocket = openUdpSocket(rtsp.localAddress());
                transportHeader = buildUdpTransport(
                        udpControlSocket.getLocalPort(), udpTimingSocket.getLocalPort());
            }
            RtspClient.RtspResponse setup = rtsp.setup(uri, transportHeader);

            String sessionId = parseSessionId(setup.header("session"));
            int initialSequence = random.nextInt(0x10000);
            long initialTimestamp = Integer.toUnsignedLong(random.nextInt());
            long ssrc = Integer.toUnsignedLong(random.nextInt());

            if (txtInfo.transport == TransportMode.TCP) {
                int audioPort = parseRequiredPort(
                        setup.header("transport"),
                        "server_port",
                        "legacy RAOP TCP server_port");
                audioSocket = new Socket();
                audioSocket.connect(new InetSocketAddress(host, audioPort), AUDIO_CONNECT_TIMEOUT_MS);
                audioSocket.setSoTimeout(AUDIO_SO_TIMEOUT_MS);
                audioSocket.setTcpNoDelay(true);
                openedSession = Session.forTcp(
                        rtsp,
                        audioSocket,
                        audioSocket.getOutputStream(),
                        uri,
                        sessionId,
                        crypto,
                        initialSequence,
                        initialTimestamp,
                        ssrc,
                        format);
            } else {
                String setupTransport = setup.header("transport");
                int audioPort = parseRequiredPort(setupTransport, "server_port", "legacy RAOP UDP server_port");
                int controlPort = parseRequiredPort(setupTransport, "control_port", "legacy RAOP UDP control_port");
                int timingPort = parseRequiredPort(setupTransport, "timing_port", "legacy RAOP UDP timing_port");

                udpAudioSocket = openUdpSocket(rtsp.localAddress());
                connectUdpSocket(udpAudioSocket, audioPort);
                connectUdpSocket(udpControlSocket, controlPort);
                connectUdpSocket(udpTimingSocket, timingPort);

                openedSession = Session.forUdp(
                        rtsp,
                        uri,
                        sessionId,
                        crypto,
                        initialSequence,
                        initialTimestamp,
                        ssrc,
                        udpAudioSocket,
                        udpControlSocket,
                        udpTimingSocket,
                        format);
                sendDatagram(udpTimingSocket, RtpPacketizer.buildInitialUdpTimingPacket());
            }

            rtsp.record(uri, sessionId, initialSequence, initialTimestamp);
            this.session = openedSession;
            startUdpWorkers(openedSession);
        } catch (IOException | RuntimeException | GeneralSecurityException exception) {
            this.session = null;
            if (openedSession != null) {
                closeSessionTransports(openedSession, null);
            }
            closeQuietly(audioSocket);
            closeQuietly(udpAudioSocket);
            closeQuietly(udpControlSocket);
            closeQuietly(udpTimingSocket);
            try {
                rtsp.close();
            } catch (IOException ignored) {
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to open RAOP session", exception);
        }
    }

    public synchronized void writePcm(byte[] data, int offset, int length) throws IOException {
        checkRange(data.length, offset, length);
        throwIfUdpWorkerFailed();
        if (length == 0) {
            return;
        }
        if (session == null) {
            throw new IllegalStateException("RAOP session is not connected");
        }

        int cursor = offset;
        int remaining = length;
        int packetBytes = session.pcmBytesPerPacket;
        while (remaining > 0) {
            if (partialPcmLength > 0 || remaining < packetBytes) {
                int copy = Math.min(packetBytes - partialPcmLength, remaining);
                System.arraycopy(data, cursor, partialPcm, partialPcmLength, copy);
                partialPcmLength += copy;
                cursor += copy;
                remaining -= copy;

                if (partialPcmLength == packetBytes) {
                    sendPacket(partialPcm, 0);
                    partialPcmLength = 0;
                }
                continue;
            }

            sendPacket(data, cursor);
            cursor += packetBytes;
            remaining -= packetBytes;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        partialPcmLength = 0;
        Session current = session;
        if (current == null) {
            return;
        }
        closingSession = current;
        session = null;

        try {
            IOException failure = null;
            try {
                current.rtsp.flush(current.uri, current.sessionId, current.sequenceNumber, current.timestamp);
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                current.rtsp.teardown(current.uri, current.sessionId);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
            }
            failure = closeSessionTransports(current, failure);
            try {
                current.rtsp.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            if (closingSession == current) {
                closingSession = null;
            }
        }
    }

    /**
     * Immediately closes the live transport without acquiring the client monitor. This is the
     * escape hatch for a sender thread blocked inside a synchronized socket write; normal session
     * shutdown should continue to use {@link #close()} so FLUSH and TEARDOWN are attempted.
     */
    public void abort() {
        Session current = session;
        if (current == null) {
            current = closingSession;
        }
        session = null;
        if (current == null || !current.beginAbort()) {
            return;
        }
        current.stopUdpWorkers();
        closeQuietly(current.audioSocket);
        closeQuietly(current.udpAudioSocket);
        closeQuietly(current.udpControlSocket);
        closeQuietly(current.udpTimingSocket);
        current.rtsp.abort();
    }

    private void sendPacket(byte[] pcm, int offset) throws IOException {
        throwIfUdpWorkerFailed();
        Session current = session;
        if (current == null) {
            throw new IllegalStateException("RAOP session closed");
        }

        try {
            byte[] payload = AlacPacketBuilder.encodeUncompressedFrames(
                    current.format, pcm, offset, current.pcmBytesPerPacket);
            if (current.transport == TransportMode.TCP) {
                byte[] packet = RtpPacketizer.buildTcpPacket(
                        current.sequenceNumber,
                        current.timestamp,
                        current.ssrc,
                        payload,
                        current.crypto);
                current.audioOutput.write(packet);
                current.audioOutput.flush();
            } else {
                maybeSendUdpSync(current);
                byte[] packet = RtpPacketizer.buildUdpAudioPacket(
                        current.sequenceNumber,
                        current.timestamp,
                        current.ssrc,
                        payload,
                        current.crypto,
                        current.firstPacket);
                sendDatagram(current.udpAudioSocket, packet);
                current.retransmitCache.store(current.sequenceNumber, packet);
                current.firstPacket = false;
            }
            current.sequenceNumber = nextSequence(current.sequenceNumber);
            current.timestamp = (current.timestamp + current.framesPerPacket) & 0xffffffffL;
        } catch (GeneralSecurityException exception) {
            safeAbortCurrentSession();
            throw new IOException("Failed to encrypt RAOP audio packet", exception);
        } catch (IOException exception) {
            safeAbortCurrentSession();
            throw exception;
        }
    }

    private void safeAbortCurrentSession() {
        Session current = session;
        session = null;
        partialPcmLength = 0;
        if (current == null) {
            return;
        }
        closeSessionTransports(current, null);
        try {
            current.rtsp.close();
        } catch (IOException ignored) {
        }
    }

    private void throwIfUdpWorkerFailed() throws IOException {
        Session current = session;
        if (current == null || current.transport != TransportMode.UDP) {
            return;
        }
        IOException failure = current.udpWorkerFailure;
        if (failure == null) {
            return;
        }
        safeAbortCurrentSession();
        throw new IOException("RAOP UDP control or timing channel failed", failure);
    }

    private String buildSdp(InetAddress localAddress, String streamId, RaopCrypto crypto)
            throws GeneralSecurityException {
        int ipVersion = localAddress instanceof Inet6Address ? 6 : 4;
        String localIp = localAddress.getHostAddress();
        StringBuilder sdp = new StringBuilder();
        sdp.append("v=0\r\n");
        sdp.append("o=iTunes ").append(streamId).append(" 0 IN IP").append(ipVersion).append(' ').append(localIp)
                .append("\r\n");
        sdp.append("s=iTunes\r\n");
        sdp.append("c=IN IP").append(ipVersion).append(' ').append(host.getHostAddress()).append("\r\n");
        sdp.append("t=0 0\r\n");
        sdp.append("m=audio 0 RTP/AVP 96\r\n");
        sdp.append("a=rtpmap:96 AppleLossless\r\n");
        sdp.append("a=fmtp:96 ")
                .append(txtInfo.transport.framesPerPacket)
                .append(" 0 ")
                .append(format.sampleFormat().bitsPerSample())
                .append(" 40 10 14 ")
                .append(format.channelCount())
                .append(" 255 0 0 ")
                .append(format.sampleRateHz())
                .append("\r\n");
        if (crypto != null) {
            sdp.append("a=rsaaeskey:").append(crypto.encryptedKeyBase64(random)).append("\r\n");
            sdp.append("a=aesiv:").append(crypto.aesIvBase64()).append("\r\n");
        }
        return sdp.toString();
    }

    private static String buildRtspUri(InetAddress localAddress, String streamId) {
        String hostPart = localAddress.getHostAddress();
        if (localAddress instanceof Inet6Address) {
            return "rtsp://[" + hostPart + "]/" + streamId;
        }
        return "rtsp://" + hostPart + "/" + streamId;
    }

    private static String parseSessionId(String sessionHeader) throws IOException {
        if (sessionHeader == null || sessionHeader.isEmpty()) {
            throw new IOException("RTSP SETUP response missing Session header");
        }
        int separator = sessionHeader.indexOf(';');
        String sessionId = separator >= 0 ? sessionHeader.substring(0, separator).trim() : sessionHeader.trim();
        if (sessionId.isEmpty()) {
            throw new IOException("RTSP SETUP response contained an empty Session header");
        }
        return sessionId;
    }

    private static int parseRequiredPort(String transportHeader, String key, String description) throws IOException {
        if (transportHeader == null || transportHeader.isEmpty()) {
            throw new IOException("RTSP SETUP response missing Transport header");
        }
        String prefix = key.toLowerCase(Locale.ROOT) + "=";
        for (String part : transportHeader.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            String value = trimmed.substring(key.length() + 1);
            int dash = value.indexOf('-');
            if (dash >= 0) {
                value = value.substring(0, dash);
            }
            if (value.isEmpty()) {
                throw new IOException("Invalid RTSP " + key + ": " + trimmed);
            }
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0 || parsed > 65535) {
                    throw new IOException("Invalid RTSP " + key + ": " + trimmed);
                }
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid RTSP " + key + ": " + trimmed, exception);
            }
        }
        throw new IOException("Receiver did not return " + description + " in SETUP");
    }

    private String randomHex(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            value.append(String.format(Locale.US, "%02x", current & 0xff));
        }
        return value.toString();
    }

    private String randomBase64NoPadding(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void validateDacpCredentials(String dacpId, String activeRemote) {
        if (dacpId == null && activeRemote == null) {
            return;
        }
        if (dacpId == null || activeRemote == null) {
            throw new IllegalArgumentException("DACP-ID and Active-Remote must be supplied together");
        }
        if (dacpId.length() != 16) {
            throw new IllegalArgumentException("DACP-ID must contain 16 hexadecimal characters");
        }
        for (int index = 0; index < dacpId.length(); index++) {
            if (Character.digit(dacpId.charAt(index), 16) < 0) {
                throw new IllegalArgumentException("DACP-ID must contain 16 hexadecimal characters");
            }
        }
        if (activeRemote.isEmpty() || activeRemote.length() > 10) {
            throw new IllegalArgumentException("Active-Remote must be an unsigned 32-bit decimal value");
        }
        for (int index = 0; index < activeRemote.length(); index++) {
            char digit = activeRemote.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("Active-Remote must be an unsigned 32-bit decimal value");
            }
        }
        try {
            long value = Long.parseLong(activeRemote);
            if (value > 0xffff_ffffL) {
                throw new IllegalArgumentException("Active-Remote must be an unsigned 32-bit decimal value");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Active-Remote must be an unsigned 32-bit decimal value", exception);
        }
    }

    private static int nextSequence(int currentSequence) {
        return currentSequence == 0xffff ? 0 : currentSequence + 1;
    }

    private static void checkRange(int arrayLength, int offset, int length) {
        if (offset < 0 || length < 0 || offset > arrayLength || length > arrayLength - offset) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", arrayLength=" + arrayLength);
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

    private static void closeQuietly(DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }

    private static DatagramSocket openUdpSocket(InetAddress localAddress) throws IOException {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(localAddress, 0));
        try {
            socket.setSoTimeout(AUDIO_SO_TIMEOUT_MS);
            return socket;
        } catch (SocketException exception) {
            socket.close();
            throw exception;
        }
    }

    private void connectUdpSocket(DatagramSocket socket, int remotePort) throws SocketException {
        socket.connect(new InetSocketAddress(host, remotePort));
    }

    private static String buildUdpTransport(int controlPort, int timingPort) {
        return TRANSPORT_UDP_PREFIX + controlPort + ";timing_port=" + timingPort;
    }

    private static void sendDatagram(DatagramSocket socket, byte[] packet) throws IOException {
        socket.send(new DatagramPacket(packet, packet.length));
    }

    private static IOException closeSessionTransports(Session session, IOException failure) {
        session.stopUdpWorkers();
        if (session.audioSocket != null) {
            try {
                session.audioSocket.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
            }
        }
        closeQuietly(session.udpAudioSocket);
        closeQuietly(session.udpControlSocket);
        closeQuietly(session.udpTimingSocket);
        if (!session.joinUdpWorkers() && failure == null) {
            failure = new IOException("RAOP UDP worker did not stop cleanly");
        }
        return failure;
    }

    private static void maybeSendUdpSync(Session session) throws IOException {
        if (session.transport != TransportMode.UDP) {
            return;
        }
        session.udpSyncCount++;
        if (session.firstPacket || session.udpSyncCount >= session.udpSyncInterval) {
            sendDatagram(
                    session.udpControlSocket,
                    RtpPacketizer.buildUdpSyncPacket(
                            session.timestamp,
                            session.firstPacket,
                            session.format.sampleRateHz()));
            session.udpSyncCount = 0;
        }
    }

    private static void startUdpWorkers(Session session) {
        if (session.transport != TransportMode.UDP) {
            return;
        }
        session.udpRunning = true;
        session.controlWorker = new Thread(() -> runUdpControlLoop(session), "raop-udp-control");
        session.timingWorker = new Thread(() -> runUdpTimingLoop(session), "raop-udp-timing");
        session.controlWorker.setDaemon(true);
        session.timingWorker.setDaemon(true);
        session.controlWorker.start();
        session.timingWorker.start();
    }

    private static void runUdpControlLoop(Session session) {
        byte[] buffer = new byte[UDP_PACKET_BUFFER_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (session.udpRunning) {
            try {
                session.udpControlSocket.receive(datagram);
                handleUdpControlPacket(session, datagram.getData(), datagram.getLength());
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException exception) {
                if (session.udpRunning) {
                    session.udpWorkerFailure = exception;
                }
                return;
            } catch (IOException exception) {
                session.udpWorkerFailure = exception;
            }
        }
    }

    private static void runUdpTimingLoop(Session session) {
        byte[] buffer = new byte[UDP_PACKET_BUFFER_SIZE];
        DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
        while (session.udpRunning) {
            try {
                session.udpTimingSocket.receive(datagram);
                handleUdpTimingPacket(session, datagram.getData(), datagram.getLength());
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException exception) {
                if (session.udpRunning) {
                    session.udpWorkerFailure = exception;
                }
                return;
            } catch (IOException exception) {
                session.udpWorkerFailure = exception;
            }
        }
    }

    private static void handleUdpControlPacket(Session session, byte[] packet, int length) throws IOException {
        if (length != 8 || packet[0] != (byte) 0x80 || (packet[1] & 0x7f) != 0x55) {
            return;
        }
        int sequenceNumber = ((packet[4] & 0xff) << 8) | (packet[5] & 0xff);
        int packetCount = ((packet[6] & 0xff) << 8) | (packet[7] & 0xff);
        if (packetCount <= 0) {
            return;
        }
        int boundedPacketCount = Math.min(packetCount, UDP_MAX_RETRANSMIT_REQUEST_PACKETS);
        for (int index = 0; index < boundedPacketCount; index++) {
            int requestedSequence = (sequenceNumber + index) & 0xffff;
            byte[] audioPacket = session.retransmitCache.get(requestedSequence);
            if (audioPacket == null) {
                continue;
            }
            sendDatagram(
                    session.udpControlSocket,
                    RtpPacketizer.buildUdpRetransmitReply(requestedSequence, audioPacket));
        }
    }

    private static void handleUdpTimingPacket(Session session, byte[] packet, int length) throws IOException {
        if (length != 32 || packet[0] != (byte) 0x80 || (packet[1] & 0x7f) != 0x52) {
            return;
        }
        long receiveNtp = RtpPacketizer.currentNtpTimestamp();
        byte[] reply = RtpPacketizer.buildUdpTimingReply(
                Arrays.copyOf(packet, length),
                receiveNtp,
                RtpPacketizer.currentNtpTimestamp());
        sendDatagram(session.udpTimingSocket, reply);
    }

    private enum EncryptionMode {
        NONE,
        RSA
    }

    private enum TransportMode {
        TCP(AlacPacketBuilder.FRAMES_PER_TCP_PACKET),
        UDP(AlacPacketBuilder.FRAMES_PER_UDP_PACKET);

        final int framesPerPacket;
        final int pcmBytesPerPacket;

        TransportMode(int framesPerPacket) {
            this.framesPerPacket = framesPerPacket;
            this.pcmBytesPerPacket = framesPerPacket * AlacPacketBuilder.PCM_BYTES_PER_FRAME;
        }
    }

    private static final class TxtInfo {
        final RaopCompatibility compatibility;
        final TransportMode transport;
        final EncryptionMode encryption;
        final String failureReason;
        final boolean mdPresent;
        final boolean mdText;
        final boolean mdArtwork;
        final boolean mdProgress;

        private TxtInfo(
                RaopCompatibility compatibility,
                TransportMode transport,
                EncryptionMode encryption,
                String failureReason,
                boolean mdPresent,
                boolean mdText,
                boolean mdArtwork,
                boolean mdProgress) {
            this.compatibility = compatibility;
            this.transport = transport;
            this.encryption = encryption;
            this.failureReason = failureReason;
            this.mdPresent = mdPresent;
            this.mdText = mdText;
            this.mdArtwork = mdArtwork;
            this.mdProgress = mdProgress;
        }

        boolean supported() {
            return failureReason == null;
        }

        static TxtInfo from(Map<String, byte[]> txt) {
            return from(txt, DEFAULT_FORMAT);
        }

        static TxtInfo from(Map<String, byte[]> txt, AudioStreamFormat format) {
            if (txt == null) {
                return supported(
                        RaopCompatibility.LEGACY_CLEAR,
                        TransportMode.TCP,
                        EncryptionMode.NONE,
                        false,
                        false,
                        false,
                        false);
            }

            // md bits: 0 = text, 1 = artwork, 2 = progress. Missing md is recorded as
            // "not present" (unknown), which the sender treats as probe-once per kind
            // rather than as a refusal.
            Set<String> metadataTokens = tokens(findAttribute(txt, "md"), false);
            boolean mdPresent = !metadataTokens.isEmpty();
            boolean mdText = metadataTokens.contains("0");
            boolean mdArtwork = metadataTokens.contains("1");
            boolean mdProgress = metadataTokens.contains("2");

            TransportMode transport = selectTransport(txt);
            if (transport == null) {
                return unsupported(
                        RaopCompatibility.UNSUPPORTED_LEGACY_CAPABILITIES,
                        "Receiver TXT tp does not advertise legacy RAOP TCP or UDP transport");
            }

            TxtInfo capabilityFailure = requireCapability(txt, "cn", "ALAC codec 1", token -> "1".equals(token));
            if (capabilityFailure != null) {
                return capabilityFailure;
            }
            String sampleRate = Integer.toString(format.sampleRateHz());
            String sampleSize = Integer.toString(format.sampleFormat().bitsPerSample());
            String channels = Integer.toString(format.channelCount());
            capabilityFailure = requireCapability(
                    txt, "sr", sampleRate + " Hz sample rate", sampleRate::equals);
            if (capabilityFailure != null) {
                return capabilityFailure;
            }
            capabilityFailure = requireCapability(
                    txt, "ss", sampleSize + "-bit audio", sampleSize::equals);
            if (capabilityFailure != null) {
                return capabilityFailure;
            }
            capabilityFailure = requireCapability(
                    txt, "ch", channels + "-channel audio", channels::equals);
            if (capabilityFailure != null) {
                return capabilityFailure;
            }

            Set<String> encryptionTokens = tokens(findAttribute(txt, "et"), false);
            if (encryptionTokens.isEmpty()) {
                return supported(
                        RaopCompatibility.LEGACY_CLEAR,
                        transport,
                        EncryptionMode.NONE,
                        mdPresent,
                        mdText,
                        mdArtwork,
                        mdProgress);
            }

            boolean has0 = encryptionTokens.contains("0");
            boolean has1 = encryptionTokens.contains("1");
            if (has1) {
                return supported(
                        has0 ? RaopCompatibility.LEGACY_CLEAR : RaopCompatibility.LEGACY_RSA,
                        transport,
                        has0 ? EncryptionMode.NONE : EncryptionMode.RSA,
                        mdPresent,
                        mdText,
                        mdArtwork,
                        mdProgress);
            }
            if (has0) {
                return supported(
                        RaopCompatibility.LEGACY_CLEAR,
                        transport,
                        EncryptionMode.NONE,
                        mdPresent,
                        mdText,
                        mdArtwork,
                        mdProgress);
            }
            if (encryptionTokens.size() == 1 && encryptionTokens.contains("4")) {
                return unsupported(
                        RaopCompatibility.UNSUPPORTED_AIRPLAY2_ONLY,
                        "Receiver advertises only FairPlay/AirPlay 2 (et=4) and does not support legacy RAOP");
            }
            return unsupported(
                    RaopCompatibility.UNSUPPORTED_ENCRYPTION,
                    "Receiver TXT et does not advertise legacy RAOP clear (0) or RSA (1) encryption");
        }

        private static TransportMode selectTransport(Map<String, byte[]> txt) {
            Set<String> tokens = tokens(findAttribute(txt, "tp"), true);
            if (tokens.isEmpty() || tokens.contains("TCP")) {
                return TransportMode.TCP;
            }
            if (tokens.contains("UDP")) {
                return TransportMode.UDP;
            }
            return null;
        }

        private static TxtInfo requireCapability(
                Map<String, byte[]> txt,
                String key,
                String expectedValue,
                TokenMatcher matcher) {
            Set<String> tokens = tokens(findAttribute(txt, key), true);
            if (tokens.isEmpty()) {
                return null;
            }
            for (String token : tokens) {
                if (matcher.matches(token)) {
                    return null;
                }
            }
            return unsupported(
                    RaopCompatibility.UNSUPPORTED_LEGACY_CAPABILITIES,
                    "Receiver TXT " + key + " does not advertise " + expectedValue + " required for legacy RAOP");
        }

        private static TxtInfo supported(
                RaopCompatibility compatibility,
                TransportMode transport,
                EncryptionMode encryption,
                boolean mdPresent,
                boolean mdText,
                boolean mdArtwork,
                boolean mdProgress) {
            return new TxtInfo(
                    compatibility, transport, encryption, null, mdPresent, mdText, mdArtwork, mdProgress);
        }

        private static TxtInfo unsupported(RaopCompatibility compatibility, String reason) {
            return new TxtInfo(
                    compatibility,
                    TransportMode.TCP,
                    EncryptionMode.NONE,
                    reason,
                    false,
                    false,
                    false,
                    false);
        }

        private static byte[] findAttribute(Map<String, byte[]> txt, String expectedKey) {
            for (Map.Entry<String, byte[]> entry : txt.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(expectedKey)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static Set<String> tokens(byte[] raw, boolean uppercase) {
            Set<String> values = new LinkedHashSet<>();
            if (raw == null || raw.length == 0) {
                return values;
            }
            String value = toAscii(raw);
            if (value.isEmpty()) {
                return values;
            }
            for (String token : value.split("[,\\s/]+")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                values.add(uppercase ? trimmed.toUpperCase(Locale.ROOT) : trimmed);
            }
            return values;
        }

        private static String toAscii(byte[] raw) {
            int end = 0;
            while (end < raw.length && raw[end] != 0) {
                end++;
            }
            return new String(Arrays.copyOf(raw, end), StandardCharsets.US_ASCII);
        }

        private interface TokenMatcher {
            boolean matches(String token);
        }
    }

    private static final class Session {
        final TransportMode transport;
        final RtspClient rtsp;
        final Socket audioSocket;
        final DatagramSocket udpAudioSocket;
        final DatagramSocket udpControlSocket;
        final DatagramSocket udpTimingSocket;
        final OutputStream audioOutput;
        final String uri;
        final String sessionId;
        final RaopCrypto crypto;
        final AudioStreamFormat format;
        final int framesPerPacket;
        final int pcmBytesPerPacket;
        final int udpSyncInterval;
        final UdpRetransmitCache retransmitCache;
        long timestamp;
        long ssrc;
        int sequenceNumber;
        int udpSyncCount;
        boolean firstPacket;
        volatile boolean udpRunning;
        volatile IOException udpWorkerFailure;
        private final AtomicBoolean abortStarted = new AtomicBoolean();
        Thread controlWorker;
        Thread timingWorker;

        private Session(
                TransportMode transport,
                RtspClient rtsp,
                Socket audioSocket,
                DatagramSocket udpAudioSocket,
                DatagramSocket udpControlSocket,
                DatagramSocket udpTimingSocket,
                OutputStream audioOutput,
                String uri,
                String sessionId,
                RaopCrypto crypto,
                int sequenceNumber,
                long timestamp,
                long ssrc,
                int framesPerPacket,
                AudioStreamFormat format) {
            this.transport = transport;
            this.rtsp = rtsp;
            this.audioSocket = audioSocket;
            this.udpAudioSocket = udpAudioSocket;
            this.udpControlSocket = udpControlSocket;
            this.udpTimingSocket = udpTimingSocket;
            this.audioOutput = audioOutput;
            this.uri = uri;
            this.sessionId = sessionId;
            this.crypto = crypto;
            this.format = format;
            this.sequenceNumber = sequenceNumber;
            this.timestamp = timestamp;
            this.ssrc = ssrc;
            this.framesPerPacket = framesPerPacket;
            this.pcmBytesPerPacket = framesPerPacket * format.bytesPerFrame();
            this.udpSyncInterval = Math.max(1, format.sampleRateHz() / framesPerPacket);
            this.retransmitCache = transport == TransportMode.UDP
                    ? new UdpRetransmitCache(UDP_RETRANSMIT_CACHE_PACKETS)
                    : null;
            this.firstPacket = true;
        }

        private static Session forTcp(
                RtspClient rtsp,
                Socket audioSocket,
                OutputStream audioOutput,
                String uri,
                String sessionId,
                RaopCrypto crypto,
                int sequenceNumber,
                long timestamp,
                long ssrc,
                AudioStreamFormat format) {
            return new Session(
                    TransportMode.TCP,
                    rtsp,
                    audioSocket,
                    null,
                    null,
                    null,
                    audioOutput,
                    uri,
                    sessionId,
                    crypto,
                    sequenceNumber,
                    timestamp,
                    ssrc,
                    TransportMode.TCP.framesPerPacket,
                    format);
        }

        private static Session forUdp(
                RtspClient rtsp,
                String uri,
                String sessionId,
                RaopCrypto crypto,
                int sequenceNumber,
                long timestamp,
                long ssrc,
                DatagramSocket udpAudioSocket,
                DatagramSocket udpControlSocket,
                DatagramSocket udpTimingSocket,
                AudioStreamFormat format) {
            return new Session(
                    TransportMode.UDP,
                    rtsp,
                    null,
                    udpAudioSocket,
                    udpControlSocket,
                    udpTimingSocket,
                    null,
                    uri,
                    sessionId,
                    crypto,
                    sequenceNumber,
                    timestamp,
                    ssrc,
                    TransportMode.UDP.framesPerPacket,
                    format);
        }

        private void stopUdpWorkers() {
            udpRunning = false;
        }

        private boolean beginAbort() {
            return abortStarted.compareAndSet(false, true);
        }

        private boolean joinUdpWorkers() {
            boolean controlStopped = joinQuietly(controlWorker);
            boolean timingStopped = joinQuietly(timingWorker);
            return controlStopped && timingStopped;
        }

        private void resetAfterFlush() {
            if (transport != TransportMode.UDP) {
                return;
            }
            retransmitCache.clear();
            udpSyncCount = 0;
            firstPacket = true;
        }

        private static boolean joinQuietly(Thread thread) {
            if (thread == null) {
                return true;
            }
            try {
                thread.join(UDP_THREAD_JOIN_TIMEOUT_MS);
                if (thread.isAlive()) {
                    thread.interrupt();
                    thread.join(100);
                }
                return !thread.isAlive();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static final class UdpRetransmitCache {
        private final int maxEntries;
        private final ArrayDeque<Integer> order = new ArrayDeque<>();
        private final Map<Integer, byte[]> packets = new HashMap<>();

        private UdpRetransmitCache(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        synchronized void store(int sequenceNumber, byte[] audioPacket) {
            int key = sequenceNumber & 0xffff;
            if (packets.containsKey(key)) {
                order.remove(key);
            }
            packets.put(key, audioPacket.clone());
            order.addLast(key);
            while (order.size() > maxEntries) {
                Integer oldest = order.removeFirst();
                packets.remove(oldest);
            }
        }

        synchronized byte[] get(int sequenceNumber) {
            byte[] packet = packets.get(sequenceNumber & 0xffff);
            return packet == null ? null : packet.clone();
        }

        synchronized void clear() {
            order.clear();
            packets.clear();
        }
    }
}
