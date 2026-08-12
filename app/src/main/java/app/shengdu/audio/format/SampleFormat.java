package app.shengdu.audio.format;

/** PCM representations that WaveFerry can describe on its audio path. */
public enum SampleFormat {
    PCM16_LE(16, 2),
    PCM24_PACKED_LE(24, 3),
    PCM32_LE(32, 4),
    PCM_FLOAT_LE(32, 4);

    private final int bitsPerSample;
    private final int bytesPerSample;

    SampleFormat(int bitsPerSample, int bytesPerSample) {
        this.bitsPerSample = bitsPerSample;
        this.bytesPerSample = bytesPerSample;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }

    public int bytesPerSample() {
        return bytesPerSample;
    }
}
