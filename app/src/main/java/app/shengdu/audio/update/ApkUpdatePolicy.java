package app.shengdu.audio.update;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The trust checks shared by persisted download metadata and downloaded package validation. */
final class ApkUpdatePolicy {
    private static final String DOWNLOAD_PREFIX = "/sqliu07/WaveFerry/releases/download/";

    private ApkUpdatePolicy() {
    }

    static void validateSource(String version, String url, String fileName) throws IOException {
        try {
            if (!NumericVersion.parse(version).getName().equals(version)
                    || !("WaveFerry-" + version + "-release.apk").equals(fileName)) {
                throw new IOException("Unexpected update version or file name");
            }
            URI source = new URI(url);
            if (!"https".equals(source.getScheme()) || !"github.com".equals(source.getHost())
                    || source.getPort() != -1 || source.getRawUserInfo() != null
                    || source.getRawQuery() != null || source.getRawFragment() != null
                    || source.getRawPath() == null
                    || !source.getRawPath().startsWith(DOWNLOAD_PREFIX)) {
                throw new IOException("Untrusted update download URL");
            }
            String[] segments = source.getRawPath().substring(DOWNLOAD_PREFIX.length()).split("/", -1);
            if (segments.length != 2 || !NumericVersion.parse(segments[0]).getName().equals(version)
                    || !segments[1].equals(fileName)) {
                throw new IOException("Update download URL does not match its version");
            }
        } catch (URISyntaxException | IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid update download metadata", exception);
        }
    }

    static void validateArchive(String expectedPackage, String expectedVersion, long installedCode,
            byte[][] installedSigners, String archivePackage, String archiveVersion, long archiveCode,
            byte[][] archiveSigners) throws IOException {
        if (expectedPackage == null || expectedPackage.isEmpty()
                || !expectedPackage.equals(archivePackage)) {
            throw new IOException("Downloaded APK has a different package name");
        }
        try {
            if (!NumericVersion.parse(expectedVersion).getName().equals(expectedVersion)
                    || !expectedVersion.equals(archiveVersion)) {
                throw new IOException("Downloaded APK has a different release version");
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid expected APK version", exception);
        }
        if (installedCode < 0 || archiveCode <= installedCode) {
            throw new IOException("Downloaded APK is not newer than the installed application");
        }
        if (!signerSet(installedSigners).equals(signerSet(archiveSigners))) {
            throw new IOException("Downloaded APK has a different signing certificate");
        }
    }

    private static Set<String> signerSet(byte[][] certificates) throws IOException {
        if (certificates == null || certificates.length == 0) {
            throw new IOException("APK signing certificates are missing");
        }
        Set<String> result = new HashSet<>();
        for (byte[] certificate : certificates) {
            if (certificate == null || certificate.length == 0
                    || !result.add(Arrays.toString(certificate))) {
                throw new IOException("APK signing certificates are incomplete");
            }
        }
        return result;
    }
}
