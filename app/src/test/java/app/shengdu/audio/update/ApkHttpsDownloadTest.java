package app.shengdu.audio.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

import org.junit.Test;

public final class ApkHttpsDownloadTest {
    private static final URI SOURCE = URI.create(
            "https://github.com/sqliu07/WaveFerry/releases/download/v0.2.4/WaveFerry-0.2.4-release.apk");
    private static final byte[] APK_BYTES = {'P', 'K', 3, 4, 1, 2, 3, 4};

    @Test
    public void streamsApkBytesWithExplicitGetAndReportsProgress() throws Exception {
        FakeConnection connection = new FakeConnection();
        File destination = File.createTempFile("apk-transfer-", ".part");
        try (ApkHttpsDownload transfer = new ApkHttpsDownload(uri -> connection)) {
            List<Long> bytes = new ArrayList<>();
            transfer.download(SOURCE, destination, (received, total) -> {
                bytes.add(received);
                assertEquals(APK_BYTES.length, total);
            });
            assertArrayEquals(APK_BYTES, Files.readAllBytes(destination.toPath()));
            assertEquals(Long.valueOf(0), bytes.get(0));
            assertEquals(Long.valueOf(APK_BYTES.length), bytes.get(bytes.size() - 1));
            assertEquals("GET", connection.getRequestMethod());
            assertFalse(connection.getInstanceFollowRedirects());
            assertEquals("identity", connection.getRequestProperty("Accept-Encoding"));
            assertEquals(1, connection.disconnects);
        } finally {
            Files.deleteIfExists(destination.toPath());
        }
    }

    @Test
    public void followsRelativeAndOfficialAssetRedirectsWithoutSendingThemToOtherHosts() throws Exception {
        Queue<FakeConnection> responses = new ArrayDeque<>();
        responses.add(redirect("/sqliu07/WaveFerry/releases/download/v0.2.4/other.apk"));
        responses.add(redirect("https://release-assets.githubusercontent.com/github-production-release-asset/1?sig=abc"));
        responses.add(new FakeConnection());
        List<URI> requested = new ArrayList<>();
        tryTransfer(new ApkHttpsDownload(uri -> {
            requested.add(uri);
            return responses.remove();
        }), (bytes, total) -> { });
        assertEquals(3, requested.size());
        assertEquals("github.com", requested.get(1).getHost());
        assertEquals("release-assets.githubusercontent.com", requested.get(2).getHost());
    }

    @Test
    public void rejectsUnsafeRedirectsBeforeOpeningAnotherConnection() throws Exception {
        for (String location : new String[]{"http://github.com/file.apk", "file:///tmp/file.apk",
                "https://attacker.invalid/file.apk", "https://github.com.attacker.invalid/file.apk",
                "https://user@github.com/file.apk", "https://github.com:443/file.apk",
                "https://release-assets.githubusercontent.com.attacker.invalid/file.apk",
                "https://objects.githubusercontent.com/file.apk#fragment", "not a URI", ""}) {
            AtomicInteger opened = new AtomicInteger();
            ApkHttpsDownload transfer = new ApkHttpsDownload(uri -> {
                opened.incrementAndGet();
                return redirect(location);
            });
            rejected(transfer);
            assertEquals(1, opened.get());
        }
    }

    @Test
    public void rejectsAnUntrustedInitialHostBeforeOpeningAConnection() throws Exception {
        try (ApkHttpsDownload transfer = new ApkHttpsDownload(uri -> {
            fail("Opened an untrusted initial URL");
            return null;
        })) {
            File destination = File.createTempFile("apk-transfer-", ".part");
            try {
                transfer.download(URI.create("https://attacker.invalid/file.apk"), destination,
                        (bytes, total) -> { });
                fail("Accepted an untrusted initial URL");
            } catch (IOException expected) {
                assertEquals(0, destination.length());
            } finally {
                Files.deleteIfExists(destination.toPath());
            }
        }
    }

    @Test
    public void capsRedirectsAndDisconnectsEveryConnection() throws Exception {
        List<FakeConnection> connections = new ArrayList<>();
        rejected(new ApkHttpsDownload(uri -> {
            FakeConnection response = redirect("/loop.apk");
            connections.add(response);
            return response;
        }));
        assertEquals(6, connections.size());
        for (FakeConnection connection : connections) {
            assertEquals(1, connection.disconnects);
        }
    }

    @Test
    public void rejectsShortOverlongAndEmptyResponses() throws Exception {
        for (long expected : new long[]{APK_BYTES.length + 1, APK_BYTES.length - 1, 0}) {
            FakeConnection connection = new FakeConnection();
            connection.length = expected;
            rejected(new ApkHttpsDownload(uri -> connection));
            assertEquals(1, connection.disconnects);
        }
        FakeConnection empty = new FakeConnection();
        empty.length = -1;
        empty.body = new ByteArrayInputStream(new byte[0]);
        rejected(new ApkHttpsDownload(uri -> empty));
    }

