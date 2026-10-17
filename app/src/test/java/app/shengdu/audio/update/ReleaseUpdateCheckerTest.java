package app.shengdu.audio.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void checksWithHeadAndReturnsTheTrustedReleaseWithoutReadingItsBody() throws Exception {
        FakeConnection connection = new FakeConnection();
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            ReleaseUpdateChecker.Result result = checker.check("0.2.2");
            assertTrue(result.isUpdateAvailable());
            assertEquals("0.2.3", result.getVersionName());
            assertEquals(TRUSTED_URL, result.getReleaseUrl());
            assertEquals("HEAD", connection.getRequestMethod());
            assertFalse(connection.getInstanceFollowRedirects());
            assertEquals("WaveFerry-UpdateChecker", connection.getRequestProperty("User-Agent"));
            assertEquals(7_000, connection.getConnectTimeout());
            assertEquals(7_000, connection.getReadTimeout());
            assertEquals(1, connection.disconnectCount.get());
        }
    }

    @Test
    public void doesNotOfferCurrentOrOlderReleases() throws Exception {
        assertFalse(check("0.2.3", TRUSTED_URL).isUpdateAvailable());
        assertFalse(check("0.2.10", TRUSTED_URL).isUpdateAvailable());
    }

    @Test
    public void comparesTheRedirectVersionNumerically() throws Exception {
        assertTrue(check("0.2.2", TRUSTED_URL.replace("v0.2.3", "v0.2.10")).isUpdateAvailable());
    }

    @Test
    public void resolvesTrustedRelativeRedirects() throws Exception {
        String[] locations = {"/sqliu07/WaveFerry/releases/tag/v0.2.3", "tag/v0.2.3",
                "//github.com/sqliu07/WaveFerry/releases/tag/v0.2.3"};
        for (String location : locations) {
            assertEquals(TRUSTED_URL, check("0.2.2", location).getReleaseUrl());
        }
    }

    @Test
    public void acceptsOnlyDirectReleaseRedirectStatuses() throws Exception {
        for (int status : new int[]{301, 302, 303, 307, 308}) {
            FakeConnection connection = new FakeConnection();
            connection.status = status;
            try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
                assertTrue(checker.check("0.2.2").isUpdateAvailable());
            }
        }
    }

    @Test
    public void rejectsMissingInvalidAndPrereleaseTags() throws Exception {
        String[] invalid = {null, "", TRUSTED_URL.replace("v0.2.3", "v0.2.3-rc.1"),
                TRUSTED_URL.replace("v0.2.3", "v0.2.3+build"),
                TRUSTED_URL.replace("v0.2.3", "%760.2.3"),
                TRUSTED_URL.replace("v0.2.3", ""), TRUSTED_URL + "/other", "not a uri"};
        for (String location : invalid) {
            expectRejected(() -> check("0.2.2", location));
        }
    }

    @Test
    public void rejectsUntrustedRedirectsAndAdditionalRedirectHops() throws Exception {
        String[] invalid = {"http://github.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://example.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com.evil.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com@evil.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://user@github.com/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com:444/sqliu07/WaveFerry/releases/tag/v0.2.3",
                "https://github.com/other/WaveFerry/releases/tag/v0.2.3",
                "https://github.com/sqliu07/Other/releases/tag/v0.2.3",
                TRUSTED_URL + "?redirect=https://example.com", TRUSTED_URL + "#fragment",
                "https://github.com/sqliu07/WaveFerry/releases/latest",
                "/login", "//evil.com/sqliu07/WaveFerry/releases/tag/v0.2.3"};
        for (String location : invalid) {
            expectRejected(() -> check("0.2.2", location));
        }
    }

    @Test
    public void exposesAccessRestrictionStatusCodesAndOtherUnexpectedStatuses() throws Exception {
        for (int status : new int[]{403, 429, 200, 304, 404, 500, 503}) {
            FakeConnection connection = new FakeConnection();
            connection.status = status;
            try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
                try {
                    checker.check("0.2.2");
                    fail("Accepted unexpected HTTP status");
                } catch (ReleaseUpdateChecker.HttpException expected) {
                    assertEquals(status, expected.getStatusCode());
                }
                assertEquals(1, connection.disconnectCount.get());
            }
        }
    }

    @Test
    public void disconnectsAfterFailureAndAllowsAnotherCheck() throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.status = 503;
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            expectRejected(() -> checker.check("0.2.2"));
            connection.status = 302;
            assertTrue(checker.check("0.2.2").isUpdateAvailable());
            assertEquals(2, connection.disconnectCount.get());
        }
    }

    @Test
    public void invalidInstalledVersionDoesNotOpenAConnection() throws Exception {
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> {
            fail("Opened a connection for invalid installed version");
            return null;
        })) {
            expectRejected(() -> checker.check("unknown"));
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

    @Test
    public void rejectsConcurrentChecksAndDisconnectsTheUnusedConnection() throws Exception {
        FakeConnection active = new FakeConnection();
        active.blockResponse = true;
        FakeConnection rejected = new FakeConnection();
        AtomicInteger opened = new AtomicInteger();
        ReleaseUpdateChecker checker = new ReleaseUpdateChecker(
                () -> opened.incrementAndGet() == 1 ? active : rejected);
        Thread request = new Thread(() -> {
            try {
                checker.check("0.2.2");
            } catch (IOException expected) {
                // The test closes the active request.
            }
        });
        request.start();
        try {
            assertTrue(active.responseStarted.await(2, TimeUnit.SECONDS));
            expectRejected(() -> checker.check("0.2.2"));
            assertEquals(1, rejected.disconnectCount.get());
        } finally {
            checker.close();
            request.interrupt();
            request.join(2_000);
        }
        assertFalse(request.isAlive());
    }

    private static ReleaseUpdateChecker.Result check(String installed, String location) throws Exception {
        FakeConnection connection = new FakeConnection();
        connection.location = location;
        try (ReleaseUpdateChecker checker = new ReleaseUpdateChecker(() -> connection)) {
            try {
                return checker.check(installed);
            } finally {
                assertEquals(1, connection.disconnectCount.get());
            }
        }
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

    private static final class FakeConnection extends HttpsURLConnection {
        final AtomicInteger disconnectCount = new AtomicInteger();
        final CountDownLatch responseStarted = new CountDownLatch(1);
        final CountDownLatch disconnected = new CountDownLatch(1);
        int status = 302;
        String location = TRUSTED_URL;
        boolean blockResponse;

        FakeConnection() throws Exception {
            super(new URL("https://github.com/sqliu07/WaveFerry/releases/latest"));
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
        public String getHeaderField(String name) {
            return "Location".equalsIgnoreCase(name) ? location : null;
        }

        @Override
        public InputStream getInputStream() {
            fail("HEAD update check must not read a response body");
            return null;
        }

        @Override
        public InputStream getErrorStream() {
            fail("HEAD update check must not read an error body");
            return null;
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
