package app.shengdu.audio.dlna;

import app.shengdu.audio.BuildConfig;

/** Shared UPnP identity values that must stay consistent across discovery and control. */
final class DlnaProtocol {
    static final String USER_AGENT = "WaveFerry/" + BuildConfig.VERSION_NAME + " UPnP/1.0";

    private DlnaProtocol() {
    }

    static boolean isVersionedServiceType(String value, String expectedPrefix) {
        if (value == null || expectedPrefix == null || !value.startsWith(expectedPrefix)) {
            return false;
        }
        int versionStart = expectedPrefix.length();
        if (versionStart == value.length()) {
            return false;
        }
        for (int index = versionStart; index < value.length(); index++) {
            char next = value.charAt(index);
            if (next < '0' || next > '9') {
                return false;
            }
        }
        return true;
    }
}
