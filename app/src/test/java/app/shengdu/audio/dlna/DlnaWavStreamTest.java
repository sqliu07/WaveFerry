package app.shengdu.audio.dlna;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

import org.junit.Test;

public final class DlnaWavStreamTest {
    @Test
    public void buildsPcm16StereoWavHeader() {
        byte[] header = DlnaWavStream.header(
                new AudioStreamFormat(48_000, 2, SampleFormat.PCM16_LE));

        assertEquals(44, header.length);
        assertArrayEquals(new byte[] {'R', 'I', 'F', 'F'}, slice(header, 0, 4));
        assertArrayEquals(new byte[] {'W', 'A', 'V', 'E'}, slice(header, 8, 4));
        assertEquals(0x80, header[24] & 0xff); // 48,000 little-endian
        assertEquals(0xbb, header[25] & 0xff);
        assertEquals(16, header[34] & 0xff);
    }

    private static byte[] slice(byte[] source, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, offset, result, 0, length);
        return result;
    }
}
