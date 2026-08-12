package app.shengdu.audio.dlna;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Minimal UPnP AVTransport controller for a single MediaRenderer instance. */
final class DlnaAvTransportClient {
    private static final int MAX_FAULT_RESPONSE_BYTES = 16 * 1024;

    private final URI controlUri;
    private final String serviceType;

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

    void seekTo(long positionMs) throws IOException {
        invokeAction("Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit>"
                + "<Target>" + DlnaLiveAudioMetadata.timeValue(positionMs) + "</Target>");
    }

    String transportState() throws IOException {
        return xmlElement(invoke("GetTransportInfo", "<InstanceID>0</InstanceID>", true),
                "CurrentTransportState");
    }

    static boolean isTransitionNotAvailable(IOException error) {
        return error instanceof ControlException
                && ((ControlException) error).upnpErrorCode == 701;
    }

    private void invokeAction(String action, String body) throws IOException {
        try {
            invoke(action, body, false);
        } catch (ControlException error) {
            throw error;
        } catch (IOException error) {
            String message = error.getMessage();
            throw new IOException("DLNA " + action + " failed: "
                    + (message == null || message.isBlank()
                    ? error.getClass().getSimpleName() : message), error);
        }
    }

    private String invoke(String action, String body, boolean readResponseBody) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) controlUri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "WaveFerry/0.2.0 UPnP/1.0");
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
        byte[] request = envelope(action, body).getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(request);
        }
        int responseCode = connection.getResponseCode();
        try {
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
        String openingTag = "<" + name + ">";
        String closingTag = "</" + name + ">";
        int start = source.indexOf(openingTag);
        int end = start < 0 ? -1 : source.indexOf(closingTag, start + openingTag.length());
        if (start < 0 || end < 0) {
            return "";
        }
        return source.substring(start + openingTag.length(), end).trim();
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
