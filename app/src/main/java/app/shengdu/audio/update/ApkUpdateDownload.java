package app.shengdu.audio.update;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** App-managed transfers. The process-wide worker retains only an application context. */
public final class ApkUpdateDownload {
    private static final Object LOCK = new Object();
    private static final String PREFERENCES = "apk_update_download";
    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "apk-update-download"));
    private static Job active;
    private final Context context;

    public ApkUpdateDownload(Context context) {
        this.context = context.getApplicationContext();
        if (this.context == null) {
            throw new IllegalArgumentException("An application context is required");
        }
    }

    public void start(ReleaseUpdateChecker.Result release) throws IOException {
        try {
            synchronized (LOCK) {
                if (release == null || !release.isUpdateAvailable()) {
                    throw new IOException("No newer release is available");
                }
                Metadata next = new Metadata(UUID.randomUUID().toString(), release.getVersionName(),
                        release.getApkDownloadUrl(), release.getApkFileName(), State.WAITING);
                ApkUpdatePolicy.validateSource(next.version, next.url, next.fileName);
                Metadata current = readMetadata();
                if (current != null) {
                    Snapshot snapshot = queryInternal(current);
                    if (snapshot.state != State.FAILED && snapshot.state != State.NONE) {
                        if (current.version.equals(next.version) && current.url.equals(next.url)) {
                            return;
                        }
                        throw new IOException("Another update download is already available or running");
                    }
                    deleteFiles(current.id);
                }
                updateFile(context, next.id, true);
                save(next);
                Job job = new Job(next);
                active = job;
                try {
                    job.future = WORKER.submit(() -> transfer(job));
                } catch (RuntimeException exception) {
                    active = null;
                    save(next.withState(State.FAILED));
                    throw exception;
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("Cannot start the update download", exception);
        }
    }

    public Snapshot query() throws IOException {
        try {
            synchronized (LOCK) {
                Metadata metadata = readMetadata();
                return metadata == null ? new Snapshot(State.NONE, "", 0, -1) : queryInternal(metadata);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Cannot read the update download", exception);
        }
    }

    public void cancel() throws IOException {
        Job job = null;
        IOException failure = null;
        try {
            synchronized (LOCK) {
                Metadata metadata = readMetadata();
                if (metadata != null) {
                    if (active != null && active.metadata.id.equals(metadata.id)) {
                        job = active;
                        job.cancelled = true;
                        active = null;
                    }
                    if (!preferences().edit().clear().commit()) {
                        failure = new IOException("Cannot clear the saved update download");
                    }
                    deleteFiles(metadata.id);
                }
            }
        } catch (IOException exception) {
            failure = exception;
        } catch (RuntimeException exception) {
            failure = new IOException("Cannot cancel the update download", exception);
        } finally {
            if (job != null) {
                if (job.future != null) {
                    job.future.cancel(true);
                }
                job.transfer.close();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** Validates a complete internal APK off the UI thread and never launches an activity. */
    public Intent prepareInstall() throws IOException {
        try {
            Metadata metadata;
            synchronized (LOCK) {
                metadata = readMetadata();
                if (metadata == null || queryInternal(metadata).state != State.READY) {
                    throw new IOException("The update APK has not finished downloading");
                }
            }
            validateArchive(metadata);
            synchronized (LOCK) {
                Metadata current = readMetadata();
                if (current == null || !current.id.equals(metadata.id) || current.state != State.READY) {
                    throw new IOException("The update download changed during validation");
                }
                if (!preferences().edit().putString("validated_job", metadata.id).commit()) {
                    throw new IOException("Cannot save the validated update identity");
                }
            }
            Uri uri = new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                    .appendPath("apk").appendPath(metadata.id + ".apk").build();
            Intent installer = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installer.setClipData(ClipData.newRawUri("WaveFerry update", uri));
            return installer;
        } catch (RuntimeException exception) {
            throw new IOException("Cannot prepare the downloaded update", exception);
        }
    }

    private void transfer(Job job) {
        boolean completed = false;
        try {
            synchronized (LOCK) {
                ensureCurrent(job);
                save(job.metadata.withState(State.DOWNLOADING));
                job.state = State.DOWNLOADING;
            }
            File partial = updateFile(context, job.metadata.id, true);
            job.transfer.download(URI.create(job.metadata.url), partial, (bytes, total) -> {
                if (job.cancelled) {
                    throw new InterruptedIOException("Update download cancelled");
                }
                job.bytes = bytes;
                job.total = total;
            });
            synchronized (LOCK) {
                ensureCurrent(job);
                Files.move(partial.toPath(), updateFile(context, job.metadata.id, false).toPath(),
                        StandardCopyOption.ATOMIC_MOVE);
            }
            validateArchive(job.metadata);
            synchronized (LOCK) {
                ensureCurrent(job);
                save(job.metadata.withState(State.READY));
                job.state = State.READY;
                completed = true;
            }
        } catch (IOException | RuntimeException exception) {
            synchronized (LOCK) {
                if (active == job && !job.cancelled) {
                    job.state = State.FAILED;
                    try {
                        save(job.metadata.withState(State.FAILED));
                    } catch (IOException | RuntimeException ignored) {
                        // A surviving worker exposes FAILED; a new process detects interruption.
                    }
                }
            }
        } finally {
            job.transfer.close();
            synchronized (LOCK) {
                if (!completed) {
                    try {
                        deleteFiles(job.metadata.id);
                    } catch (IOException ignored) {
                        // Only this unique job's files are touched; retry/cancel can remove leftovers.
                    }
                }
                if (active == job) {
                    active = null;
                }
            }
        }
    }

    private void ensureCurrent(Job job) throws IOException {
        Metadata current = readMetadata();
        if (job.cancelled || Thread.currentThread().isInterrupted() || active != job
                || current == null || !current.id.equals(job.metadata.id)) {
            throw new InterruptedIOException("Update download cancelled");
        }
    }

    private Snapshot queryInternal(Metadata metadata) throws IOException {
        try {
            String installedVersion = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            if (NumericVersion.parse(metadata.version).compareTo(NumericVersion.parse(installedVersion)) <= 0) {
                if (active != null && active.metadata.id.equals(metadata.id)) {
                    throw new IOException("Installed application changed during the update download");
                }
                deleteFiles(metadata.id);
                if (!preferences().edit().clear().commit()) {
                    throw new IOException("Cannot clear the installed update download");
                }
                return new Snapshot(State.NONE, "", 0, -1);
            }
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException exception) {
            throw new IOException("Cannot read the installed application version", exception);
        }
        if (active != null && active.metadata.id.equals(metadata.id)) {
            return new Snapshot(active.state, metadata.version, active.bytes, active.total);
        }
        File complete = updateFile(context, metadata.id, false);
        if (metadata.state == State.READY && complete.isFile() && complete.length() > 0) {
            return new Snapshot(State.READY, metadata.version, complete.length(), complete.length());
        }
        if (metadata.state != State.FAILED) {
            save(metadata.withState(State.FAILED));
        }
        return new Snapshot(State.FAILED, metadata.version,
                updateFile(context, metadata.id, true).length(), -1);
    }

    private void validateArchive(Metadata metadata) throws IOException {
        File archive = updateFile(context, metadata.id, false);
        if (!archive.isFile() || archive.length() <= 0 || archive.length() > ApkHttpsDownload.MAX_BYTES) {
            throw new IOException("The downloaded APK is missing or invalid");
        }
        try {
            PackageManager packages = context.getPackageManager();
            PackageInfo installed = packages.getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo downloaded = packages.getPackageArchiveInfo(archive.getPath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (downloaded == null) {
                throw new IOException("The downloaded file is not a valid APK");
            }
            ApkUpdatePolicy.validateArchive(context.getPackageName(), metadata.version,
                    installed.getLongVersionCode(), certificates(installed), downloaded.packageName,
                    downloaded.versionName, downloaded.getLongVersionCode(), certificates(downloaded));
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IOException("Cannot verify the installed application", exception);
        }
    }

    private Metadata readMetadata() throws IOException {
        SharedPreferences saved = preferences();
        String id = saved.getString("job", null);
        if (id == null) {
            return null;
        }
        try {
            Metadata metadata = new Metadata(id, saved.getString("version", ""),
                    saved.getString("url", ""), saved.getString("file_name", ""),
                    State.valueOf(saved.getString("state", "FAILED")));
            validateJobId(id);
            ApkUpdatePolicy.validateSource(metadata.version, metadata.url, metadata.fileName);
            return metadata;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid saved update download", exception);
        }
    }

    private void save(Metadata metadata) throws IOException {
        if (!preferences().edit().putString("job", metadata.id).putString("version", metadata.version)
                .putString("url", metadata.url).putString("file_name", metadata.fileName)
                .putString("state", metadata.state.name()).remove("validated_job").commit()) {
            throw new IOException("Cannot save the update download");
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private void deleteFiles(String jobId) throws IOException {
        for (boolean partial : new boolean[]{true, false}) {
            File file = updateFile(context, jobId, partial);
            if (file.exists() && !file.delete()) {
                throw new IOException("Cannot remove the previous update file");
            }
        }
    }

    static File validatedFile(Context context, String jobId) throws IOException {
        synchronized (LOCK) {
            ApkUpdateDownload updates = new ApkUpdateDownload(context);
            Metadata metadata = updates.readMetadata();
            if (metadata == null || !metadata.id.equals(jobId) || metadata.state != State.READY
                    || !jobId.equals(updates.preferences().getString("validated_job", null))) {
                throw new IOException("The requested update APK has not been validated");
            }
            File file = updateFile(context, jobId, false);
            if (!file.isFile() || file.length() <= 0) {
                throw new IOException("The requested update APK is missing");
            }
            return file;
        }
    }

    private static File updateFile(Context context, String jobId, boolean partial) throws IOException {
        validateJobId(jobId);
        File files = context.getFilesDir().getCanonicalFile();
        File directory = new File(files, "updates").getCanonicalFile();
        if (!files.equals(directory.getParentFile())) {
            throw new IOException("Invalid app update directory");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create the app update directory");
        }
        File requested = new File(directory, jobId + (partial ? ".part" : ".apk"));
        File file = requested.getCanonicalFile();
        if (!requested.equals(file) || !directory.equals(file.getParentFile())) {
            throw new IOException("Invalid update file path");
        }
        return file;
    }

    static void validateJobId(String jobId) throws IOException {
        try {
            if (!UUID.fromString(jobId).toString().equals(jobId)) {
                throw new IOException("Invalid update job identity");
            }
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException("Invalid update job identity", exception);
        }
    }

    private static byte[][] certificates(PackageInfo info) throws IOException {
        if (info.signingInfo == null) {
            throw new IOException("APK signing information is missing");
        }
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) {
            throw new IOException("APK signing certificates are missing");
        }
        byte[][] result = new byte[signatures.length][];
        for (int index = 0; index < signatures.length; index++) {
            result[index] = signatures[index] == null ? null : signatures[index].toByteArray();
        }
        return result;
    }

    private static final class Metadata {
        final String id;
        final String version;
        final String url;
        final String fileName;
        final State state;

        Metadata(String id, String version, String url, String fileName, State state) {
            this.id = id;
            this.version = version;
            this.url = url;
            this.fileName = fileName;
            this.state = state;
        }

        Metadata withState(State next) {
            return new Metadata(id, version, url, fileName, next);
        }
    }

    private static final class Job {
        final Metadata metadata;
        final ApkHttpsDownload transfer = new ApkHttpsDownload();
        volatile boolean cancelled;
        volatile State state = State.WAITING;
        volatile long bytes;
        volatile long total = -1;
        Future<?> future;

        Job(Metadata metadata) {
            this.metadata = metadata;
        }
    }

    public enum State {
        NONE, DOWNLOADING, WAITING, READY, FAILED
    }

    public static final class Snapshot {
        private final State state;
        private final String versionName;
        private final long downloadedBytes;
        private final long totalBytes;

        private Snapshot(State state, String versionName, long downloadedBytes, long totalBytes) {
            this.state = state;
            this.versionName = versionName;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
        }

        public State getState() {
            return state;
        }

        public String getVersionName() {
            return versionName;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }
    }
}
