package com.cloudy.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import com.cloudy.app.core.Account;
import com.cloudy.app.core.Paths;
import com.cloudy.app.store.CloudStore;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.TreeMap;

/**
 * Shows every connected drive as a storage location in the system Files app and in other apps' file pickers.
 *
 * Files are not copied to the phone. The system gets a normal random-access file descriptor from
 * StorageManager.openProxyFileDescriptor(), and every read or write on it lands in {@link Callback}, which answers
 * from the RAM + disk cache or fetches just the needed blocks from the cloud.
 *
 * Document ids: "&lt;accountId&gt;|d|&lt;path&gt;" for a folder (empty path = the drive's root) and
 * "&lt;accountId&gt;|f|&lt;path&gt;" for a file.
 */
public final class CloudyProvider extends DocumentsProvider {
    private static final String[] ROOT_COLS = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_SUMMARY};
    private static final String[] DOC_COLS = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED};

    private HandlerThread ioThread;
    private Handler ioHandler;

    @Override
    public boolean onCreate() {
        ioThread = new HandlerThread("cloudy-io");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        return true;
    }

    // ------------------------------------------------------------------ roots and listings

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : ROOT_COLS);
        for (Account a : AccountStore.load(getContext())) {
            c.newRow().add(Root.COLUMN_ROOT_ID, a.id)
                    .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
                    .add(Root.COLUMN_TITLE, a.name)
                    .add(Root.COLUMN_DOCUMENT_ID, a.id + "|d|")
                    .add(Root.COLUMN_SUMMARY, "Streamed from the cloud");
        }
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_COLS);
        addRow(c, documentId);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DOC_COLS);
        String[] id = split(parentId);
        String prefix = id[2].isEmpty() ? "" : id[2] + "/";
        TreeMap<String, String> children = new TreeMap<>();            // display name -> document id
        for (CloudStore.FileInfo f : store(id[0]).list()) {
            if (!f.path.startsWith(prefix)) continue;
            String rest = f.path.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) children.put(rest, id[0] + "|f|" + f.path);
            else children.put(rest.substring(0, slash), id[0] + "|d|" + prefix + rest.substring(0, slash));
        }
        for (String child : children.values()) addRow(c, child);
        return c;
    }

    private void addRow(MatrixCursor c, String documentId) throws FileNotFoundException {
        String[] id = split(documentId);
        boolean dir = id[1].equals("d");
        MatrixCursor.RowBuilder r = c.newRow();
        r.add(Document.COLUMN_DOCUMENT_ID, documentId);
        if (dir) {
            r.add(Document.COLUMN_DISPLAY_NAME, id[2].isEmpty() ? driveName(id[0]) : Paths.name("/" + id[2]));
            r.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            r.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
        } else {
            CloudStore.CloudFile f = openFile(id[0], id[2]);
            r.add(Document.COLUMN_DISPLAY_NAME, Paths.name("/" + id[2]));
            r.add(Document.COLUMN_MIME_TYPE, Paths.mimeFor(Paths.name("/" + id[2])));
            r.add(Document.COLUMN_SIZE, f.size());
            r.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE
                    | Document.FLAG_SUPPORTS_RENAME);
        }
    }

    // ------------------------------------------------------------------ changes

    @Override
    public String createDocument(String parentId, String mimeType, String displayName) throws FileNotFoundException {
        String[] id = split(parentId);
        String p = id[2].isEmpty() ? displayName : id[2] + "/" + displayName;
        if (Document.MIME_TYPE_DIR.equals(mimeType)) return id[0] + "|d|" + p;   // folders exist through their files
        try {
            store(id[0]).create(p);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return id[0] + "|f|" + p;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        String[] id = split(documentId);
        try {
            store(id[0]).delete(id[2]);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        String[] id = split(documentId);
        int slash = id[2].lastIndexOf('/');
        String np = slash < 0 ? displayName : id[2].substring(0, slash + 1) + displayName;
        try {
            store(id[0]).rename(id[2], np);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return id[0] + "|f|" + np;
    }

    @Override
    public boolean isChildDocument(String parentId, String documentId) {
        String[] p = split0(parentId), d = split0(documentId);
        if (p == null || d == null || !p[0].equals(d[0])) return false;
        return p[2].isEmpty() || d[2].startsWith(p[2] + "/");
    }

    // ------------------------------------------------------------------ the part that serves bytes

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        String[] id = split(documentId);
        CloudStore.CloudFile f = openFile(id[0], id[2]);
        boolean writable = mode.contains("w");
        try {
            if (writable && mode.contains("t")) f.truncate(0);
            StorageManager sm = getContext().getSystemService(StorageManager.class);
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.parseMode(mode),
                    new Callback(id[0], f, writable), ioHandler);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private final class Callback extends ProxyFileDescriptorCallback {
        private final String accountId;
        private final CloudStore.CloudFile file;
        private final boolean writable;

        Callback(String accountId, CloudStore.CloudFile file, boolean writable) {
            this.accountId = accountId;
            this.file = file;
            this.writable = writable;
        }

        @Override
        public long onGetSize() {
            return file.size();
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            try {
                return Math.max(file.read(offset, data, 0, size), 0);
            } catch (IOException e) {
                throw new ErrnoException("read", OsConstants.EIO);
            }
        }

        @Override
        public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
            if (!writable) throw new ErrnoException("write", OsConstants.EBADF);
            try {
                file.write(offset, data, 0, size);
                return size;
            } catch (IOException e) {
                throw new ErrnoException("write", OsConstants.EIO);   // e.g. cache full while the cloud is unreachable
            }
        }

        @Override
        public void onFsync() throws ErrnoException {
            try {
                store(accountId).flush();
            } catch (IOException e) {
                throw new ErrnoException("fsync", OsConstants.EIO);
            }
        }

        @Override
        public void onRelease() {
        }
    }

    // ------------------------------------------------------------------ helpers

    private CloudStore store(String accountId) throws FileNotFoundException {
        try {
            return Drives.store(getContext(), accountId);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage() == null ? "Drive not available" : e.getMessage());
        }
    }

    private CloudStore.CloudFile openFile(String accountId, String path) throws FileNotFoundException {
        try {
            CloudStore.CloudFile f = store(accountId).open(path);
            if (f == null) throw new FileNotFoundException("No such file: " + path);
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private String driveName(String accountId) {
        Account a = AccountStore.find(getContext(), accountId);
        return a == null ? "Drive" : a.name;
    }

    private static String[] split(String documentId) throws FileNotFoundException {
        String[] p = split0(documentId);
        if (p == null) throw new FileNotFoundException("Bad document id: " + documentId);
        return p;
    }

    private static String[] split0(String documentId) {
        if (documentId == null) return null;
        String[] p = documentId.split("\\|", 3);
        if (p.length != 3 || !(p[1].equals("d") || p[1].equals("f"))) return null;
        return p;
    }

}
