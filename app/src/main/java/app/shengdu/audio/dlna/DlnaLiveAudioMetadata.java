package app.shengdu.audio.dlna;

import java.net.URI;
import java.util.Locale;

/** DLNA metadata and protocol markers for the app's live PCM/WAV endpoint. */
final class DlnaLiveAudioMetadata {
    private static final String DLNA_FLAGS = "01700000000000000000000000000000";
    private static final String CONTENT_FEATURES =
            "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=" + DLNA_FLAGS;
    private static final String PROTOCOL_INFO = "http-get:*:audio/wav:" + CONTENT_FEATURES;

    private DlnaLiveAudioMetadata() {
    }

    static String didlLite(URI mediaUri) {
        return didlLite(mediaUri, "WaveFerry", "WaveFerry", "", null);
    }

    static String didlLite(
            URI mediaUri, String title, String artist, String album, URI artworkUri) {
        return didlLite(mediaUri, title, artist, album, artworkUri, 0L);
    }

    static String didlLite(
            URI mediaUri,
            String title,
            String artist,
            String album,
            URI artworkUri,
            long durationMs) {
        String uri = xml(mediaUri.toString());
        String resolvedTitle = normalized(title, "WaveFerry");
        String resolvedArtist = normalized(artist, "WaveFerry");
        String resolvedAlbum = normalized(album, "");
        String duration = durationMs <= 0
                ? ""
                : " duration=\"" + timeValue(durationMs) + "\"";
        return "<?xml version=\"1.0\"?>"
                + "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" "
                + "xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">"
                + "<item id=\"waveferry-live\" parentID=\"0\" restricted=\"0\">"
                + "<dc:title>" + xml(resolvedTitle) + "</dc:title>"
                + "<dc:creator>" + xml(resolvedArtist) + "</dc:creator>"
                + "<upnp:artist>" + xml(resolvedArtist) + "</upnp:artist>"
                + (resolvedAlbum.isEmpty() ? "" : "<upnp:album>" + xml(resolvedAlbum) + "</upnp:album>")
                + (artworkUri == null ? "" : "<upnp:albumArtURI dlna:profileID=\"JPEG_SM\">"
                + xml(artworkUri.toString()) + "</upnp:albumArtURI>")
                + "<upnp:class>object.item.audioItem.musicTrack</upnp:class>"
                + "<res protocolInfo=\"" + xml(PROTOCOL_INFO) + "\"" + duration + ">" + uri
                + "</res></item></DIDL-Lite>";
    }

    static String contentFeatures() {
        return CONTENT_FEATURES;
    }

    private static String normalized(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    static String timeValue(long timeMs) {
        long normalized = Math.max(0L, timeMs);
        long hours = normalized / 3_600_000L;
        long minutes = (normalized / 60_000L) % 60L;
        long seconds = (normalized / 1_000L) % 60L;
        long milliseconds = normalized % 1_000L;
        return String.format(
                Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
