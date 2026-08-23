package app.shengdu.audio.dlna;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal UPnP AVTransport controller for a single MediaRenderer instance. */
final class DlnaAvTransportClient {
    private static final int MAX_FAULT_RESPONSE_BYTES = 16 * 1024;
    private static final int CONTROL_CONNECT_TIMEOUT_MS = 3_000;
    private static final int CONTROL_READ_TIMEOUT_MS = 5_000;
    private static final int OPTIONAL_CONNECT_TIMEOUT_MS = 750;
    private static final int OPTIONAL_READ_TIMEOUT_MS = 1_000;
    private static final int TEARDOWN_CONNECT_TIMEOUT_MS = 500;
    private static final int TEARDOWN_READ_TIMEOUT_MS = 750;

    private final URI controlUri;
    private final String serviceType;
    private final AtomicBoolean controlActionsClosed = new AtomicBoolean();
    private final AtomicLong controlRevision = new AtomicLong();
    private final AtomicReference<HttpURLConnection> pendingControlAction = new AtomicReference<>();
    private final AtomicLong seekRevision = new AtomicLong();
    private final AtomicReference<HttpURLConnection> pendingSeek = new AtomicReference<>();
    private final AtomicLong transportPollRevision = new AtomicLong();
    private final AtomicReference<HttpURLConnection> pendingTransportPoll = new AtomicReference<>();

    DlnaAvTransportClient(URI controlUri, String serviceType) {
        this.controlUri = Objects.requireNonNull(controlUri, "controlUri");
        this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
    }

    void setUri(URI mediaUri, String metadata) throws IOException {
        invokeAction("SetAVTransportURI", "<InstanceID>0</InstanceID>"
                + "<CurrentURI>" + xml(mediaUri.toString()) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + xml(metadata == null ? "" : metadata)
                + "</CurrentURIMetaData>");
    }

    void play() throws IOException {
        invokeAction("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>");
    }

    void pause() throws IOException {
        invokeAction("Pause", "<InstanceID>0</InstanceID>");
    }

    void stop() throws IOException {
        invokeAction("Stop", "<InstanceID>0</InstanceID>");
    }

    void stopForTeardown() throws IOException {
        invokeAction(
                "Stop",
                "<InstanceID>0</InstanceID>",
                TEARDOWN_CONNECT_TIMEOUT_MS,
                TEARDOWN_READ_TIMEOUT_MS,
                null,
                null);
    }

    void seekTo(long positionMs) throws IOException {
        seekTo(positionMs, currentSeekRevision());
    }

    void seekTo(long positionMs, long expectedSeekRevision) throws IOException {
        invokeAction(
                "Seek",
                "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>"
                        + "<Target>" + DlnaLiveAudioMetadata.timeValue(positionMs) + "</Target>",
                OPTIONAL_CONNECT_TIMEOUT_MS,
                OPTIONAL_READ_TIMEOUT_MS,
                pendingSeek,
                () -> seekRevision.get() == expectedSeekRevision);
    }

    long currentSeekRevision() {
        return seekRevision.get();
    }

    void cancelPendingSeek() {
        seekRevision.incrementAndGet();
        HttpURLConnection connection = pendingSeek.getAndSet(null);
        if (connection != null) {
            connection.disconnect();
        }
    }

    String transportState() throws IOException {
        long expectedRevision = controlRevision.get();
        return xmlElement(invoke(
                "GetTransportInfo",
                "<InstanceID>0</InstanceID>",
                true,
                CONTROL_CONNECT_TIMEOUT_MS,
                CONTROL_READ_TIMEOUT_MS,
                pendingControlAction,
                () -> !controlActionsClosed.get()
                        && controlRevision.get() == expectedRevision),
                "CurrentTransportState");
    }

    String transportStateForPolling() throws IOException {
        long expectedRevision = transportPollRevision.get();
        return xmlElement(invoke(
                "GetTransportInfo",
                "<InstanceID>0</InstanceID>",
                true,
                OPTIONAL_CONNECT_TIMEOUT_MS,
                OPTIONAL_READ_TIMEOUT_MS,
                pendingTransportPoll,
                () -> !controlActionsClosed.get()
                        && transportPollRevision.get() == expectedRevision),
                "CurrentTransportState");
    }

    void cancelPendingTransportPoll() {
        transportPollRevision.incrementAndGet();
        HttpURLConnection connection = pendingTransportPoll.getAndSet(null);
        if (connection != null) {
            connection.disconnect();
        }
    }

    void closeControlActions() {
        controlActionsClosed.set(true);
        controlRevision.incrementAndGet();
        HttpURLConnection connection = pendingControlAction.getAndSet(null);
        if (connection != null) {
            connection.disconnect();
        }
    }

