package app.shengdu.audio.capture;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;

import app.shengdu.audio.format.AudioStreamFormat;
import app.shengdu.audio.format.SampleFormat;

/**
 * Captures system playback audio as interleaved PCM16 using a supplied {@link MediaProjection}.
 */
public final class PlaybackCapture {
    public static final int SAMPLE_RATE_HZ = 44_100;
    public static final int CHANNEL_COUNT = 2;
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int FRAMES_PER_READ = 1_024;
    private static final long CAPTURE_THREAD_JOIN_TIMEOUT_MS = 1_000L;

    public static final AudioStreamFormat DEFAULT_FORMAT =
            new AudioStreamFormat(SAMPLE_RATE_HZ, CHANNEL_COUNT, SampleFormat.PCM16_LE);

    private final Object stateLock = new Object();
    private final MediaProjection mediaProjection;
    private final PcmSink pcmSink;
    private final Listener listener;
    private final AudioStreamFormat requestedFormat;

    private volatile boolean stopRequested;
    private volatile State state = State.IDLE;
    private volatile long activeRunGeneration;
    private long nextRunGeneration;

    private AudioRecord audioRecord;
    private MediaProjection.Callback projectionCallback;
    private HandlerThread projectionCallbackThread;
    private Thread captureThread;

    public PlaybackCapture(MediaProjection mediaProjection, PcmSink pcmSink, Listener listener) {
        this(mediaProjection, pcmSink, listener, DEFAULT_FORMAT);
    }

    public PlaybackCapture(
            MediaProjection mediaProjection,
            PcmSink pcmSink,
            Listener listener,
            AudioStreamFormat requestedFormat) {
        if (mediaProjection == null) {
            throw new IllegalArgumentException("mediaProjection == null");
        }
        if (pcmSink == null) {
            throw new IllegalArgumentException("pcmSink == null");
        }
        if (requestedFormat == null
                || requestedFormat.channelCount() != CHANNEL_COUNT
                || requestedFormat.sampleFormat() != SampleFormat.PCM16_LE) {
            throw new IllegalArgumentException("Playback capture currently supports PCM16 stereo only");
        }
        this.mediaProjection = mediaProjection;
        this.pcmSink = pcmSink;
        this.listener = listener;
        this.requestedFormat = requestedFormat;
    }

