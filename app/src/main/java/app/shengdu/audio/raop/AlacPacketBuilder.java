/* SPDX-License-Identifier: LGPL-2.1-or-later */
package app.shengdu.audio.raop;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

final class AlacPacketBuilder {
    static final int FRAMES_PER_TCP_PACKET = 4096;
    static final int FRAMES_PER_UDP_PACKET = 352;
    static final int FRAMES_PER_PACKET = FRAMES_PER_TCP_PACKET;
    static final int PCM_BYTES_PER_FRAME = 4;
    static final int PCM_BYTES_PER_TCP_PACKET = FRAMES_PER_TCP_PACKET * PCM_BYTES_PER_FRAME;
    static final int PCM_BYTES_PER_UDP_PACKET = FRAMES_PER_UDP_PACKET * PCM_BYTES_PER_FRAME;
    static final int PCM_BYTES_PER_PACKET = PCM_BYTES_PER_TCP_PACKET;
    static final int ALAC_PACKET_OVERHEAD_BYTES = 8;

    private AlacPacketBuilder() {
    }

    static byte[] encodeUncompressedFrame(byte[] pcm, int offset, int length) {
        return encodeUncompressedFrames(pcm, offset, length);
    }

    static byte[] encodeUncompressedFrames(byte[] pcm, int offset, int length) {
        return encodeUncompressedFrames(
                new AudioStreamFormat(44_100, 2, SampleFormat.PCM16_LE), pcm, offset, length);
    }

    static byte[] encodeUncompressedFrames(
            AudioStreamFormat format, byte[] pcm, int offset, int length) {
        if (format == null
                || format.channelCount() != 2
                || format.sampleFormat() != SampleFormat.PCM16_LE) {
            throw new IllegalArgumentException("RAOP ALAC currently supports PCM16 stereo only");
        }
        if (length <= 0 || length % PCM_BYTES_PER_FRAME != 0) {
            throw new IllegalArgumentException(
                    "RAOP ALAC packets require PCM bytes in full stereo PCM16 frames");
        }

        byte[] packet = new byte[ALAC_PACKET_OVERHEAD_BYTES + length];
        // The uncompressed ALAC header is 55 bits. PCM and the 3-bit end tag
        // must continue in the same bit stream instead of starting on byte 7.
        BitWriter writer = new BitWriter(packet);
        writeHeader(writer, length / PCM_BYTES_PER_FRAME);

        for (int in = offset; in < offset + length; in += PCM_BYTES_PER_FRAME) {
            writer.write(pcm[in + 1] & 0xff, 8);
            writer.write(pcm[in] & 0xff, 8);
            writer.write(pcm[in + 3] & 0xff, 8);
            writer.write(pcm[in + 2] & 0xff, 8);
        }
        writer.write(7, 3);

        return packet;
    }

    private static void writeHeader(BitWriter writer, int frames) {
        writer.write(1, 3);
        writer.write(0, 4);
        writer.write(0, 8);
        writer.write(0, 4);
        writer.write(1, 1);
        writer.write(0, 2);
        writer.write(1, 1);
        writer.write((frames >>> 24) & 0xff, 8);
        writer.write((frames >>> 16) & 0xff, 8);
        writer.write((frames >>> 8) & 0xff, 8);
        writer.write(frames & 0xff, 8);
    }

    private static final class BitWriter {
        private final byte[] buffer;
        private int index;
        private int bitPos;

        private BitWriter(byte[] buffer) {
            this.buffer = buffer;
        }

        private void write(int data, int dataBitLen) {
            if (dataBitLen == 0) {
                return;
            }
            if (bitPos == 0) {
                buffer[index] = 0;
            }

            int bitsLeft = 8 - bitPos;
            int bitOverflow = bitsLeft - dataBitLen;
            if (bitOverflow >= 0) {
                int bitData = (data << bitOverflow) & 0xff;
                buffer[index] = (byte) (buffer[index] | bitData);
                if (bitOverflow == 0) {
                    index++;
                    bitPos = 0;
                } else {
                    bitPos += dataBitLen;
                }
            } else {
                int bitData = (data >> -bitOverflow) & 0xff;
                buffer[index] = (byte) (buffer[index] | bitData);
                index++;
                buffer[index] = (byte) ((data << (8 + bitOverflow)) & 0xff);
                bitPos = -bitOverflow;
            }
        }
    }
}
