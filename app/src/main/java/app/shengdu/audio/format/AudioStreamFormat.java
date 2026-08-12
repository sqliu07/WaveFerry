package app.shengdu.audio.format;

import java.util.Objects;

/** Immutable description of interleaved PCM carried through the WaveFerry pipeline. */
public final class AudioStreamFormat {
    private final int sampleRateHz;
    private final int channelCount;
    private final SampleFormat sampleFormat;

    public AudioStreamFormat(int sampleRateHz, int channelCount, SampleFormat sampleFormat) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive");
        }
        if (channelCount <= 0) {
            throw new IllegalArgumentException("channelCount must be positive");
        }
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.sampleFormat = Objects.requireNonNull(sampleFormat, "sampleFormat");
    }

    public int sampleRateHz() {
        return sampleRateHz;
    }

    public int channelCount() {
        return channelCount;
    }

    public SampleFormat sampleFormat() {
        return sampleFormat;
    }

    public int bytesPerFrame() {
        return channelCount * sampleFormat.bytesPerSample();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AudioStreamFormat)) {
            return false;
        }
        AudioStreamFormat that = (AudioStreamFormat) other;
        return sampleRateHz == that.sampleRateHz
                && channelCount == that.channelCount
                && sampleFormat == that.sampleFormat;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleRateHz, channelCount, sampleFormat);
    }

    @Override
    public String toString() {
        return sampleFormat + "/" + sampleRateHz + "/" + channelCount + "ch";
    }
}
