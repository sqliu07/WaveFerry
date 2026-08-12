package app.shengdu.audio.dlna;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DlnaSinkCapabilitiesTest {
    @Test
    public void recognizesWavAndWildcardSinks() {
        assertTrue(DlnaSinkCapabilities.parse(
                "http-get:*:audio/wav:*,http-get:*:audio/mpeg:*").supportsLiveWav());
        assertTrue(DlnaSinkCapabilities.parse("http-get:*:*:*").supportsLiveWav());
    }

    @Test
    public void rejectsMpegOnlySink() {
        assertFalse(DlnaSinkCapabilities.parse("http-get:*:audio/mpeg:*").supportsLiveWav());
    }

    @Test
    public void exposesAdvisoryWavSupportWithoutTreatingAnEmptyReportAsRefusal() {
        assertEquals(
                DlnaSinkCapabilities.WavSupport.ADVERTISED,
                DlnaSinkCapabilities.parse("http-get:*:audio/wav:*").wavSupport());
        assertEquals(
                DlnaSinkCapabilities.WavSupport.NOT_ADVERTISED,
                DlnaSinkCapabilities.parse("http-get:*:audio/mpeg:*").wavSupport());
        assertEquals(
                DlnaSinkCapabilities.WavSupport.UNKNOWN,
                DlnaSinkCapabilities.parse("").wavSupport());
    }
}
