package app.shengdu.audio.raop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class DmapEncoderTest {
    @Test
    public void textMetadataUsesMlitContainerWithMikdItemKindAndUtf8Items() {
        byte[] encoded = DmapEncoder.encodeTextMetadata("Song", "Artist", "Album");

        // mikd(9) + minm(12) + asar(14) + asal(13) = 48 bytes of items inside mlit.
        byte[] golden = new byte[] {
                'm', 'l', 'i', 't', 0, 0, 0, 48,
                'm', 'i', 'k', 'd', 0, 0, 0, 1, 0x02,
                'm', 'i', 'n', 'm', 0, 0, 0, 4, 'S', 'o', 'n', 'g',
                'a', 's', 'a', 'r', 0, 0, 0, 6, 'A', 'r', 't', 'i', 's', 't',
                'a', 's', 'a', 'l', 0, 0, 0, 5, 'A', 'l', 'b', 'u', 'm'
        };
        assertArrayEquals(golden, encoded);
    }

    @Test
    public void chineseTitlesAreUtf8EncodedWithByteLengths() {
        byte[] encoded = DmapEncoder.encodeTextMetadata("歌名", "", "");

        byte[] title = "歌名".getBytes(StandardCharsets.UTF_8);
        assertEquals(6, title.length);
        int minmTagOffset = 8 + 9;
        assertEquals('m', encoded[minmTagOffset]);
        assertEquals(0, encoded[minmTagOffset + 4]);
        assertEquals(0, encoded[minmTagOffset + 5]);
        assertEquals(0, encoded[minmTagOffset + 6]);
        assertEquals(6, encoded[minmTagOffset + 7]);
        for (int i = 0; i < title.length; i++) {
            assertEquals(title[i], encoded[minmTagOffset + 8 + i]);
        }
    }

    @Test
    public void emptyAndNullFieldsEmitZeroLengthItems() {
        byte[] encoded = DmapEncoder.encodeTextMetadata(null, null, null);

        // mlit(13) wrapping only mikd; minm/asar/asal are present with zero length.
        byte[] golden = new byte[] {
                'm', 'l', 'i', 't', 0, 0, 0, 33,
                'm', 'i', 'k', 'd', 0, 0, 0, 1, 0x02,
                'm', 'i', 'n', 'm', 0, 0, 0, 0,
                'a', 's', 'a', 'r', 0, 0, 0, 0,
                'a', 's', 'a', 'l', 0, 0, 0, 0
        };
        assertArrayEquals(golden, encoded);
    }

    @Test
    public void lengthFieldsAreBigEndianForLongTitles() {
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            title.append('x');
        }

        byte[] encoded = DmapEncoder.encodeTextMetadata(title.toString(), "", "");

        int minmTagOffset = 8 + 9;
        assertEquals(0x00, encoded[minmTagOffset + 4] & 0xff);
        assertEquals(0x00, encoded[minmTagOffset + 5] & 0xff);
        assertEquals(0x01, encoded[minmTagOffset + 6] & 0xff);
        assertEquals(0x2C, encoded[minmTagOffset + 7] & 0xff);
        // mlit length covers mikd(9) + minm(8+300) + asar(8) + asal(8) = 333.
        assertEquals(0x00, encoded[4] & 0xff);
        assertEquals(0x00, encoded[5] & 0xff);
        assertEquals(0x01, encoded[6] & 0xff);
        assertEquals(0x4D, encoded[7] & 0xff);
    }
}
