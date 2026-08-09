package app.shengdu.audio.capture;

/**
 * Receives captured PCM16 little-endian stereo frames on the playback capture thread.
 *
 * <p>The provided buffer is reused by {@link PlaybackCapture}. Implementations that need to
 * retain frame data after this callback returns must copy it.
 */
public interface PcmSink {
    void onPcmFrame(byte[] pcm16Le, int byteCount, long framePosition);
}