    static boolean isTransitionNotAvailable(IOException error) {
        return error instanceof ControlException
                && ((ControlException) error).upnpErrorCode == 701;
    }

    static boolean isUpnpFault(IOException error) {
        return error instanceof ControlException
                && ((ControlException) error).upnpErrorCode != 0;
    }

    private void invokeAction(String action, String body) throws IOException {
        long expectedRevision = controlRevision.get();
        invokeAction(
                action,
                body,
                CONTROL_CONNECT_TIMEOUT_MS,
                CONTROL_READ_TIMEOUT_MS,
                pendingControlAction,
                () -> !controlActionsClosed.get()
                        && controlRevision.get() == expectedRevision);
    }

    private void invokeAction(
            String action,
            String body,
            int connectTimeoutMs,
            int readTimeoutMs,
            AtomicReference<HttpURLConnection> cancellationSlot,
            BooleanSupplier cancellationGuard) throws IOException {
        try {
            invoke(
                    action,
                    body,
                    false,
                    connectTimeoutMs,
                    readTimeoutMs,
                    cancellationSlot,
                    cancellationGuard);
        } catch (ControlException error) {
            throw error;
        } catch (IOException error) {
            String message = error.getMessage();
            throw new IOException("DLNA " + action + " failed: "
                    + (message == null || message.isBlank()
                    ? error.getClass().getSimpleName() : message), error);
        }
    }

    private String invoke(
            String action,
            String body,
            boolean readResponseBody,
            int connectTimeoutMs,
            int readTimeoutMs,
            AtomicReference<HttpURLConnection> cancellationSlot,
            BooleanSupplier cancellationGuard) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) controlUri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", DlnaProtocol.USER_AGENT);
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        byte[] request = envelope(action, body).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        if (cancellationSlot != null) {
            HttpURLConnection previous = cancellationSlot.getAndSet(connection);
            if (previous != null && previous != connection) {
                previous.disconnect();
            }
        }
        try {
            if (cancellationGuard != null && !cancellationGuard.getAsBoolean()) {
                throw new InterruptedIOException("DLNA action was cancelled");
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                FaultDetails fault = readFault(connection.getErrorStream());
                throw new ControlException(action, responseCode, fault);
            }
            if (readResponseBody) {
                try (InputStream input = connection.getInputStream()) {
                    return new String(readBounded(input), StandardCharsets.UTF_8);
                }
            }
            return "";
        } finally {
            if (cancellationSlot != null) {
                cancellationSlot.compareAndSet(connection, null);
            }
            connection.disconnect();
        }
    }

    private String envelope(String action, String body) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + serviceType + "\">"
                + body + "</u:" + action + "></s:Body></s:Envelope>";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static FaultDetails readFault(InputStream errorStream) {
        if (errorStream == null) {
            return new FaultDetails(0, "");
        }
        try (InputStream input = errorStream) {
            String fault = new String(readBounded(input), StandardCharsets.UTF_8);
            String errorCodeText = xmlElement(fault, "errorCode");
            String description = xmlElement(fault, "errorDescription");
            try {
                return new FaultDetails(Integer.parseInt(errorCodeText), description);
            } catch (NumberFormatException ignored) {
                return new FaultDetails(0, description);
            }
        } catch (IOException ignored) {
            return new FaultDetails(0, "");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1_024];
        while (output.size() < MAX_FAULT_RESPONSE_BYTES) {
            int maxRead = Math.min(buffer.length, MAX_FAULT_RESPONSE_BYTES - output.size());
            int read = input.read(buffer, 0, maxRead);
            if (read < 0) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String xmlElement(String source, String name) {
        String qualifiedName = "(?:[A-Za-z_][A-Za-z0-9_.-]*:)?" + Pattern.quote(name);
        Pattern element = Pattern.compile(
                "<" + qualifiedName + "(?:\\s[^>]*)?>(.*?)</" + qualifiedName + "\\s*>",
                Pattern.DOTALL);
        Matcher match = element.matcher(source);
        return match.find() ? match.group(1).trim() : "";
    }

    private static final class FaultDetails {
        private final int upnpErrorCode;
        private final String description;

        private FaultDetails(int upnpErrorCode, String description) {
            this.upnpErrorCode = upnpErrorCode;
            this.description = description;
        }
    }

    private static final class ControlException extends IOException {
        private final int upnpErrorCode;

        private ControlException(String action, int httpStatus, FaultDetails fault) {
            super("DLNA " + action + " failed: HTTP " + httpStatus
                    + (fault.upnpErrorCode == 0 && fault.description.isEmpty()
                    ? ""
                    : " (" + (fault.upnpErrorCode == 0 ? "" : fault.upnpErrorCode)
                    + (fault.description.isEmpty() ? "" : " " + fault.description) + ")"));
            this.upnpErrorCode = fault.upnpErrorCode;
        }
    }
}
