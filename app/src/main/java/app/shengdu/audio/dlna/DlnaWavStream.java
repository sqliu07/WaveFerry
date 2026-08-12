package app.shengdu.audio.dlna;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

/** Generates the fixed header used before live PCM data in the DLNA WAV stream. */
final class DlnaWavStream {
    static final int LIVE_CONTENT_LENGTH_BYTES = Integer.MAX_VALUE;
    private static final int HEADER_BYTES = 44;

    private DlnaWavStream() {
    }

    static byte[] header(AudioStreamFormat format) {
        if (format.sampleFormat() != SampleFormat.PCM16_LE || format.channelCount() != 2) {
            throw new IllegalArgumentException("DLNA WAV currently requires PCM16 stereo");
        }
        int sampleRate = format.sampleRateHz();
        int byteRate = sampleRate * format.bytesPerFrame();
        int dataBytes = LIVE_CONTENT_LENGTH_BYTES - HEADER_BYTES;
        byte[] header = new byte[HEADER_BYTES];
        ascii(header, 0, "RIFF");
        littleEndian(header, 4, dataBytes + 36);
        ascii(header, 8, "WAVEfmt ");
        littleEndian(header, 16, 16);
        littleEndianShort(header, 20, 1);
        littleEndianShort(header, 22, format.channelCount());
        littleEndian(header, 24, sampleRate);
        littleEndian(header, 28, byteRate);
        littleEndianShort(header, 32, format.bytesPerFrame());
        littleEndianShort(header, 34, format.sampleFormat().bitsPerSample());
        ascii(header, 36, "data");
        littleEndian(header, 40, dataBytes);
        return header;
    }

    private static void ascii(byte[] target, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            target[offset + index] = (byte) value.charAt(index);
        }
    }

    private static void littleEndian(byte[] target, int offset, int value) {
        for (int index = 0; index < 4; index++) {
            target[offset + index] = (byte) (value >>> (index * 8));
        }
    }

    private static void littleEndianShort(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
    }
}
