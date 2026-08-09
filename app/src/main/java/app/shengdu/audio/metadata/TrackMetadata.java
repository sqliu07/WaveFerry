package app.shengdu.audio.metadata;

import java.util.Objects;

/**
 * Immutable snapshot of the currently playing track, read from an Android MediaSession.
 * Pure value object with no Android dependencies so it can be unit tested on the JVM.
 * Fields are never null; null and blank inputs are normalized to trimmed empty strings.
 */
public final class TrackMetadata {
    private final String sourcePackage;
    private final String mediaId;
    private final String queueItemId;
    private final String title;
    private final String artist;
    private final String album;
    private final long durationMs;
    private final long positionMs;
    private final boolean playing;

    public TrackMetadata(String sourcePackage, String title, String artist, String album) {
        this(sourcePackage, "", "", title, artist, album, 0, 0, false);
    }

    public TrackMetadata(
            String sourcePackage,
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            boolean playing) {
        this(sourcePackage, "", "", title, artist, album, durationMs, positionMs, playing);
    }

    /** Convenience constructor for snapshots with one known identity. */
    public TrackMetadata(
            String sourcePackage,
            String itemIdentity,
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            boolean playing) {
        this(
                sourcePackage,
                identityPart(itemIdentity, "media:"),
                identityPart(itemIdentity, "queue:"),
                title,
                artist,
                album,
                durationMs,
                positionMs,
                playing);
    }

    public TrackMetadata(
            String sourcePackage,
            String mediaId,
            String queueItemId,
            String title,
            String artist,
            String album,
            long durationMs,
            long positionMs,
            boolean playing) {
        this.sourcePackage = normalize(sourcePackage);
        this.mediaId = normalize(mediaId);
        this.queueItemId = normalize(queueItemId);
        this.title = normalize(title);
        this.artist = normalize(artist);
        this.album = normalize(album);
        this.durationMs = Math.max(0, durationMs);
        this.positionMs = Math.max(0, positionMs);
        this.playing = playing;
    }

    public String getSourcePackage() {
        return sourcePackage;
    }

    public String getItemIdentity() {
        if (!mediaId.isEmpty()) {
            return "media:" + mediaId;
        }
        return queueItemId.isEmpty() ? "" : "queue:" + queueItemId;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    /** Track length in ms, or 0 when the session does not report one (e.g. radio). */
    public long getDurationMs() {
        return durationMs;
    }

    /** Estimated playback position in ms at the moment this snapshot was taken. */
    public long getPositionMs() {
        return positionMs;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** True when there is nothing worth displaying or sending. */
    public boolean isEmpty() {
        return title.isEmpty() && artist.isEmpty() && album.isEmpty();
    }

    /** True when two snapshots refer to the same queue/media item. */
    public boolean sameTrack(TrackMetadata other) {
        if (other == null) {
            return false;
        }
        boolean sameSource = sourcePackage.equals(other.sourcePackage);
        if (sameSource && !mediaId.isEmpty() && !other.mediaId.isEmpty()) {
            return mediaId.equals(other.mediaId);
        }
        if (sameSource && !queueItemId.isEmpty() && !other.queueItemId.isEmpty()) {
            return queueItemId.equals(other.queueItemId);
        }
        if (!sameSource && hasItemIdentity() && other.hasItemIdentity()) {
            return false;
        }
        return title.equals(other.title)
                && artist.equals(other.artist)
                && album.equals(other.album);
    }

    /** Stable dedup key for send policies; empty when there is nothing worth sending. */
    public String dedupKey() {
        if (isEmpty()) {
            return "";
        }
        String display = title + "\n" + artist + "\n" + album;
        String identity = getItemIdentity();
        return identity.isEmpty()
                ? display
                : display + "\n" + sourcePackage + "\n" + identity;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrackMetadata)) {
            return false;
        }
        TrackMetadata other = (TrackMetadata) o;
        return sourcePackage.equals(other.sourcePackage)
                && mediaId.equals(other.mediaId)
                && queueItemId.equals(other.queueItemId)
                && title.equals(other.title)
                && artist.equals(other.artist)
                && album.equals(other.album)
                && durationMs == other.durationMs
                && positionMs == other.positionMs
                && playing == other.playing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sourcePackage,
                mediaId,
                queueItemId,
                title,
                artist,
                album,
                durationMs,
                positionMs,
                playing);
    }

    private boolean hasItemIdentity() {
        return !mediaId.isEmpty() || !queueItemId.isEmpty();
    }

    private static String identityPart(String identity, String prefix) {
        String normalized = normalize(identity);
        if (normalized.startsWith(prefix)) {
            return normalized.substring(prefix.length());
        }
        // Treat an unqualified legacy identity as a media ID.
        return prefix.equals("media:") && !normalized.contains(":") ? normalized : "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
