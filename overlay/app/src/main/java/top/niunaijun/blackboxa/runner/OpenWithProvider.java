package top.niunaijun.blackboxa.runner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;

/**
 * Serves the copies OpenWithActivity makes of files, so an app in the container can open them.
 * Container apps run with this app's identity, so they can read it; other apps cannot.
 * Addresses look like content://&lt;package&gt;.openwith/&lt;id&gt;/&lt;file name&gt;.
 */
public class OpenWithProvider extends ContentProvider {
    private static final String TAG = "OpenWith";

    static File root(android.content.Context context) {
        return new File(context.getCacheDir(), "open_with");
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            List<String> segments = uri.getPathSegments();
            if (segments.size() != 2) {
                throw new FileNotFoundException(uri.toString());
            }
            File root = root(getContext()).getCanonicalFile();
            File file = new File(new File(root, segments.get(0)), segments.get(1)).getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()) {
                throw new FileNotFoundException(uri.toString());
            }
            return file;
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        try {
            File file = resolve(uri);
            Log.d(TAG, "provider: query for " + file.getName());
            String[] columns = projection != null ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                    row[i] = file.getName();
                } else if (OpenableColumns.SIZE.equals(columns[i])) {
                    row[i] = file.length();
                }
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            Log.w(TAG, "provider: query for a file that is not there: " + uri.getLastPathSegment());
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0) {
            String type = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int bits;
        try {
            bits = ParcelFileDescriptor.parseMode(mode == null ? "r" : mode);
        } catch (IllegalArgumentException e) {
            // An app asked for a mode Android does not know: it only wants to look at the file.
            Log.w(TAG, "provider: unknown mode \"" + mode + "\", opening read-only");
            bits = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        File file = resolve(uri);
        Log.d(TAG, "provider: opening " + file.getName() + " mode=" + mode);
        return ParcelFileDescriptor.open(file, bits);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
