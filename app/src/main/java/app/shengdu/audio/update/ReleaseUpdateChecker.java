package app.shengdu.audio.update;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** One user-initiated request at a time; the caller supplies its own background executor. */
public final class ReleaseUpdateChecker implements AutoCloseable {
    private static final String API_URL = "https://api.github.com/repos/sqliu07/WaveFerry/releases/latest";
    private static final String RELEASE_URL_PREFIX = "https://github.com/sqliu07/WaveFerry/releases/tag/";
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 7_000;
    private static final int REQUEST_BUDGET_MS = 15_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private final Object requestLock = new Object();
    private final ConnectionFactory connectionFactory;
    private volatile boolean closed;
    private HttpsURLConnection activeConnection;

    public ReleaseUpdateChecker() {
        this(() -> (HttpsURLConnection) new URL(API_URL).openConnection());
    }

    ReleaseUpdateChecker(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Result check(String installedVersion) throws IOException {
        parseVersion(installedVersion);
        ensureOpen();
        HttpsURLConnection connection = connectionFactory.open();
        synchronized (requestLock) {
            if (closed || activeConnection != null) {
                connection.disconnect();
                throw new IOException(closed ? "Update checker is closed" : "Update check already running");
            }
            activeConnection = connection;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_BUDGET_MS);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "WaveFerry-UpdateChecker");
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
            connection.setRequestProperty("Accept-Encoding", "identity");
            ensureOpen();
            int responseCode = connection.getResponseCode();
            ensureOpen();
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                throw new IOException("GitHub update request failed (HTTP " + responseCode + ")");
            }
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new IOException("GitHub release response is too large");
            }
            try (InputStream input = connection.getInputStream()) {
                String body = readResponse(connection, input, deadline);
                ensureOpen();
                return parseResponse(installedVersion, body);
            }
        } finally {
            synchronized (requestLock) {
                if (activeConnection == connection) {
                    activeConnection = null;
                }
            }
            connection.disconnect();
        }
    }

    /** Permanently closes this checker and interrupts an active connection without holding its lock. */
    @Override
    public void close() {
        HttpsURLConnection connection;
        synchronized (requestLock) {
            closed = true;
            connection = activeConnection;
            activeConnection = null;
        }
        if (connection != null) {
            connection.disconnect();
        }
    }

    private String readResponse(HttpsURLConnection connection, InputStream input, long deadline)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        while (true) {
            ensureOpen();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new IOException("GitHub update request timed out");
            }
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            connection.setReadTimeout((int) Math.min(READ_TIMEOUT_MS, remainingMillis));
            int count = input.read(buffer);
            if (System.nanoTime() > deadline) {
                throw new IOException("GitHub update request timed out");
            }
            if (count == -1) {
                break;
            }
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw new IOException("GitHub release response is too large");
            }
            output.write(buffer, 0, count);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray())).toString();
    }

    private static Result parseResponse(String installedVersion, String body) throws IOException {
        String tagName = null;
        String releaseUrl = null;
        Boolean draft = null;
        Boolean prerelease = null;
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "tag_name":
                        requireField(reader, tagName == null, JsonToken.STRING);
                        tagName = reader.nextString();
                        break;
                    case "html_url":
                        requireField(reader, releaseUrl == null, JsonToken.STRING);
                        releaseUrl = reader.nextString();
                        break;
                    case "draft":
                        requireField(reader, draft == null, JsonToken.BOOLEAN);
                        draft = reader.nextBoolean();
                        break;
                    case "prerelease":
                        requireField(reader, prerelease == null, JsonToken.BOOLEAN);
                        prerelease = reader.nextBoolean();
                        break;
                    default:
                        reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || draft == null || prerelease == null) {
                throw new IOException("Invalid GitHub release response");
            }
        } catch (IllegalStateException exception) {
            throw new IOException("Invalid GitHub release response", exception);
        }
        return resultForRelease(installedVersion, tagName, releaseUrl, draft, prerelease);
    }

    private static void requireField(JsonReader reader, boolean unseen, JsonToken expected)
            throws IOException {
        if (!unseen || reader.peek() != expected) {
            throw new IOException("Invalid GitHub release response");
        }
    }

    static Result resultForRelease(String installedVersion, String tagName, String releaseUrl,
            boolean draft, boolean prerelease) throws IOException {
        if (draft || prerelease) {
            throw new IOException("Expected a published stable GitHub release");
        }
        NumericVersion installed = parseVersion(installedVersion);
        NumericVersion released = parseVersion(tagName);
        String trustedUrl = RELEASE_URL_PREFIX + tagName;
        if (!trustedUrl.equals(releaseUrl)) {
            throw new IOException("Unexpected GitHub release URL");
        }
        return new Result(released.getName(), trustedUrl, released.compareTo(installed) > 0);
    }

    private static NumericVersion parseVersion(String value) throws IOException {
        try {
            return NumericVersion.parse(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid numeric release version", exception);
        }
    }

    private void ensureOpen() throws InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update check cancelled");
        }
    }

    interface ConnectionFactory {
        HttpsURLConnection open() throws IOException;
    }

    public static final class Result {
        private final String versionName;
        private final String releaseUrl;
        private final boolean updateAvailable;

        private Result(String versionName, String releaseUrl, boolean updateAvailable) {
            this.versionName = versionName;
            this.releaseUrl = releaseUrl;
            this.updateAvailable = updateAvailable;
        }

        public String getVersionName() {
            return versionName;
        }

        public String getReleaseUrl() {
            return releaseUrl;
        }

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }
    }
}
