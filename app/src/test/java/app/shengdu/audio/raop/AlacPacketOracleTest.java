package app.shengdu.audio.raop;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;

public class AlacPacketOracleTest {
    private static final int DATA_CHUNK_OFFSET = 0xae;
    private static final int DATA_PAYLOAD_OFFSET = DATA_CHUNK_OFFSET + 16;

    @Test
    public void ffmpegOracleDecodesTcpAlacPacketBackToOriginalPcm() throws Exception {
        Assume.assumeTrue("ffmpeg is required for this oracle test", isCommandAvailable("ffmpeg"));

        byte[] pcm = oraclePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
        byte[] packet = AlacPacketBuilder.encodeUncompressedFrames(pcm, 0, pcm.length);

        Path tempDir = Files.createTempDirectory("alac-oracle");
        Path inputPcm = tempDir.resolve("input.pcm");
        Path templateCaf = tempDir.resolve("template.caf");
        Path oracleCaf = tempDir.resolve("oracle.caf");
        Path outputPcm = tempDir.resolve("output.pcm");

        Files.write(inputPcm, pcm);
        run(
                List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "s16le",
                        "-ar",
                        "44100",
                        "-ac",
                        "2",
                        "-i",
                        inputPcm.toString(),
                        "-c:a",
                        "alac",
                        templateCaf.toString()));

        Files.write(oracleCaf, wrapSinglePacketCaf(Files.readAllBytes(templateCaf), packet));

        try {
            run(
                    List.of(
                            "ffmpeg",
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-y",
                            "-i",
                            oracleCaf.toString(),
                            "-f",
                            "s16le",
                            outputPcm.toString()));
        } catch (IOException exception) {
            fail("ffmpeg rejected the generated ALAC packet: " + exception.getMessage());
        }

        assertArrayEquals(pcm, Files.readAllBytes(outputPcm));
    }

    @Test
    public void rewrappedFfmpegPacketStillDecodesViaTheOracleContainer() throws Exception {
        Assume.assumeTrue("ffmpeg is required for this oracle test", isCommandAvailable("ffmpeg"));

        byte[] pcm = oraclePcm(AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
        Path tempDir = Files.createTempDirectory("alac-oracle-template");
        Path inputPcm = tempDir.resolve("input.pcm");
        Path templateCaf = tempDir.resolve("template.caf");
        Path rewrappedCaf = tempDir.resolve("rewrapped.caf");
        Path outputPcm = tempDir.resolve("output.pcm");

        Files.write(inputPcm, pcm);
        run(
                List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "s16le",
                        "-ar",
                        "44100",
                        "-ac",
                        "2",
                        "-i",
                        inputPcm.toString(),
                        "-c:a",
                        "alac",
                        templateCaf.toString()));

        byte[] templateBytes = Files.readAllBytes(templateCaf);
        byte[] templatePacket = extractSinglePacket(templateBytes);
        Files.write(rewrappedCaf, wrapSinglePacketCaf(templateBytes, templatePacket));

        run(
                List.of(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-i",
                        rewrappedCaf.toString(),
                        "-f",
                        "s16le",
                        outputPcm.toString()));

        assertArrayEquals(pcm, Files.readAllBytes(outputPcm));
    }

    private static byte[] wrapSinglePacketCaf(byte[] templateCaf, byte[] packet) throws IOException {
        if (templateCaf.length <= DATA_CHUNK_OFFSET) {
            throw new IOException("CAF template is shorter than expected");
        }

        ByteArrayOutputStream caf = new ByteArrayOutputStream();
        caf.write(templateCaf, 0, DATA_CHUNK_OFFSET);

        byte[] dataPayload = new byte[4 + packet.length];
        System.arraycopy(packet, 0, dataPayload, 4, packet.length);

        caf.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLong(caf, dataPayload.length);
        caf.write(dataPayload);

        byte[] packetSize = encodeCafVarInt(packet.length);
        ByteArrayOutputStream pakt = new ByteArrayOutputStream();
        writeLong(pakt, 1);
        writeLong(pakt, AlacPacketBuilder.FRAMES_PER_TCP_PACKET);
        writeInt(pakt, 0);
        writeInt(pakt, 0);
        pakt.write(packetSize);

        caf.write("pakt".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeLong(caf, pakt.size());
        caf.write(pakt.toByteArray());
        return caf.toByteArray();
    }

    private static byte[] extractSinglePacket(byte[] caf) throws IOException {
        if (caf.length < DATA_PAYLOAD_OFFSET) {
            throw new IOException("CAF file is shorter than expected");
        }

        long chunkSize = readLong(caf, DATA_CHUNK_OFFSET + 4);
        if (chunkSize < 4) {
            throw new IOException("CAF data chunk is too small: " + chunkSize);
        }

        int packetLength = (int) chunkSize - 4;
        byte[] packet = new byte[packetLength];
        System.arraycopy(caf, DATA_PAYLOAD_OFFSET, packet, 0, packetLength);
        return packet;
    }

    private static byte[] encodeCafVarInt(int value) {
        List<Byte> bytes = new ArrayList<>();
        bytes.add((byte) (value & 0x7f));
        value >>>= 7;
        while (value != 0) {
            bytes.add(0, (byte) (0x80 | (value & 0x7f)));
            value >>>= 7;
        }

        byte[] encoded = new byte[bytes.size()];
        for (int index = 0; index < bytes.size(); index++) {
            encoded[index] = bytes.get(index);
        }
        return encoded;
    }

    private static void writeInt(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeLong(ByteArrayOutputStream output, long value) {
        output.write((int) ((value >>> 56) & 0xff));
        output.write((int) ((value >>> 48) & 0xff));
        output.write((int) ((value >>> 40) & 0xff));
        output.write((int) ((value >>> 32) & 0xff));
        output.write((int) ((value >>> 24) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) (value & 0xff));
    }

    private static long readLong(byte[] input, int offset) {
        return ((long) (input[offset] & 0xff) << 56)
                | ((long) (input[offset + 1] & 0xff) << 48)
                | ((long) (input[offset + 2] & 0xff) << 40)
                | ((long) (input[offset + 3] & 0xff) << 32)
                | ((long) (input[offset + 4] & 0xff) << 24)
                | ((long) (input[offset + 5] & 0xff) << 16)
                | ((long) (input[offset + 6] & 0xff) << 8)
                | (input[offset + 7] & 0xffL);
    }

    private static byte[] oraclePcm(int frames) {
        byte[] pcm = new byte[frames * AlacPacketBuilder.PCM_BYTES_PER_FRAME];
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * AlacPacketBuilder.PCM_BYTES_PER_FRAME;
            int left = 0x2211 + frame;
            int right = 0x4433 + (frame * 2);
            pcm[offset] = (byte) (left & 0xff);
            pcm[offset + 1] = (byte) ((left >>> 8) & 0xff);
            pcm[offset + 2] = (byte) (right & 0xff);
            pcm[offset + 3] = (byte) ((right >>> 8) & 0xff);
        }
        return pcm;
    }

    private static boolean isCommandAvailable(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("sh", "-lc", "command -v " + command).start();
        return process.waitFor() == 0;
    }

    private static void run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        String stderr;
        try (InputStream error = process.getErrorStream()) {
            stderr = new String(error.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (process.waitFor() != 0) {
            throw new IOException(String.join(" ", command) + " failed: " + stderr.trim());
        }
    }
}
