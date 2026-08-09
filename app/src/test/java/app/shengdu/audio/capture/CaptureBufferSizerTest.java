package app.shengdu.audio.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CaptureBufferSizerTest {
    @Test
    public void alignToFrameBoundaryRoundsUpPartialFrames() {
        assertEquals(8, CaptureBufferSizer.alignToFrameBoundary(5, 4));
    }

    @Test
    public void readBufferBytesMatchesRequestedFrameCount() {
        assertEquals(4_096, CaptureBufferSizer.readBufferBytes(4, 1_024));
    }

    @Test
    public void internalBufferBytesKeepsAtLeastTwoReadWindows() {
        assertEquals(8_192, CaptureBufferSizer.internalBufferBytes(3_528, 4, 1_024));
    }

}
