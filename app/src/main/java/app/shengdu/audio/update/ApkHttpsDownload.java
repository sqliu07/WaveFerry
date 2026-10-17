package app.shengdu.audio.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/** One bounded HTTPS transfer. The caller owns the destination and its eventual atomic rename. */
final class ApkHttpsDownload implements AutoCloseable {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private final Object connectionLock = new Object();
    private final ConnectionFactory connections;
    private volatile boolean cancelled;
    private HttpsURLConnection active;

    ApkHttpsDownload() {
        this(uri -> (HttpsURLConnection) uri.toURL().openConnection());
    }

    ApkHttpsDownload(ConnectionFactory connections) {
        this.connections = connections;
    }

    void download(URI source, File destination, Progress progress) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        URI next = source;
        for (int redirects = 0; ; redirects++) {
            checkCancelled();
            trustedHttps(next);
            HttpsURLConnection connection = connections.open(next);
            synchronized (connectionLock) {
                if (cancelled) {
                    connection.disconnect();
                    throw new InterruptedIOException("Update download cancelled");
                }
                active = connection;
            }
            try {
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(15_000);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "WaveFerry-Updater");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                checkCancelled();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("Too many APK download redirects");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty() || location.length() > 16_384) {
                        throw new IOException("Invalid APK download redirect");
                    }
                    try {
                        next = next.resolve(new URI(location));
                    } catch (URISyntaxException | IllegalArgumentException exception) {
                        throw new IOException("Invalid APK download redirect", exception);
                    }
                    trustedHttps(next);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("APK download failed (HTTP " + status + ")");
                }
                String type = connection.getContentType();
                if (type != null && (type.toLowerCase(java.util.Locale.ROOT).startsWith("text/")
                        || type.toLowerCase(java.util.Locale.ROOT).contains("html")
                        || type.toLowerCase(java.util.Locale.ROOT).contains("json"))) {
                    throw new IOException("The update response is not an APK");
                }
                String encoding = connection.getContentEncoding();
                if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                    throw new IOException("Unexpected APK content encoding");
                }
                long total = connection.getContentLengthLong();
                if (total == 0 || total > MAX_BYTES) {
                    throw new IOException("Invalid APK download size");
                }
                long received = 0;
                byte[] signature = new byte[4];
                progress.update(0, total > 0 ? total : -1);
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled();
                        if (System.nanoTime() > deadline) {
                            throw new java.net.SocketTimeoutException("APK download timed out");
                        }
                        if (count == 0) {
                            continue;
                        }
                        if (received + count > MAX_BYTES || total > 0 && received + count > total) {
                            throw new IOException("APK download exceeds its expected size");
                        }
                        for (int index = 0; index < count && received + index < signature.length; index++) {
                            signature[(int) received + index] = buffer[index];
                        }
                        output.write(buffer, 0, count);
                        received += count;
                        progress.update(received, total > 0 ? total : -1);
                    }
                    checkCancelled();
                    if (received < 4 || total > 0 && received != total
                            || signature[0] != 'P' || signature[1] != 'K'
                            || signature[2] != 3 || signature[3] != 4) {
                        throw new IOException("The APK download is incomplete or invalid");
                    }
                    output.getFD().sync();
                }
                checkCancelled();
                return;
            } finally {
                synchronized (connectionLock) {
                    if (active == connection) {
                        active = null;
                    }
                }
                connection.disconnect();
            }
        }
    }

    static void trustedHttps(URI uri) throws IOException {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !("github.com".equalsIgnoreCase(host)
                || "release-assets.githubusercontent.com".equalsIgnoreCase(host)
                || "objects.githubusercontent.com".equalsIgnoreCase(host))) {
            throw new IOException("Untrusted APK download host");
        }
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update download cancelled");
        }
    }

    @Override
    public void close() {
        HttpsURLConnection connection;
        synchronized (connectionLock) {
            cancelled = true;
            connection = active;
        }
        if (connection != null) {
            connection.disconnect();
        }
    }

    interface ConnectionFactory {
        HttpsURLConnection open(URI uri) throws IOException;
    }

    interface Progress {
        void update(long received, long total) throws IOException;
    }
}
