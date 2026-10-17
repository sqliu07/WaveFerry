package app.shengdu.audio.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Read grants expose exactly one explicitly validated completed APK, never the update directory. */
public final class UpdateApkProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        resolve(uri);
        return ApkUpdateDownload.APK_MIME;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        File file = resolve(uri);
        String[] requested = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String column : requested) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                columns.add(column);
                values.add("WaveFerry-update.apk");
            } else if (OpenableColumns.SIZE.equals(column)) {
                columns.add(column);
                values.add(file.length());
            }
        }
        MatrixCursor result = new MatrixCursor(columns.toArray(new String[0]), 1);
        result.addRow(values.toArray());
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Update APKs are read-only");
        }
        try {
            return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IllegalArgumentException exception) {
            FileNotFoundException failure = new FileNotFoundException("The validated update APK is unavailable");
            failure.initCause(exception);
            throw failure;
        }
    }

    private File resolve(Uri uri) {
        if (getContext() == null || uri == null || !"content".equals(uri.getScheme())
                || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid update APK URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !"apk".equals(segments.get(0)) || !segments.get(1).endsWith(".apk")) {
            throw new IllegalArgumentException("Invalid update APK path");
        }
        String fileName = segments.get(1);
        String jobId = fileName.substring(0, fileName.length() - 4);
        if (!("/apk/" + jobId + ".apk").equals(uri.getEncodedPath())) {
            throw new IllegalArgumentException("Invalid encoded update APK path");
        }
        try {
            return ApkUpdateDownload.validatedFile(getContext(), jobId);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("The validated update APK is unavailable", exception);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Update APKs are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update APKs are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update APKs are read-only");
    }
}
