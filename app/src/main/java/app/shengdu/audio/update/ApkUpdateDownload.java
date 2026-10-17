package app.shengdu.audio.update;

import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

/** Explicitly started APK downloads; all methods must be called on a background executor. */
public final class ApkUpdateDownload {
    private static final Object OPERATIONS_LOCK = new Object();
    private static final String PREFERENCES = "apk_update_download";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private final Context context;

    public ApkUpdateDownload(Context context) {
        this.context = context.getApplicationContext();
        if (this.context == null) {
            throw new IllegalArgumentException("An application context is required");
        }
    }

    public void start(ReleaseUpdateChecker.Result release) throws IOException {
        synchronized (OPERATIONS_LOCK) {
            try {
                if (release == null || !release.isUpdateAvailable()) {
                    throw new IOException("No newer release is available");
                }
                Metadata next = new Metadata(0, release.getVersionName(),
                        release.getApkDownloadUrl(), release.getApkFileName());
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
                    cancelInternal(current);
                }
                File destination = destination(next);
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("Cannot replace the previous update APK");
                }
                DownloadManager manager = manager();
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(next.url))
                        .setTitle(next.fileName)
                        .setMimeType(APK_MIME)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                                next.fileName);
                // Persist the identity before enqueueing, so the small enqueue/ID-write window is recoverable.
                save(next);
                long id = manager.enqueue(request);
                if (id <= 0) {
                    throw new IOException("The system did not start the update download");
                }
                try {
                    save(next.withId(id));
                } catch (IOException exception) {
                    manager.remove(id);
                    throw exception;
                }
            } catch (RuntimeException exception) {
                throw systemFailure(exception);
            }
        }
    }

    public Snapshot query() throws IOException {
        synchronized (OPERATIONS_LOCK) {
            try {
                Metadata metadata = readMetadata();
                return metadata == null ? new Snapshot(State.NONE, "", 0, -1)
                        : queryInternal(metadata);
            } catch (RuntimeException exception) {
                throw systemFailure(exception);
            }
        }
    }

    public void cancel() throws IOException {
        synchronized (OPERATIONS_LOCK) {
            try {
                Metadata metadata = readMetadata();
                if (metadata != null) {
                    cancelInternal(metadata);
                }
            } catch (RuntimeException exception) {
                throw systemFailure(exception);
            }
        }
    }

    /** Validates the completed archive and returns an installer intent; never launches an activity. */
    public Intent prepareInstall() throws IOException {
        synchronized (OPERATIONS_LOCK) {
            try {
                Metadata metadata = readMetadata();
                if (metadata == null || queryInternal(metadata).state != State.READY) {
                    throw new IOException("The update APK has not finished downloading");
                }
                // queryInternal may recover an ID persisted just before the process was interrupted.
                metadata = readMetadata();
                if (metadata == null || metadata.id <= 0) {
                    throw new IOException("The update download is missing");
                }
                File archive = destination(metadata);
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
                Uri uri = manager().getUriForDownloadedFile(metadata.id);
                if (uri == null || !"content".equals(uri.getScheme())
                        || !"downloads".equals(uri.getAuthority())) {
                    throw new IOException("The system cannot open the downloaded APK");
                }
                Intent installer = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                installer.setClipData(ClipData.newRawUri("WaveFerry update", uri));
                return installer;
            } catch (PackageManager.NameNotFoundException exception) {
                throw new IOException("Cannot verify the installed application", exception);
            } catch (RuntimeException exception) {
                throw systemFailure(exception);
            }
        }
    }

    private Snapshot queryInternal(Metadata metadata) throws IOException {
        try {
            String installedVersion = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            if (NumericVersion.parse(metadata.version).compareTo(NumericVersion.parse(installedVersion)) <= 0) {
                cancelInternal(metadata);
                return new Snapshot(State.NONE, "", 0, -1);
            }
        } catch (PackageManager.NameNotFoundException | IllegalArgumentException exception) {
            throw new IOException("Cannot read the installed application version", exception);
        }
        DownloadManager manager = manager();
        metadata = recoverPendingId(manager, metadata);
        if (metadata.id <= 0) {
            return new Snapshot(State.FAILED, metadata.version, 0, -1);
        }
        try (Cursor row = manager.query(new DownloadManager.Query().setFilterById(metadata.id))) {
            if (row == null) {
                throw new IOException("Cannot read the system download status");
            }
            if (!row.moveToFirst()) {
                return new Snapshot(State.FAILED, metadata.version, 0, -1);
            }
            long downloaded = Math.max(0, row.getLong(row.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
            long total = row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int status = row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            State state;
            if (!metadata.url.equals(row.getString(row.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))) {
                state = State.FAILED;
            } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                File file = destination(metadata);
                String local = row.getString(row.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                state = matchesDestination(local, file) && file.isFile() && file.length() > 0
                        ? State.READY : State.FAILED;
            } else if (status == DownloadManager.STATUS_RUNNING) {
                state = State.DOWNLOADING;
            } else if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED) {
                state = State.WAITING;
            } else {
                state = State.FAILED;
            }
            return new Snapshot(state, metadata.version, downloaded, total > 0 ? total : -1);
        }
    }

    private Metadata recoverPendingId(DownloadManager manager, Metadata metadata) throws IOException {
        if (metadata.id != 0) {
            return metadata;
        }
        File expected = destination(metadata);
        try (Cursor rows = manager.query(new DownloadManager.Query())) {
            if (rows == null) {
                throw new IOException("Cannot recover the system download status");
            }
            while (rows.moveToNext()) {
                String url = rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                String local = rows.getString(rows.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                // A queued download may not have a local file URI until DownloadManager creates it.
                if (metadata.url.equals(url) && (local == null || matchesDestination(local, expected))) {
                    Metadata recovered = metadata.withId(rows.getLong(rows.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_ID)));
                    save(recovered);
                    return recovered;
                }
            }
        }
        return metadata;
    }

    private void cancelInternal(Metadata metadata) throws IOException {
        DownloadManager manager = manager();
        metadata = recoverPendingId(manager, metadata);
        if (metadata.id > 0) {
            manager.remove(metadata.id);
        }
        File file = destination(metadata);
        if (file.exists() && !file.delete()) {
            throw new IOException("Cannot delete the previous update APK");
        }
        if (!preferences().edit().clear().commit()) {
            throw new IOException("Cannot clear the saved update download");
        }
    }

    private Metadata readMetadata() throws IOException {
        SharedPreferences preferences = preferences();
        if (!preferences.contains("id")) {
            return null;
        }
        Metadata metadata = new Metadata(preferences.getLong("id", -1),
                preferences.getString("version", ""), preferences.getString("url", ""),
                preferences.getString("file_name", ""));
        ApkUpdatePolicy.validateSource(metadata.version, metadata.url, metadata.fileName);
        if (metadata.id < 0) {
            throw new IOException("Invalid saved update download ID");
        }
        return metadata;
    }

    private void save(Metadata metadata) throws IOException {
        if (!preferences().edit().putLong("id", metadata.id).putString("version", metadata.version)
                .putString("url", metadata.url).putString("file_name", metadata.fileName).commit()) {
            throw new IOException("Cannot save the update download");
        }
    }

    private SharedPreferences preferences() {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private DownloadManager manager() throws IOException {
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        if (manager == null) {
            throw new IOException("The system download manager is unavailable");
        }
        return manager;
    }

    private File destination(Metadata metadata) throws IOException {
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            throw new IOException("The app download directory is unavailable");
        }
        directory = directory.getCanonicalFile();
        File file = new File(directory, metadata.fileName).getCanonicalFile();
        if (!directory.equals(file.getParentFile())) {
            throw new IOException("Unexpected update file destination");
        }
        return file;
    }

    private static boolean matchesDestination(String localUri, File expected) throws IOException {
        if (localUri == null) {
            return false;
        }
        Uri uri = Uri.parse(localUri);
        return "file".equals(uri.getScheme()) && uri.getPath() != null
                && new File(uri.getPath()).getCanonicalFile().equals(expected);
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

    private static IOException systemFailure(RuntimeException exception) {
        return new IOException("The system cannot manage this update download", exception);
    }

    private static final class Metadata {
        final long id;
        final String version;
        final String url;
        final String fileName;

        Metadata(long id, String version, String url, String fileName) {
            this.id = id;
            this.version = version;
            this.url = url;
            this.fileName = fileName;
        }

        Metadata withId(long nextId) {
            return new Metadata(nextId, version, url, fileName);
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
