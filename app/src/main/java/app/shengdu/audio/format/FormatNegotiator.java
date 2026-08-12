package app.shengdu.audio.format;

import java.util.List;

/** Chooses only formats that both sides explicitly support; never upsamples implicitly. */
public final class FormatNegotiator {
    private FormatNegotiator() {}

    public static AudioStreamFormat choose(
            List<AudioStreamFormat> actualCaptureFormats,
            ReceiverCapabilities receiverCapabilities) {
        if (actualCaptureFormats == null || receiverCapabilities == null) {
            return null;
        }
        for (AudioStreamFormat candidate : actualCaptureFormats) {
            if (receiverCapabilities.supports(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