    @Test
    public void enforcesTheMaximumEvenWithoutContentLength() throws Exception {
        FakeConnection tooLarge = new FakeConnection();
        tooLarge.length = ApkHttpsDownload.MAX_BYTES + 1;
        rejected(new ApkHttpsDownload(uri -> tooLarge));
        assertEquals(0, tooLarge.bodyReads);

        FakeConnection streaming = new FakeConnection();
        streaming.length = -1;
        streaming.body = new InputStream() {
            long supplied;

            @Override
            public int read() {
                throw new AssertionError("Expected buffered reads");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 0);
                if (supplied == 0) {
                    System.arraycopy(APK_BYTES, 0, bytes, offset, APK_BYTES.length);
                }
                supplied += length;
                return length;
            }
        };
        rejected(new ApkHttpsDownload(uri -> streaming));
    }

    @Test
    public void rejectsHttpFailuresHtmlAndNonZipOrEncodedPayloads() throws Exception {
        FakeConnection failed = new FakeConnection();
        failed.status = 404;
        rejected(new ApkHttpsDownload(uri -> failed));
        FakeConnection html = new FakeConnection();
        html.type = "text/html; charset=utf-8";
        rejected(new ApkHttpsDownload(uri -> html));
        assertEquals(0, html.bodyReads);
        FakeConnection mislabeled = new FakeConnection();
        mislabeled.body = new ByteArrayInputStream("<html>no".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        rejected(new ApkHttpsDownload(uri -> mislabeled));
        FakeConnection encoded = new FakeConnection();
        encoded.encoding = "gzip";
        rejected(new ApkHttpsDownload(uri -> encoded));
    }

    @Test
    public void cancellationDuringStreamingStopsBeforeSuccessfulCompletion() throws Exception {
        FakeConnection connection = new FakeConnection();
        ApkHttpsDownload transfer = new ApkHttpsDownload(uri -> connection);
        File destination = File.createTempFile("apk-transfer-", ".part");
        try {
            transfer.download(SOURCE, destination, (bytes, total) -> {
                if (bytes > 0) {
                    transfer.close();
                }
            });
            fail("Completed a cancelled download");
        } catch (InterruptedIOException expected) {
            assertTrue(connection.disconnects >= 1);
        } finally {
            transfer.close();
            Files.deleteIfExists(destination.toPath());
        }
    }

    @Test
    public void closingDuringAResponseWaitDisconnectsWithoutWaitingForTheNetworkLock() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.blockResponse = true;
        ApkHttpsDownload transfer = new ApkHttpsDownload(uri -> connection);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                tryTransfer(transfer, (bytes, total) -> { });
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        worker.start();
        try {
            assertTrue(connection.responseStarted.await(2, TimeUnit.SECONDS));
            transfer.close();
            worker.join(2_000);
            assertFalse("Cancellation remained blocked behind the response", worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedIOException);
        } finally {
            transfer.close();
            worker.interrupt();
            worker.join(2_000);
        }
    }

    @Test
    public void interruptionOrClosingBeforeStartDoesNotOpenAConnection() throws Exception {
        ApkHttpsDownload closed = new ApkHttpsDownload(uri -> {
            fail("Opened a closed transfer");
            return null;
        });
        closed.close();
        rejected(closed);
        Thread.currentThread().interrupt();
        try {
            rejected(new ApkHttpsDownload(uri -> {
                fail("Opened an interrupted transfer");
                return null;
            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static FakeConnection redirect(String location) throws IOException {
        FakeConnection result = new FakeConnection();
        result.status = 302;
        result.location = location;
        return result;
    }

    private static void tryTransfer(ApkHttpsDownload transfer, ApkHttpsDownload.Progress progress)
            throws Exception {
        File destination = File.createTempFile("apk-transfer-", ".part");
        try (transfer) {
            transfer.download(SOURCE, destination, progress);
        } finally {
            Files.deleteIfExists(destination.toPath());
        }
    }

    private static void rejected(ApkHttpsDownload transfer) throws Exception {
        try {
            tryTransfer(transfer, (bytes, total) -> { });
            fail("Accepted an invalid APK response");
        } catch (IOException expected) {
            // Real transport rejection; no Android framework implementation is mocked.
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        int status = 200;
        int disconnects;
        int bodyReads;
        long length = APK_BYTES.length;
        String type = "application/octet-stream";
        String location;
        String encoding;
        InputStream body = new ByteArrayInputStream(APK_BYTES);
        boolean blockResponse;
        final CountDownLatch responseStarted = new CountDownLatch(1);
        final CountDownLatch responseReleased = new CountDownLatch(1);

        FakeConnection() throws IOException {
            super(new URL(SOURCE.toString()));
        }

        @Override public int getResponseCode() throws IOException {
            responseStarted.countDown();
            if (blockResponse) {
                try {
                    if (!responseReleased.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("Test response wait timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Test response interrupted");
                }
            }
            return status;
        }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getContentType() { return type; }
        @Override public String getContentEncoding() { return encoding; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public InputStream getInputStream() { bodyReads++; return body; }
        @Override public void disconnect() { disconnects++; responseReleased.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "fake"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
}
