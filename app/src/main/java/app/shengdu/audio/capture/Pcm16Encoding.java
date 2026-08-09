package app.shengdu.audio.capture;

/** Converts signed PCM16 samples to an explicit byte representation. */
final class Pcm16Encoding {
    private Pcm16Encoding() {
    }

    static int writeLittleEndian(short[] samples, int sampleCount, byte[] output) {
        if (sampleCount < 0 || sampleCount > samples.length) {
            throw new IndexOutOfBoundsException("sampleCount=" + sampleCount);
        }
        if (sampleCount > output.length / 2) {
            throw new IndexOutOfBoundsException(
                    "output is too small for " + sampleCount + " PCM16 samples: " + output.length);
        }
        int byteCount = sampleCount * 2;
        for (int index = 0; index < sampleCount; index++) {
            int value = samples[index] & 0xffff;
            int outputIndex = index * 2;
            output[outputIndex] = (byte) value;
            output[outputIndex + 1] = (byte) (value >>> 8);
        }
        return byteCount;
    }
}
