package app.shengdu.audio.update;

import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

public final class ApkUpdatePolicyTest {
    private static final String VERSION = "0.2.4";
    private static final String FILE = "WaveFerry-0.2.4-release.apk";
    private static final String URL = "https://github.com/sqliu07/WaveFerry/releases/download/v0.2.4/" + FILE;
    private static final byte[][] SIGNERS = {{1, 2, 3}, {4, 5, 6}};

    @Test
    public void acceptsOnlyTheExpectedRepositoryAssetAndVersion() throws Exception {
        ApkUpdatePolicy.validateSource(VERSION, URL, FILE);
        ApkUpdatePolicy.validateSource(VERSION, URL.replace("/v0.2.4/", "/0.2.4/"), FILE);
        ApkUpdatePolicy.validateSource(VERSION, URL.replace("/v0.2.4/", "/V0.2.4/"), FILE);
    }

    @Test
    public void rejectsUntrustedOrAmbiguousAssetUrls() throws Exception {
        String[] urls = {null, "", "not a URI", URL.replace("https:", "http:"),
                URL.replace("github.com", "github.com.attacker.invalid"),
                URL.replace("github.com", "github.com@attacker.invalid"),
                URL.replace("github.com", "user@github.com"),
                URL.replace("github.com", "github.com:443"),
                URL.replace("sqliu07", "someone"), URL.replace("WaveFerry/releases", "Other/releases"),
                URL + "?download=1", URL + "#fragment", URL + "/extra",
                URL.replace("/v0.2.4/", "/v0.2.5/"),
                URL.replace("/v0.2.4/", "/v0.2.4-rc.1/"),
                URL.replace("/v0.2.4/", "/%760.2.4/"),
                URL.replace("/v0.2.4/", "/../v0.2.4/"),
                URL.replace("-release.apk", "-debug.apk"), URL.replace(".apk", ".aab")};
        for (String url : urls) {
            rejected(() -> ApkUpdatePolicy.validateSource(VERSION, url, FILE));
        }
    }

    @Test
    public void rejectsUnsafeNamesAndNonCanonicalExpectedVersions() throws Exception {
        for (String file : new String[]{null, "", "../" + FILE, FILE + "/other", "/" + FILE,
                "WaveFerry-0.2.5-release.apk"}) {
            rejected(() -> ApkUpdatePolicy.validateSource(VERSION, URL, file));
        }
        for (String version : new String[]{null, "", "v0.2.4", "0.2.4-rc.1", "../../0.2.4"}) {
            rejected(() -> ApkUpdatePolicy.validateSource(version, URL, FILE));
        }
    }

    @Test
    public void acceptsAValidNewerArchiveWithTheSameCompleteSignerSetInAnyOrder() throws Exception {
        validate("app.shengdu.audio", VERSION, 15, new byte[][]{{4, 5, 6}, {1, 2, 3}});
        ApkUpdatePolicy.validateArchive("app.shengdu.audio", VERSION, 14,
                new byte[][]{{1}}, "app.shengdu.audio", VERSION, 1L << 33, new byte[][]{{1}});
    }

    @Test
    public void rejectsDifferentOrMissingPackagesAndVersions() throws Exception {
        for (String packageName : new String[]{null, "", "app.other.audio"}) {
            rejected(() -> validate(packageName, VERSION, 15, SIGNERS));
        }
        for (String version : new String[]{null, "", "0.2.3", "0.2.5", "v0.2.4", "0.2.4.0"}) {
            rejected(() -> validate("app.shengdu.audio", version, 15, SIGNERS));
        }
        rejected(() -> ApkUpdatePolicy.validateArchive(null, VERSION, 14, SIGNERS,
                "app.shengdu.audio", VERSION, 15, SIGNERS));
        rejected(() -> ApkUpdatePolicy.validateArchive("app.shengdu.audio", "0.2.4-rc.1", 14, SIGNERS,
                "app.shengdu.audio", "0.2.4-rc.1", 15, SIGNERS));
    }

    @Test
    public void rejectsAnEqualOrOlderVersionCodeEvenWhenTheNameMatches() throws Exception {
        for (long versionCode : new long[]{-1, 0, 13, 14}) {
            rejected(() -> validate("app.shengdu.audio", VERSION, versionCode, SIGNERS));
        }
    }

    @Test
    public void rejectsMissingPartialChangedDuplicateAndExtraSignerSets() throws Exception {
        byte[][][] invalid = {null, {}, {null}, {{}}, {{1, 2, 3}}, {{4, 5, 6}},
                {{1, 2, 3}, {4, 5, 7}}, {{1, 2, 3}, {4, 5, 6}, {7}},
                {{1, 2, 3}, {4, 5, 6}, {1, 2, 3}}, {{1, 2, 3}, null}};
        for (byte[][] signers : invalid) {
            rejected(() -> validate("app.shengdu.audio", VERSION, 15, signers));
            rejected(() -> ApkUpdatePolicy.validateArchive("app.shengdu.audio", VERSION, 14,
                    signers, "app.shengdu.audio", VERSION, 15, SIGNERS));
        }
    }

    @Test
    public void doesNotTreatAnEmptyMatchingSignerSetAsTrusted() throws Exception {
        rejected(() -> ApkUpdatePolicy.validateArchive("app.shengdu.audio", VERSION, 14,
                new byte[0][], "app.shengdu.audio", VERSION, 15, new byte[0][]));
    }

    private static void validate(String packageName, String version, long versionCode, byte[][] signers)
            throws IOException {
        ApkUpdatePolicy.validateArchive("app.shengdu.audio", VERSION, 14, SIGNERS,
                packageName, version, versionCode, signers);
    }

    private static void rejected(ThrowingRunnable operation) throws Exception {
        try {
            operation.run();
            fail("Accepted untrusted update metadata or APK identity");
        } catch (IOException expected) {
            // Expected: a real policy rejection, with no Android framework stubs involved.
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
