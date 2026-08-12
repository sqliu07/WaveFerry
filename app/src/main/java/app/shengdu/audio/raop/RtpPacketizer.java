/* SPDX-License-Identifier: LGPL-2.1-or-later */
package app.shengdu.audio.raop;

import java.security.GeneralSecurityException;

final class RtpPacketizer {
    private static final long NTP_UNIX_EPOCH_OFFSET_SECONDS = 0x83aa7e80L;
    private static final int UDP_TIMING_PACKET_BYTES = 32;

    private RtpPacketizer() {
    }

    static byte[] buildTcpPacket(
            int sequenceNumber,
            long timestamp,
            long ssrc,
            byte[] payload,
            RaopCrypto crypto) throws GeneralSecurityException {
        byte[] packet = new byte[16 + payload.length];
        int rtpLength = packet.length - 4;

        packet[0] = 0x24;
        packet[1] = 0x00;
        packet[2] = (byte) ((rtpLength >>> 8) & 0xff);
        packet[3] = (byte) (rtpLength & 0xff);
        packet[4] = (byte) 0x80;
        packet[5] = (byte) 0xe0;
        packet[6] = (byte) ((sequenceNumber >>> 8) & 0xff);
        packet[7] = (byte) (sequenceNumber & 0xff);
        writeInt(packet, 8, timestamp);
        writeInt(packet, 12, ssrc);
        System.arraycopy(payload, 0, packet, 16, payload.length);

        if (crypto != null) {
            crypto.encryptAudioPayload(packet, 16, payload.length);
        }
        return packet;
    }

    static byte[] buildUdpAudioPacket(
            int sequenceNumber,
            long timestamp,
            long ssrc,
            byte[] payload,
            RaopCrypto crypto,
            boolean marker) throws GeneralSecurityException {
        byte[] packet = new byte[12 + payload.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) (marker ? 0xe0 : 0x60);
        packet[2] = (byte) ((sequenceNumber >>> 8) & 0xff);
        packet[3] = (byte) (sequenceNumber & 0xff);
        writeInt(packet, 4, timestamp);
        writeInt(packet, 8, ssrc);
        System.arraycopy(payload, 0, packet, 12, payload.length);

        if (crypto != null) {
            crypto.encryptAudioPayload(packet, 12, payload.length);
        }
        return packet;
    }

    static byte[] buildUdpSyncPacket(long timestamp, boolean firstPacket) {
        return buildUdpSyncPacket(timestamp, firstPacket, 44_100);
    }

    static byte[] buildUdpSyncPacket(long timestamp, boolean firstPacket, int sampleRateHz) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive");
        }
        byte[] packet = new byte[20];
        packet[0] = (byte) (firstPacket ? 0x90 : 0x80);
        packet[1] = (byte) 0xd4;
        packet[2] = 0x00;
        packet[3] = 0x07;

        long delayed = (timestamp - 2L * sampleRateHz) & 0xffffffffL;
        long nowNtp = currentNtpTimestamp();
        writeInt(packet, 4, delayed);
        writeLong32(packet, 8, nowNtp >>> 32);
        writeLong32(packet, 12, nowNtp);
        writeInt(packet, 16, timestamp);
        return packet;
    }

    static byte[] buildInitialUdpTimingPacket() {
        byte[] packet = new byte[32];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) 0xd3;
        packet[2] = 0x00;
        packet[3] = 0x07;

        long nowNtp = currentNtpTimestamp();
        writeLong32(packet, 8, nowNtp >>> 32);
        writeLong32(packet, 12, nowNtp);
        writeLong32(packet, 16, nowNtp >>> 32);
        writeLong32(packet, 20, nowNtp);
        writeLong32(packet, 24, nowNtp >>> 32);
        writeLong32(packet, 28, nowNtp);
        return packet;
    }

    static byte[] buildUdpTimingReply(byte[] request, long receiveNtp, long transmitNtp) {
        if (request.length < UDP_TIMING_PACKET_BYTES) {
            throw new IllegalArgumentException("UDP timing request must be 32 bytes");
        }
        byte[] packet = new byte[UDP_TIMING_PACKET_BYTES];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) 0xd3;
        packet[2] = 0x00;
        packet[3] = 0x07;
        System.arraycopy(request, 24, packet, 8, 8);
        writeLong32(packet, 16, receiveNtp >>> 32);
        writeLong32(packet, 20, receiveNtp);
        writeLong32(packet, 24, transmitNtp >>> 32);
        writeLong32(packet, 28, transmitNtp);
        return packet;
    }

    static byte[] buildUdpRetransmitReply(int sequenceNumber, byte[] audioPacket) {
        byte[] packet = new byte[4 + audioPacket.length];
        packet[0] = (byte) 0x80;
        packet[1] = (byte) 0xd6;
        packet[2] = (byte) ((sequenceNumber >>> 8) & 0xff);
        packet[3] = (byte) (sequenceNumber & 0xff);
        System.arraycopy(audioPacket, 0, packet, 4, audioPacket.length);
        return packet;
    }

    private static void writeInt(byte[] packet, int offset, long value) {
        packet[offset] = (byte) ((value >>> 24) & 0xff);
        packet[offset + 1] = (byte) ((value >>> 16) & 0xff);
        packet[offset + 2] = (byte) ((value >>> 8) & 0xff);
        packet[offset + 3] = (byte) (value & 0xff);
    }

    private static void writeLong32(byte[] packet, int offset, long value) {
        writeInt(packet, offset, value & 0xffffffffL);
    }

    static long currentNtpTimestamp() {
        long nowMillis = System.currentTimeMillis();
        long seconds = nowMillis / 1000L;
        long millisRemainder = nowMillis % 1000L;
        long fraction = (millisRemainder * 0x1_0000_0000L) / 1000L;
        return ((seconds + NTP_UNIX_EPOCH_OFFSET_SECONDS) << 32) | (fraction & 0xffffffffL);
    }
}
