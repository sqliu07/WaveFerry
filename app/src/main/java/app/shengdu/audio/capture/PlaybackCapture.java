package app.shengdu.audio.capture;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * Captures system playback audio as 44.1 kHz stereo PCM16 using a supplied {@link MediaProjection}.
 */
public final class PlaybackCapture {
    public static final int SAMPLE_RATE_HZ = 44_100;
    public static final int CHANNEL_COUNT = 2;
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int FRAMES_PER_READ = 1_024;

    private static final AudioFormat AUDIO_FORMAT = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build();

    private final Object stateLock = new Object();
    private final MediaProjection mediaProjection;
    private final PcmSink pcmSink;
    private final Listener listener;
    private final MediaProjection.Callback projectionCallback = new ProjectionStopCallback();

    private volatile boolean stopRequested;
    private volatile State state = State.IDLE;

    private AudioRecord audioRecord;
    private HandlerThread projectionCallbackThread;
    private Handler projectionCallbackHandler;
    private Thread captureThread;

    public PlaybackCapture(MediaProjection mediaProjection, PcmSink pcmSink, Listener listener) {
        if (mediaProjection == null) {
            throw new IllegalArgumentException("mediaProjection == null");
        }
        if (pcmSink == null) {
            throw new IllegalArgumentException("pcmSink == null");
        }
        this.mediaProjection = mediaProjection;
        this.pcmSink = pcmSink;
        this.listener = listener;
    }

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        State current = state;
        return current == State.STARTING || current == State.STARTED || current == State.STOPPING;
    }

    public boolean start() {
        synchronized (stateLock) {
            if (state == State.STARTING || state == State.STARTED) {
                return false;
            }
            stopRequested = false;
            state = State.STARTING;
        }
        notifyStateChanged(State.STARTING);

        AudioRecord createdRecord = null;
        boolean callbackRegistered = false;
        try {
            startProjectionCallbackThread();
            mediaProjection.registerCallback(projectionCallback, projectionCallbackHandler);
            callbackRegistered = true;
            createdRecord = buildAudioRecord();
            createdRecord.startRecording();
            if (createdRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord failed to start recording");
            }
            synchronized (stateLock) {
                if (state != State.STARTING || stopRequested) {
                    audioRecord = createdRecord;
                    createdRecord = null;
                } else {
                    audioRecord = createdRecord;
                    createdRecord = null;
                    state = State.STARTED;
                    captureThread = new Thread(new CaptureLoop(audioRecord), "playback-capture");
                    captureThread.start();
                }
            }
            if (getState() == State.STARTED) {
                notifyStateChanged(State.STARTED);
                return true;
            }
            finishStop(null, false, callbackRegistered);
            callbackRegistered = false;
            return false;
        } catch (Throwable error) {
            if (createdRecord != null) {
                createdRecord.release();
            }
            finishFailure(error, callbackRegistered);
            return false;
        }
    }

    public void stop() {
        Thread threadToJoin = null;
        AudioRecord recordToStop = null;
        boolean shouldNotifyStopping = false;
        synchronized (stateLock) {
            if (state == State.IDLE || state == State.STOPPED) {
                return;
            }
            if (state != State.STOPPING) {
                stopRequested = true;
                state = State.STOPPING;
                shouldNotifyStopping = true;
            }
            threadToJoin = captureThread;
            recordToStop = audioRecord;
        }

        if (shouldNotifyStopping) {
            notifyStateChanged(State.STOPPING);
        }
        requestAudioRecordStop(recordToStop);
        joinCaptureThread(threadToJoin);
        finishStop(null, false, true);
    }

    private void startProjectionCallbackThread() {
        HandlerThread handlerThread = new HandlerThread("playback-projection-callback");
        handlerThread.start();
        projectionCallbackThread = handlerThread;
        projectionCallbackHandler = Handler.createAsync(handlerThread.getLooper());
    }

    /** The caller verifies RECORD_AUDIO before creating the projection-backed recorder. */
    @SuppressLint("MissingPermission")
    private AudioRecord buildAudioRecord() {
        AudioPlaybackCaptureConfiguration captureConfiguration =
                new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

        int minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) {
            throw new IllegalStateException("AudioRecord min buffer query failed: " + minBufferBytes);
        }

        int frameBytes = CaptureBufferSizer.bytesPerFrame(CHANNEL_COUNT, BYTES_PER_SAMPLE);
        int internalBufferBytes = CaptureBufferSizer.internalBufferBytes(
                minBufferBytes,
                frameBytes,
                FRAMES_PER_READ);

        AudioRecord record = new AudioRecord.Builder()
                .setAudioFormat(AUDIO_FORMAT)
                .setBufferSizeInBytes(internalBufferBytes)
                .setAudioPlaybackCaptureConfig(captureConfiguration)
                .build();

        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("AudioRecord failed to initialize");
        }
        validateAudioRecordFormat(record);
        return record;
    }

    private static void validateAudioRecordFormat(AudioRecord record) {
        int encoding = record.getAudioFormat();
        int sampleRate = record.getSampleRate();
        int channelCount = record.getChannelCount();
        int frameSize = record.getFormat().getFrameSizeInBytes();
        int expectedFrameSize = CHANNEL_COUNT * BYTES_PER_SAMPLE;
        if (encoding != AudioFormat.ENCODING_PCM_16BIT
                || sampleRate != SAMPLE_RATE_HZ
                || channelCount != CHANNEL_COUNT
                || frameSize != expectedFrameSize) {
            record.release();
            throw new IllegalStateException(
                    "系统返回了不兼容的播放音频格式：encoding=" + encoding
                            + ", sampleRate=" + sampleRate
                            + ", channels=" + channelCount
                            + ", frameBytes=" + frameSize);
        }
    }

    private void finishFailure(Throwable error, boolean callbackRegistered) {
        finishStop(error, true, callbackRegistered);
    }

    private void finishStop(Throwable error, boolean notifyError, boolean unregisterProjectionCallback) {
        AudioRecord recordToRelease;
        Thread captureThreadToClear;
        HandlerThread callbackThreadToQuit;
        boolean shouldNotifyStopped = false;

        synchronized (stateLock) {
            recordToRelease = audioRecord;
            captureThreadToClear = captureThread;
            callbackThreadToQuit = projectionCallbackThread;

            audioRecord = null;
            captureThread = null;
            projectionCallbackHandler = null;
            projectionCallbackThread = null;
            stopRequested = false;

            if (state != State.IDLE && state != State.STOPPED) {
                state = State.STOPPED;
                shouldNotifyStopped = true;
            }
        }

        if (captureThreadToClear != null && captureThreadToClear == Thread.currentThread()) {
            captureThreadToClear = null;
        }

        if (recordToRelease != null) {
            requestAudioRecordStop(recordToRelease);
            recordToRelease.release();
        }
        if (unregisterProjectionCallback) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (RuntimeException ignored) {
                // MediaProjection can already be torn down when stop races with system shutdown.
            }
        }
        if (callbackThreadToQuit != null) {
            callbackThreadToQuit.quitSafely();
        }
        if (notifyError) {
            notifyError(error);
        }
        if (shouldNotifyStopped) {
            notifyStateChanged(State.STOPPED);
        }
    }

    private void notifyStateChanged(State newState) {
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }

    private void notifyError(Throwable error) {
        if (listener != null && error != null) {
            listener.onError(error);
        }
    }

    private void requestAudioRecordStop(AudioRecord record) {
        if (record == null) {
            return;
        }
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException ignored) {
            // Best-effort stop while tearing down.
        }
    }

    private void joinCaptureThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public interface Listener {
        void onStateChanged(State state);

        void onError(Throwable error);
    }

    public enum State {
        IDLE,
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }

    private final class CaptureLoop implements Runnable {
        private final AudioRecord record;

        private CaptureLoop(AudioRecord record) {
            this.record = record;
        }

        @Override
        public void run() {
            int frameBytes = CaptureBufferSizer.bytesPerFrame(CHANNEL_COUNT, BYTES_PER_SAMPLE);
            short[] sampleBuffer = new short[FRAMES_PER_READ * CHANNEL_COUNT];
            byte[] pcm16LeBuffer = new byte[CaptureBufferSizer.readBufferBytes(frameBytes, FRAMES_PER_READ)];
            long framePosition = 0L;
            try {
                while (!stopRequested) {
                    int samplesRead = record.read(
                            sampleBuffer,
                            0,
                            sampleBuffer.length,
                            AudioRecord.READ_BLOCKING);
                    if (samplesRead > 0) {
                        if (samplesRead % CHANNEL_COUNT != 0) {
                            throw new IllegalStateException(
                                    "AudioRecord returned a partial PCM frame: " + samplesRead + " samples");
                        }
                        int bytesRead = Pcm16Encoding.writeLittleEndian(
                                sampleBuffer,
                                samplesRead,
                                pcm16LeBuffer);
                        pcmSink.onPcmFrame(pcm16LeBuffer, bytesRead, framePosition);
                        framePosition += samplesRead / CHANNEL_COUNT;
                        continue;
                    }
                    if (samplesRead == 0 && stopRequested) {
                        break;
                    }
                    if (samplesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("AudioRecord became unavailable");
                    }
                    if (samplesRead < 0 && !stopRequested) {
                        throw new IllegalStateException("AudioRecord.read failed: " + samplesRead);
                    }
                }
                finishStop(null, false, true);
            } catch (Throwable error) {
                finishFailure(error, true);
            }
        }
    }

    private final class ProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            stop();
        }
    }
}
