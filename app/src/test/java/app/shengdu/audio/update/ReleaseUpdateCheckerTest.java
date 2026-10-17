package app.shengdu.audio.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;

import org.junit.Test;

public final class ReleaseUpdateCheckerTest {
    private static final String TRUSTED_URL = "https://github.com/sqliu07/WaveFerry/releases/tag/v0.2.3";

    @Test
    public void reportsNewStableVersionAndTrustedReleasePage() throws Exception {
        ReleaseUpdateChecker.Result result = result("0.2.2", "v0.2.3", TRUSTED_URL, false, false);

        assertTrue(result.isUpdateAvailable());
        assertEquals("0.2.3", result.getVersionName());
        assertEquals(TRUSTED_URL, result.getReleaseUrl());
    }

    @Test
    public void doesNotOfferTheCurrentOrOlderReleaseAsAnUpdate() throws Exception {
        assertFalse(result("0.2.3", "v0.2.3", TRUSTED_URL, false, false).isUpdateAvailable());
        assertFalse(result("0.2.10", "v0.2.3", TRUSTED_URL, false, false).isUpdateAvailable());
    }

    @Test
    public void refusesDraftAndPrereleaseResponses() throws Exception {
        expectRejected(() -> result("0.2.2", "v0.2.3", TRUSTED_URL, true, false));
        expectRejected(() -> result("0.2.2", "v0.2.3", TRUSTED_URL, false, true));
    }

    @Test
    public void refusesMissingOrInvalidVersions() throws Exception {
        expectRejected(() -> result("0.2.2", null, TRUSTED_URL, false, false));
        expectRejected(() -> result("0.2.2", "v0.2.3-rc.1", TRUSTED_URL, false, false));
        expectRejected(() -> result("unknown", "v0.2.3", TRUSTED_URL, false, false));
    }

    @Test
    public void refusesUrlsOutsideTheExactRepositoryRelease() throws Exception {
        String[] invalid = {null, "", "http://github.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://example.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com.evil.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com@evil.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://user@github.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com/other/WaveFerry/releases/tag/v0.2.3",
                "https://github.com/sqliu07/Other/releases/tag/v0.2.3",
                "https://github.com/sqliu07/WaveFerry/releases/tag/v0.2.4",
                TRUSTED_URL + "?redirect=https://example.com", TRUSTED_URL + "#fragment",
                TRUSTED_URL + "/../../../../other", TRUSTED_URL.replace("v0.2.3", "%760.2.3")};
        for (String url : invalid) {
            expectRejected(() -> result("0.2.2", "v0.2.3", url, false, false));
        }
    }

    @Test
    public void closedCheckerDoesNotOpenAConnection() throws Exception {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> {
            fail("Closed checker opened a connection");
            return null;
        });
        checker.close();
        expectRejected(() -> checker.check("0.2.2"));
        checker.close();
    }

    @Test
    public void interruptedCallerDoesNotOpenAConnection() throws Exception {
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> {
            fail("Interrupted checker opened a connection");
            return null;
        });
        Thread.currentThread().interrupt();
        try {
            checker.check("0.2.2");
            fail("Ignored interruption");
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
            checker.close();
        }
    }

    @Test
    public void httpErrorDisconnectsAndLeavesTheCheckerReadyToRetry() throws Exception {
        FakeConnection connection = new FakeConnection();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection);
        try {
            expectRejected(() -> checker.check("0.2.2"));
            expectRejected(() -> checker.check("0.2.2"));
            assertEquals(2, connection.disconnectCount.get());
            assertFalse(connection.inputOpened);
            assertFalse(connection.getInstanceFollowRedirects());
            assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"));
            assertEquals("WaveFerry-UpdateChecker", connection.getRequestProperty("User-Agent"));
            assertTrue(connection.getConnectTimeout() > 0);
            assertTrue(connection.getReadTimeout() > 0);
        } finally {
            checker.close();
        }
    }

    @Test
    public void refusesRedirectWithoutReadingTheResponse() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.status = HttpsURLConnection.HTTP_MOVED_TEMP;
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            expectRejected(() -> checker.check("0.2.2"));
            assertFalse(connection.getInstanceFollowRedirects());
            assertFalse(connection.inputOpened);
            assertEquals(1, connection.disconnectCount.get());
        }
    }

    @Test
    public void refusesOversizedContentLengthBeforeReadingTheBody() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.status = HttpsURLConnection.HTTP_OK;
        connection.responseLength = 512 * 1024 + 1;
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            expectRejected(() -> checker.check("0.2.2"));
            assertFalse(connection.inputOpened);
            assertEquals(1, connection.disconnectCount.get());
        }
    }

    @Test
    public void capsUnknownLengthBodiesAndClosesTheStream() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.status = HttpsURLConnection.HTTP_OK;
        connection.body = new TrackedInputStream(new byte[512 * 1024 + 1]);
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            expectRejected(() -> checker.check("0.2.2"));
            assertTrue(connection.body.closed);
            assertEquals(1, connection.disconnectCount.get());
        }
    }

    @Test
    public void rejectsMalformedUtf8AndClosesTheStream() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.status = HttpsURLConnection.HTTP_OK;
        connection.body = new TrackedInputStream(new byte[]{(byte) 0xc0, (byte) 0xaf});
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            expectRejected(() -> checker.check("0.2.2"));
            assertTrue(connection.body.closed);
            assertEquals(1, connection.disconnectCount.get());
        }
    }

    @Test
    public void closingDuringAnActiveRequestDisconnectsWithoutWaitingForItsLock() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.blockResponse = true;
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                checker.check("0.2.2");
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        request.start();
        try {
            assertTrue(connection.responseStarted.await(2, TimeUnit.SECONDS));
            checker.close();
            request.join(2_000);
            assertFalse("Request remained blocked after close", request.isAlive());
            assertTrue(failure.get() instanceof IOException);
            assertTrue(connection.disconnectCount.get() > 0);
        } finally {
            checker.close();
            request.interrupt();
            request.join(2_000);
        }
    }

    private static ReleaseUpdateChecker.Result result(String installed, String tag, String url,
            boolean draft, boolean prerelease) throws IOException {
        return ReleaseUpdateChecker.resultForRelease(installed, tag, url, draft, prerelease);
    }

    private static void expectRejected(CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Accepted invalid release or request");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class TrackedInputStream extends ByteArrayInputStream {
        boolean closed;

        TrackedInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        final AtomicInteger disconnectCount = new AtomicInteger();
        final CountDownLatch responseStarted = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        int status = HttpsURLConnection.HTTP_UNAVAILABLE;
        long responseLength = -1;
        boolean blockResponse;
        boolean inputOpened;
        TrackedInputStream body = new TrackedInputStream(new byte[0]);

        FakeConnection() throws Exception {
            super(new URL("https://api.github.com/repos/sqliu07/WaveFerry/releases/latest"));
        }

        @Override
        public int getResponseCode() throws IOException {
            responseStarted.countDown();
            if (blockResponse) {
                try {
                    if (!disconnected.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Connection was not cancelled");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted fake request");
                }
                throw new IOException("Disconnected");
            }
            return status;
        }

        @Override
        public long getContentLengthLong() {
            return responseLength;
        }

        @Override
        public InputStream getInputStream() {
            inputOpened = true;
            return body;
        }

        @Override
        public void disconnect() {
            disconnectCount.incrementAndGet();
            disconnected.countDown();
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public String getCipherSuite() {
            return "unused";
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() {
            return null;
        }
    }
}
