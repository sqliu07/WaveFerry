package app.shengdu.audio.format;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.Set;
import org.junit.Test;

public final class FormatNegotiatorTest {
    @Test
    public void choosesFirstSupportedActualFormat() {
        AudioStreamFormat rate48 = new AudioStreamFormat(48000, 2, SampleFormat.PCM16_LE);
        AudioStreamFormat rate44 = new AudioStreamFormat(44100, 2, SampleFormat.PCM16_LE);
        ReceiverCapabilities receiver = new ReceiverCapabilities(
                Set.of(44100, 48000), Set.of(16), Set.of(2));

        assertEquals(rate48, FormatNegotiator.choose(Arrays.asList(rate48, rate44), receiver));
    }

    @Test
    public void doesNotUpsampleAnUnavailableCaptureRate() {
        AudioStreamFormat rate44 = new AudioStreamFormat(44100, 2, SampleFormat.PCM16_LE);
        ReceiverCapabilities receiver = new ReceiverCapabilities(
                Set.of(48000), Set.of(16), Set.of(2));

        assertNull(FormatNegotiator.choose(Arrays.asList(rate44), receiver));
    }

    @Test
    public void derivesFrameBytesFromFormat() {
        assertEquals(4, new AudioStreamFormat(44100, 2, SampleFormat.PCM16_LE).bytesPerFrame());
        assertEquals(6, new AudioStreamFormat(48000, 2, SampleFormat.PCM24_PACKED_LE).bytesPerFrame());
    }

    @Test
    public void parsesAdvertisedCapabilityLists() {
        ReceiverCapabilities receiver = ReceiverCapabilities.fromTxt(Map.of(
                "sr", "44100,48000".getBytes(StandardCharsets.US_ASCII),
                "ss", "16".getBytes(StandardCharsets.US_ASCII),
                "ch", "2".getBytes(StandardCharsets.US_ASCII)));

        assertEquals(Set.of(44100, 48000), receiver.sampleRatesHz());
        assertEquals(Set.of(16), receiver.sampleSizes());
        assertEquals(Set.of(2), receiver.channelCounts());
    }
}
