package app.shengdu.audio.raop;

/**
 * Whether the receiver is known to accept a RAOP 1 metadata kind, derived from the
 * {@code md} TXT field (bit 0 = text, bit 1 = artwork, bit 2 = progress). A missing
 * {@code md} means UNKNOWN: the sender may try one non-fatal probe instead of assuming
 * either way.
 */
public enum MetadataSupport {
    ADVERTISED,
    UNKNOWN,
    NOT_ADVERTISED
}
