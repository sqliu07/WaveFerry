package app.shengdu.audio.raop;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Encodes RAOP 1 text metadata bodies ({@code application/x-dmap-tagged}).
 *
 * Layout, locked by golden-byte tests and matching the iTunes wire format used by
 * libraop (AirConnect/LMS): an outer {@code mlit} container whose first item is
 * {@code mikd} (item kind, 1 byte, 2 = song), followed by {@code minm} (track name),
 * {@code asar} (artist) and {@code asal} (album). Every item is
 * {@code 4-byte ASCII tag + 4-byte big-endian byte length + UTF-8 payload}.
 * Empty fields are emitted as zero-length items; length-respecting DMAP parsers skip
 * tags they do not know, so {@code mikd} is safe for minimal receivers.
 */
final class DmapEncoder {
    private static final byte[] TAG_MLIT = {'m', 'l', 'i', 't'};
    private static final byte[] TAG_MIKD = {'m', 'i', 'k', 'd'};
    private static final byte[] TAG_MINM = {'m', 'i', 'n', 'm'};
    private static final byte[] TAG_ASAR = {'a', 's', 'a', 'r'};
    private static final byte[] TAG_ASAL = {'a', 's', 'a', 'l'};
    private static final byte ITEM_KIND_SONG = 0x02;

    private DmapEncoder() {
    }

    static byte[] encodeTextMetadata(String title, String artist, String album) {
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        writeItem(items, TAG_MIKD, new byte[] {ITEM_KIND_SONG});
        writeItem(items, TAG_MINM, utf8(title));
        writeItem(items, TAG_ASAR, utf8(artist));
        writeItem(items, TAG_ASAL, utf8(album));

        byte[] itemBytes = items.toByteArray();
        ByteArrayOutputStream container = new ByteArrayOutputStream(8 + itemBytes.length);
        writeItem(container, TAG_MLIT, itemBytes);
        return container.toByteArray();
    }

    private static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static void writeItem(ByteArrayOutputStream out, byte[] tag, byte[] payload) {
        out.write(tag, 0, tag.length);
        int length = payload.length;
        out.write((length >>> 24) & 0xff);
        out.write((length >>> 16) & 0xff);
        out.write((length >>> 8) & 0xff);
        out.write(length & 0xff);
        out.write(payload, 0, payload.length);
    }
}
