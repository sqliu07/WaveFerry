package app.shengdu.audio.capture;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class Pcm16EncodingTest {
    @Test
    public void signedSamplesBecomeExplicitLittleEndianBytes() {
        short[] samples = {(short) 0x1122, (short) 0x3344, (short) 0xff80};
        byte[] output = new byte[6];

        int byteCount = Pcm16Encoding.writeLittleEndian(samples, samples.length, output);

        assertEquals(6, byteCount);
        assertArrayEquals(
                new byte[]{0x22, 0x11, 0x44, 0x33, (byte) 0x80, (byte) 0xff},
                output);
    }
}
