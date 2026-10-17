package app.shengdu.audio.update;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** One user-initiated request at a time; the caller supplies its own background executor. */
public final class ReleaseUpdateChecker implements AutoCloseable {
    private static final URI LATEST_URL = URI.create("https://github.com/sqliu07/WaveFerry/releases/latest");
    private static final String RELEASE_PATH_PREFIX = "/sqliu07/WaveFerry/releases/tag/";
    private static final int CONNECT_TIMEOUT_MS = 7_000;
    private static final int READ_TIMEOUT_MS = 7_000;
    private static final int REQUEST_BUDGET_MS = 15_000;

    private final Object requestLock = new Object();
    private final ConnectionFactory connectionFactory;
    private volatile boolean closed;
    private HttpsURLConnection activeConnection;

    public ReleaseUpdateChecker() {
        this(() -> (HttpsURLConnection) LATEST_URL.toURL().openConnection());
    }

    ReleaseUpdateChecker(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public Result check(String installedVersion) throws IOException {
        NumericVersion installed = parseVersion(installedVersion);
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
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "WaveFerry-UpdateChecker");
            ensureOpen();
            int responseCode = connection.getResponseCode();
            ensureOpen();
            if (System.nanoTime() > deadline) {
                throw new SocketTimeoutException("GitHub update request timed out");
            }
            if (responseCode != 301 && responseCode != 302 && responseCode != 303
                    && responseCode != 307 && responseCode != 308) {
                throw new HttpException(responseCode);
            }
            URI release = trustedReleaseUri(connection.getHeaderField("Location"));
            String tag = release.getRawPath().substring(RELEASE_PATH_PREFIX.length());
            NumericVersion released = parseVersion(tag);
            ensureOpen();
            return new Result(released.getName(), "https://github.com" + RELEASE_PATH_PREFIX + tag,
                    released.compareTo(installed) > 0);
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

    private static URI trustedReleaseUri(String location) throws IOException {
        if (location == null || location.isEmpty() || location.length() > 2_048) {
            throw new IOException("Missing or invalid GitHub release redirect");
        }
        URI release;
        try {
            release = LATEST_URL.resolve(new URI(location));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IOException("Invalid GitHub release redirect", exception);
        }
        if (!"https".equalsIgnoreCase(release.getScheme())
                || !"github.com".equalsIgnoreCase(release.getHost())
                || release.getPort() != -1 || release.getRawUserInfo() != null
                || release.getRawQuery() != null || release.getRawFragment() != null
                || release.getRawPath() == null
                || !release.getRawPath().startsWith(RELEASE_PATH_PREFIX)) {
            throw new IOException("Unexpected GitHub release redirect");
        }
        return release;
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

    public static final class HttpException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        private HttpException(int statusCode) {
            super("GitHub update request failed (HTTP " + statusCode + ")");
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
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

        public String getApkFileName() {
            return "WaveFerry-" + versionName + "-release.apk";
        }

        public String getApkDownloadUrl() {
            String tag = releaseUrl.substring(releaseUrl.lastIndexOf('/') + 1);
            return "https://github.com/sqliu07/WaveFerry/releases/download/" + tag
                    + "/" + getApkFileName();
        }

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }
    }
}
