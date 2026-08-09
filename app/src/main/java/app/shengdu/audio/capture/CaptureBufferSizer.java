package app.shengdu.audio.capture;

final class CaptureBufferSizer {
    private CaptureBufferSizer() {
    }

    static int bytesPerFrame(int channelCount, int bytesPerSample) {
        if (channelCount <= 0 || bytesPerSample <= 0) {
            throw new IllegalArgumentException("channelCount and bytesPerSample must be > 0");
        }
        return channelCount * bytesPerSample;
    }

    static int alignToFrameBoundary(int byteCount, int frameBytes) {
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must be >= 0");
        }
        if (frameBytes <= 0) {
            throw new IllegalArgumentException("frameBytes must be > 0");
        }
        if (byteCount == 0) {
            return frameBytes;
        }
        int remainder = byteCount % frameBytes;
        if (remainder == 0) {
            return byteCount;
        }
        return byteCount + (frameBytes - remainder);
    }

    static int readBufferBytes(int frameBytes, int framesPerRead) {
        if (framesPerRead <= 0) {
            throw new IllegalArgumentException("framesPerRead must be > 0");
        }
        return alignToFrameBoundary(frameBytes * framesPerRead, frameBytes);
    }

    static int internalBufferBytes(int minBufferBytes, int frameBytes, int framesPerRead) {
        if (minBufferBytes <= 0) {
            throw new IllegalArgumentException("minBufferBytes must be > 0");
        }
        int alignedMinBuffer = alignToFrameBoundary(minBufferBytes, frameBytes);
        int readBuffer = readBufferBytes(frameBytes, framesPerRead);
        return Math.max(alignedMinBuffer, readBuffer * 2);
    }
}
