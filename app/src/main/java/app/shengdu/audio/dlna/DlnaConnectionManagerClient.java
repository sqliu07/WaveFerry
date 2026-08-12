package app.shengdu.audio.dlna;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Retrieves sink protocol capabilities from UPnP ConnectionManager. */
final class DlnaConnectionManagerClient {
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private final URI controlUri;
    private final String serviceType;

    DlnaConnectionManagerClient(URI controlUri, String serviceType) {
        this.controlUri = controlUri;
        this.serviceType = serviceType;
    }

    DlnaSinkCapabilities getSinkCapabilities() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) controlUri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(3_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#GetProtocolInfo\"");
        byte[] request = envelope().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(request);
        }
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("DLNA GetProtocolInfo failed: HTTP " + connection.getResponseCode());
            }
            try (InputStream input = connection.getInputStream()) {
                return DlnaSinkCapabilities.parse(extractSink(readBounded(input)));
            }
        } finally {
            connection.disconnect();
        }
    }

    private String envelope() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:GetProtocolInfo xmlns:u=\"" + serviceType + "\"/>"
                + "</s:Body></s:Envelope>";
    }

    private static String extractSink(String xml) {
        for (String tag : new String[] {"Sink", "SinkProtocolInfo"}) {
            String open = "<" + tag + ">";
            String close = "</" + tag + ">";
            int start = xml.indexOf(open);
            int end = start < 0 ? -1 : xml.indexOf(close, start + open.length());
            if (start >= 0 && end >= 0) {
                return xml.substring(start + open.length(), end)
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">");
            }
        }
        return "";
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw new IOException("DLNA GetProtocolInfo response too large");
            }
            output.write(buffer, 0, count);
        }
    }
}