    public AudioStreamFormat requestedFormat() {
        return requestedFormat;
    }

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        State current = state;
        return current == State.STARTING || current == State.STARTED || current == State.STOPPING;
    }

    public boolean start() {
        final long generation;
        synchronized (stateLock) {
            if (state == State.STARTING || state == State.STARTED || state == State.STOPPING) {
                return false;
            }
            generation = ++nextRunGeneration;
            activeRunGeneration = generation;
            stopRequested = false;
            state = State.STARTING;
        }
        notifyStateChanged(State.STARTING);

        AudioRecord createdRecord = null;
        HandlerThread createdCallbackThread = null;
        MediaProjection.Callback createdProjectionCallback = null;
        boolean callbackRegistered = false;
        try {
            createdCallbackThread = new HandlerThread("playback-projection-callback");
            createdCallbackThread.start();
            Handler createdCallbackHandler = Handler.createAsync(createdCallbackThread.getLooper());
            createdProjectionCallback = new ProjectionStopCallback(generation);
            mediaProjection.registerCallback(createdProjectionCallback, createdCallbackHandler);
            callbackRegistered = true;
            createdRecord = buildAudioRecord();
            createdRecord.startRecording();
            if (createdRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("AudioRecord failed to start recording");
            }
            synchronized (stateLock) {
                if (activeRunGeneration == generation
                        && state == State.STARTING
                        && !stopRequested) {
                    audioRecord = createdRecord;
                    createdRecord = null;
                    projectionCallback = createdProjectionCallback;
                    projectionCallbackThread = createdCallbackThread;
                    createdCallbackThread = null;
                    callbackRegistered = false;
                    state = State.STARTED;
                    captureThread = new Thread(
                            new CaptureLoop(audioRecord, generation), "playback-capture");
                    captureThread.start();
                }
            }
            if (activeRunGeneration == generation && getState() == State.STARTED) {
                notifyStateChanged(State.STARTED);
                return true;
            }
            releaseCreatedResources(
                    createdRecord,
                    createdProjectionCallback,
                    callbackRegistered,
                    createdCallbackThread);
            finishStop(null, false, false, generation);
            return false;
        } catch (Throwable error) {
            releaseCreatedResources(
                    createdRecord,
                    createdProjectionCallback,
                    callbackRegistered,
                    createdCallbackThread);
            finishFailure(error, true, generation);
            return false;
        }
    }

    public void stop() {
        stopRun(activeRunGeneration);
    }

    private void stopRun(long expectedGeneration) {
        Thread threadToJoin = null;
        AudioRecord recordToStop = null;
        boolean shouldNotifyStopping = false;
        long generation;
        synchronized (stateLock) {
            if (expectedGeneration == 0L
                    || activeRunGeneration != expectedGeneration
                    || state == State.IDLE
                    || state == State.STOPPED) {
                return;
            }
            generation = activeRunGeneration;
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
        finishStop(null, false, true, generation);
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
                requestedFormat.sampleRateHz(),
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) {
            throw new IllegalStateException("AudioRecord min buffer query failed: " + minBufferBytes);
        }

        int frameBytes = requestedFormat.bytesPerFrame();
        int internalBufferBytes = CaptureBufferSizer.internalBufferBytes(
                minBufferBytes,
                frameBytes,
                FRAMES_PER_READ);

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(requestedFormat.sampleRateHz())
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        AudioRecord record = new AudioRecord.Builder()
                .setAudioFormat(audioFormat)
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

    private void validateAudioRecordFormat(AudioRecord record) {
        int encoding = record.getAudioFormat();
        int sampleRate = record.getSampleRate();
        int channelCount = record.getChannelCount();
        int frameSize = record.getFormat().getFrameSizeInBytes();
        int expectedFrameSize = requestedFormat.bytesPerFrame();
        if (encoding != AudioFormat.ENCODING_PCM_16BIT
                || sampleRate != requestedFormat.sampleRateHz()
                || channelCount != requestedFormat.channelCount()
                || frameSize != expectedFrameSize) {
            throw new IllegalStateException(
                    "System returned an incompatible playback audio format: encoding=" + encoding
                            + ", sampleRate=" + sampleRate
                            + ", channels=" + channelCount
                            + ", frameBytes=" + frameSize);
        }
    }

    private void finishFailure(
            Throwable error, boolean callbackRegistered, long expectedGeneration) {
        finishStop(error, true, callbackRegistered, expectedGeneration);
    }

    private void finishStop(
            Throwable error,
            boolean notifyError,
            boolean unregisterProjectionCallback,
            long expectedGeneration) {
        AudioRecord recordToRelease;
        MediaProjection.Callback callbackToUnregister;
        HandlerThread callbackThreadToQuit;
        boolean shouldNotifyStopped = false;

        synchronized (stateLock) {
            if (activeRunGeneration != expectedGeneration) {
                return;
            }
            recordToRelease = audioRecord;
            callbackToUnregister = projectionCallback;
            callbackThreadToQuit = projectionCallbackThread;

            audioRecord = null;
            captureThread = null;
            projectionCallback = null;
            projectionCallbackThread = null;
            activeRunGeneration = 0L;

            if (state != State.IDLE && state != State.STOPPED) {
                state = State.STOPPED;
                shouldNotifyStopped = true;
            }
        }

        if (recordToRelease != null) {
            requestAudioRecordStop(recordToRelease);
            recordToRelease.release();
        }
        if (unregisterProjectionCallback) {
            try {
                if (callbackToUnregister != null) {
                    mediaProjection.unregisterCallback(callbackToUnregister);
                }
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

    private void releaseCreatedResources(
            AudioRecord record,
            MediaProjection.Callback callback,
            boolean callbackRegistered,
            HandlerThread callbackThread) {
        if (record != null) {
            requestAudioRecordStop(record);
            record.release();
        }
        if (callbackRegistered && callback != null) {
            try {
                mediaProjection.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
                // A concurrent projection stop can unregister the callback first.
            }
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
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

    private boolean shouldStopRun(long generation) {
        return stopRequested || activeRunGeneration != generation;
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

    private boolean joinCaptureThread(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return true;
        }
        boolean terminated = awaitThreadTermination(thread, CAPTURE_THREAD_JOIN_TIMEOUT_MS);
        if (!terminated) {
            // AudioRecord.release() in finishStop is the final unblock mechanism for a
            // vendor implementation that ignores stop() while READ_BLOCKING is active.
            thread.interrupt();
        }
        return terminated;
    }

    static boolean awaitThreadTermination(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread() || !thread.isAlive()) {
            return true;
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        try {
            thread.join(timeoutMs);
            return !thread.isAlive();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return false;
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
        private final long generation;

        private CaptureLoop(AudioRecord record, long generation) {
            this.record = record;
            this.generation = generation;
        }

        @Override
        public void run() {
            int frameBytes = requestedFormat.bytesPerFrame();
            short[] sampleBuffer = new short[FRAMES_PER_READ * requestedFormat.channelCount()];
            byte[] pcm16LeBuffer = new byte[CaptureBufferSizer.readBufferBytes(frameBytes, FRAMES_PER_READ)];
            long framePosition = 0L;
            try {
                while (!shouldStopRun(generation)) {
                    int samplesRead = record.read(
                            sampleBuffer,
                            0,
                            sampleBuffer.length,
                            AudioRecord.READ_BLOCKING);
                    if (samplesRead > 0) {
                        if (shouldStopRun(generation)) {
                            break;
                        }
                        if (samplesRead % requestedFormat.channelCount() != 0) {
                            throw new IllegalStateException(
                                    "AudioRecord returned a partial PCM frame: " + samplesRead + " samples");
                        }
                        int bytesRead = Pcm16Encoding.writeLittleEndian(
                                sampleBuffer,
                                samplesRead,
                                pcm16LeBuffer);
                        pcmSink.onPcmFrame(pcm16LeBuffer, bytesRead, framePosition);
                        framePosition += samplesRead / requestedFormat.channelCount();
                        continue;
                    }
                    if (samplesRead == 0 && shouldStopRun(generation)) {
                        break;
                    }
                    if (samplesRead == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IllegalStateException("AudioRecord became unavailable");
                    }
                    if (samplesRead < 0 && !shouldStopRun(generation)) {
                        throw new IllegalStateException("AudioRecord.read failed: " + samplesRead);
                    }
                }
                finishStop(null, false, true, generation);
            } catch (Throwable error) {
                if (shouldStopRun(generation)) {
                    finishStop(null, false, true, generation);
                } else {
                    finishFailure(error, true, generation);
                }
            }
        }
    }

    private final class ProjectionStopCallback extends MediaProjection.Callback {
        private final long generation;

        private ProjectionStopCallback(long generation) {
            this.generation = generation;
        }

        @Override
        public void onStop() {
            stopRun(generation);
        }
    }
}
