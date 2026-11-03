package com.cloudy.app.store;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A virtual drive whose files live in the cloud and are only ever held locally in a bounded
 * RAM + disk cache.
 *
 * Files are split into fixed-size blocks stored as separate cloud objects ("b-&lt;fileId&gt;-&lt;n&gt;"). A
 * small "catalog" object maps paths to file ids and sizes. Reads fetch only the blocks they touch
 * (with sequential read-ahead); writes go to the cache as dirty blocks and are uploaded on
 * {@link #flush()}, by the auto-flusher, or when the cache needs room.
 *
 * Single-writer model: one device owns a drive at a time. Two devices writing the same drive
 * concurrently would overwrite each other's catalog.
 */
public final class CloudStore implements Closeable {
    public static final int DEFAULT_BLOCK_SIZE = 1 << 20;
    private static final String CATALOG_KEY = "catalog";
    private static final String CATALOG_MAGIC = "CLOUDRUN1";

    public static final class FileInfo {
        public final String path;
        public final long size;
        FileInfo(String path, long size) { this.path = path; this.size = size; }
    }

    /** Handle to one file. Safe to share between threads. */
    public final class CloudFile {
        final String id;
        final int blockSize;
        volatile String path;
        volatile long size;
        volatile boolean deleted;
        volatile int lastBlockRead = -2;

        CloudFile(String id, String path, long size, int blockSize) {
            this.id = id; this.path = path; this.size = size; this.blockSize = blockSize;
        }

        public String path() { return path; }
        public long size() { return size; }

        /** @return bytes read, or -1 at/after end of file. */
        public int read(long pos, byte[] buf, int off, int len) throws IOException {
            return CloudStore.this.read(this, pos, buf, off, len);
        }

        public void write(long pos, byte[] buf, int off, int len) throws IOException {
            CloudStore.this.write(this, pos, buf, off, len);
        }

        public void truncate(long newSize) throws IOException {
            CloudStore.this.truncate(this, newSize);
        }
    }

    private final CloudBackend backend;
    private final File metaDir;
    private final BlockCache cache;
    private final int blockSize;
    private final int readAhead;

    private final Object catLock = new Object();
    private final Map<String, CloudFile> files = new TreeMap<>();
    private long catVersion;            // bumped on every catalog change (guarded by catLock)
    private long uploadedVersion;       // last version known to be in the cloud (guarded by catLock)
    private boolean pendingMarked;     // catalog.pending marker exists (guarded by catLock)
    private java.io.FileOutputStream journal;   // append-only "id<TAB>size" log of file growth (guarded by catLock)

    private final Set<String> pendingDeletes = ConcurrentHashMap.newKeySet();
    private final Object delLock = new Object();
    private final Object flushLock = new Object();
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor prefetcher;
    private ScheduledExecutorService autoFlusher;

    public CloudStore(CloudBackend backend, File cacheDir, long ramBytes, long diskBytes,
                      int blockSize, int readAheadBlocks) throws IOException {
        if (blockSize < 4096) throw new IllegalArgumentException("blockSize too small");
        this.backend = backend;
        this.metaDir = cacheDir;
        this.blockSize = blockSize;
        this.readAhead = Math.max(0, readAheadBlocks);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) throw new IOException("cannot create " + cacheDir);
        this.cache = new BlockCache(new File(cacheDir, "blocks"), ramBytes, diskBytes, this::putWithRetry);
        this.prefetcher = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
                r -> { Thread t = new Thread(r, "cloudrun-prefetch"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.DiscardPolicy());
        loadCatalog();
    }

    public BlockCache cache() { return cache; }

    // ------------------------------------------------------------------ namespace

    public List<FileInfo> list() {
        synchronized (catLock) {
            List<FileInfo> out = new ArrayList<>();
            for (CloudFile f : files.values()) out.add(new FileInfo(f.path, f.size));
            return out;
        }
    }

    /** @return the file, or null if it does not exist. */
    public CloudFile open(String path) throws IOException {
        String p = normalize(path);
        synchronized (catLock) { return files.get(p); }
    }

    /** Create an empty file, replacing any existing file at that path. */
    public CloudFile create(String path) throws IOException {
        String p = normalize(path);
        CloudFile old;
        CloudFile f = new CloudFile(UUID.randomUUID().toString().replace("-", ""), p, 0, blockSize);
        synchronized (catLock) {
            old = files.put(p, f);
            catChanged();
        }
        if (old != null) discard(old);
        return f;
    }

    public void delete(String path) throws IOException {
        String p = normalize(path);
        CloudFile old;
        synchronized (catLock) {
            old = files.remove(p);
            if (old != null) catChanged();
        }
        if (old != null) discard(old);
    }

    public void rename(String from, String to) throws IOException {
        String a = normalize(from), b = normalize(to);
        CloudFile replaced = null;
        synchronized (catLock) {
            CloudFile f = files.remove(a);
            if (f == null) throw new IOException("no such file: " + a);
            replaced = files.put(b, f);
            f.path = b;
            catChanged();
        }
        if (replaced != null && replaced != files.get(b)) discard(replaced);
    }

    /**
     * Upload a stream as a new file, block by block, straight to the cloud (write-through).
     * Nothing is kept in the cache, so importing a huge file does not churn the local disk.
     */
    public CloudFile importStream(String path, InputStream in) throws IOException {
        CloudFile f = create(path);
        try {
            byte[] buf = new byte[f.blockSize];
            long pos = 0;
            int idx = 0;
            while (true) {
                int n = readFully(in, buf);
                if (n <= 0) break;
                byte[] blk = n == buf.length ? buf.clone() : Arrays.copyOf(buf, n);
                putWithRetry(blockKey(f, idx++), blk);
                pos += n;
                f.size = pos;
                if (n < buf.length) break;
            }
            synchronized (catLock) { catChanged(); }
            return f;
        } catch (IOException | RuntimeException e) {
            try { delete(f.path); } catch (IOException ignored) { }
            throw e;
        }
    }

    // ------------------------------------------------------------------ data path

    private int read(CloudFile f, long pos, byte[] buf, int off, int len) throws IOException {
        if (f.deleted) throw new IOException("file deleted: " + f.path);
        long size = f.size;
        if (len == 0) return 0;
        if (pos < 0) throw new IOException("negative offset");
        if (pos >= size) return -1;
        int want = (int) Math.min(len, size - pos);
        int done = 0;
        int firstIdx = (int) (pos / f.blockSize);
        int idx = firstIdx;
        while (done < want) {
            long p = pos + done;
            idx = (int) (p / f.blockSize);
            int boff = (int) (p % f.blockSize);
            int n = Math.min(want - done, f.blockSize - boff);
            byte[] b = loadBlock(f, idx);
            int avail = Math.max(0, Math.min(n, b.length - boff));
            if (avail > 0) System.arraycopy(b, boff, buf, off + done, avail);
            if (avail < n) Arrays.fill(buf, off + done + avail, off + done + n, (byte) 0); // sparse / zero tail
            done += n;
        }
        // Sequential access? Warm the next few blocks in the background.
        int prev = f.lastBlockRead;
        f.lastBlockRead = idx;
        if (readAhead > 0 && (prev == firstIdx - 1 || prev == firstIdx)) {
            int lastNeeded = (int) ((size - 1) / f.blockSize);
            for (int i = idx + 1; i <= Math.min(idx + readAhead, lastNeeded); i++) {
                final int bi = i;
                if (!cache.contains(blockKey(f, bi))) {
                    prefetcher.execute(() -> { try { loadBlock(f, bi); } catch (IOException ignored) { } });
                }
            }
        }
        return done;
    }

    private void write(CloudFile f, long pos, byte[] buf, int off, int len) throws IOException {
        if (f.deleted) throw new IOException("file deleted: " + f.path);
        if (pos < 0) throw new IOException("negative offset");
        if (len == 0) return;
        synchronized (f) {
            long oldSize = f.size;
            int done = 0;
            while (done < len) {
                long p = pos + done;
                int idx = (int) (p / f.blockSize);
                int boff = (int) (p % f.blockSize);
                int n = Math.min(len - done, f.blockSize - boff);
                byte[] old;
                if (boff == 0 && n == f.blockSize) {
                    old = new byte[0];                                  // full overwrite, no need to fetch
                } else if ((long) idx * f.blockSize >= oldSize) {
                    old = new byte[0];                                  // brand new region
                } else {
                    old = loadBlock(f, idx);
                }
                byte[] nb = new byte[Math.max(old.length, boff + n)];
                System.arraycopy(old, 0, nb, 0, old.length);
                System.arraycopy(buf, off + done, nb, boff, n);
                String key = blockKey(f, idx);
                synchronized (delLock) {
                    pendingDeletes.remove(key);
                    cache.put(key, nb, true);
                }
                done += n;
                long end = p + n;
                if (end > f.size) {                                      // grow as we go so a failure keeps a consistent size
                    f.size = end;
                    synchronized (catLock) { catGrew(f); }
                }
            }
        }
    }

    private void truncate(CloudFile f, long newSize) throws IOException {
        if (f.deleted) throw new IOException("file deleted: " + f.path);
        if (newSize < 0) throw new IOException("negative size");
        synchronized (f) {
            long oldSize = f.size;
            if (newSize < oldSize) {
                int firstGone = (int) ((newSize + f.blockSize - 1) / f.blockSize);
                int lastOld = (int) ((oldSize - 1) / f.blockSize);
                for (int i = firstGone; i <= lastOld; i++) {
                    String key = blockKey(f, i);
                    cache.invalidate(key);
                    pendingDeletes.add(key);
                }
                int tail = (int) (newSize % f.blockSize);
                if (tail != 0) {                                         // cut the partial last block
                    int idx = (int) (newSize / f.blockSize);
                    byte[] b = loadBlock(f, idx);
                    if (b.length > tail) {
                        String key = blockKey(f, idx);
                        synchronized (delLock) {
                            pendingDeletes.remove(key);
                            cache.put(key, Arrays.copyOf(b, tail), true);
                        }
                    }
                }
            }
            f.size = newSize;
            synchronized (catLock) { catChanged(); }
        }
    }

    private byte[] loadBlock(CloudFile f, int idx) throws IOException {
        String key = blockKey(f, idx);
        byte[] b = cache.get(key);
        if (b != null) return b;

        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> other = inflight.putIfAbsent(key, mine);
        if (other != null) {                                             // someone else is already fetching it
            try {
                return other.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
            }
        }
        try {
            b = cache.get(key);
            if (b == null) {
                cache.recordMiss();
                b = pendingDeletes.contains(key) ? null : backend.get(key);
                if (b == null) b = new byte[0];                          // sparse block reads as zeros
                cache.putClean(key, b);
            }
            mine.complete(b);
            return b;
        } catch (IOException | RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inflight.remove(key);
        }
    }

    // ------------------------------------------------------------------ durability

    /** Upload dirty blocks, then deletions, then the catalog (in that order, so the cloud never points at missing data). */
    public void flush() throws IOException {
        synchronized (flushLock) {
            cache.flushDirty();

            IOException first = null;
            for (String key : new ArrayList<>(pendingDeletes)) {
                synchronized (delLock) {
                    if (!pendingDeletes.contains(key)) continue;
                    try {
                        backend.delete(key);
                        pendingDeletes.remove(key);
                    } catch (IOException e) {
                        if (first == null) first = e;
                    }
                }
            }

            byte[] cat;
            long ver;
            synchronized (catLock) {
                if (catVersion == uploadedVersion) { if (first != null) throw first; return; }
                cat = serializeCatalog();
                ver = catVersion;
            }
            backend.put(CATALOG_KEY, cat);
            synchronized (catLock) {
                uploadedVersion = Math.max(uploadedVersion, ver);
                if (catVersion == uploadedVersion) {
                    new File(metaDir, "catalog.pending").delete();
                    pendingMarked = false;
                    resetJournal();
                }
            }
            if (first != null) throw first;
        }
    }

    /** Flush every {@code periodMs} in the background (failures are retried on the next tick). */
    public synchronized void startAutoFlush(long periodMs) {
        if (autoFlusher != null) return;
        autoFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloudrun-autoflush");
            t.setDaemon(true);
            return t;
        });
        autoFlusher.scheduleWithFixedDelay(() -> {
            try { flush(); } catch (IOException | RuntimeException ignored) { }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    @Override public void close() throws IOException {
        synchronized (this) {
            if (autoFlusher != null) autoFlusher.shutdownNow();
        }
        try {
            flush();
        } finally {
            prefetcher.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ catalog

    private void discard(CloudFile f) {
        f.deleted = true;
        synchronized (f) {
            long n = f.size;
            int blocks = (int) ((n + f.blockSize - 1) / f.blockSize);
            for (int i = 0; i < blocks; i++) {
                String key = blockKey(f, i);
                cache.invalidate(key);
                pendingDeletes.add(key);
            }
        }
    }

    /** Structural change (create/delete/rename/truncate). Must be called with catLock held. */
    private void catChanged() {
        catVersion++;
        try {
            saveLocalCatalog(serializeCatalog());
            resetJournal();                                      // the full catalog now carries every size
            markPending();
        } catch (IOException ignored) { }
    }

    /** A file grew. One small append instead of rewriting the catalog. Must be called with catLock held. */
    private void catGrew(CloudFile f) {
        catVersion++;
        try {
            markPending();
            if (journal == null) journal = new java.io.FileOutputStream(new File(metaDir, "sizes.log"), true);
            journal.write((f.id + "\t" + f.size + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
    }

    private void markPending() throws IOException {
        if (!pendingMarked) {
            new File(metaDir, "catalog.pending").createNewFile();
            pendingMarked = true;
        }
    }

    private void resetJournal() {
        if (journal != null) {
            try { journal.close(); } catch (IOException ignored) { }
            journal = null;
        }
        new File(metaDir, "sizes.log").delete();
    }

    private byte[] serializeCatalog() {
        StringBuilder sb = new StringBuilder(CATALOG_MAGIC).append('\n');
        for (CloudFile f : files.values()) {
            sb.append("F\t").append(f.id).append('\t').append(f.size).append('\t')
              .append(f.blockSize).append('\t').append(f.path).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void saveLocalCatalog(byte[] data) throws IOException {
        File tmp = new File(metaDir, "catalog.txt.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        File dst = new File(metaDir, "catalog.txt");
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("cannot save catalog"); }
    }

    private void loadCatalog() throws IOException {
        File local = new File(metaDir, "catalog.txt");
        File pending = new File(metaDir, "catalog.pending");
        byte[] data = null;
        boolean fromLocal = false;
        if (pending.exists() && local.isFile()) {                // unsent local changes win over the cloud copy
            data = java.nio.file.Files.readAllBytes(local.toPath());
            fromLocal = true;
        } else {
            try {
                data = backend.get(CATALOG_KEY);
            } catch (IOException e) {
                if (local.isFile()) {                            // offline start: use the last synced copy
                    data = java.nio.file.Files.readAllBytes(local.toPath());
                } else {
                    throw e;
                }
            }
        }
        if (data != null) parseCatalog(data);
        if (fromLocal) replayJournal();
        synchronized (catLock) {
            catVersion = 1;
            uploadedVersion = fromLocal ? 0 : 1;
            pendingMarked = fromLocal;
        }
    }

    /** Apply file growth that happened after the last full local catalog save (sizes only ever grow here). */
    private void replayJournal() throws IOException {
        File j = new File(metaDir, "sizes.log");
        if (!j.isFile()) return;
        Map<String, CloudFile> byId = new java.util.HashMap<>();
        for (CloudFile f : files.values()) byId.put(f.id, f);
        for (String line : new String(java.nio.file.Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8).split("\n")) {
            int t = line.indexOf('\t');
            if (t < 0) continue;                                 // torn last line after a crash
            CloudFile f = byId.get(line.substring(0, t));
            if (f == null) continue;
            try {
                long sz = Long.parseLong(line.substring(t + 1));
                if (sz > f.size) f.size = sz;
            } catch (NumberFormatException ignored) { }
        }
    }

    private void parseCatalog(byte[] data) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            if (!CATALOG_MAGIC.equals(line)) throw new IOException("unrecognised catalog");
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\t", 5);
                if (p.length != 5 || !p[0].equals("F")) throw new IOException("corrupt catalog line");
                files.put(p[4], new CloudFile(p[1], p[4], Long.parseLong(p[2]), Integer.parseInt(p[3])));
            }
        } catch (NumberFormatException e) {
            throw new IOException("corrupt catalog", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void putWithRetry(String key, byte[] data) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                backend.put(key, data);
                return;
            } catch (IOException e) {
                last = e;
                if (attempt < 3) {
                    try { Thread.sleep(150L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        throw last;
    }

    private static String blockKey(CloudFile f, int idx) {
        return "b-" + f.id + "-" + idx;
    }

    static String normalize(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) throw new IOException("'..' not allowed in path");
            if (seg.indexOf('\t') >= 0 || seg.indexOf('\n') >= 0 || seg.indexOf('\r') >= 0) {
                throw new IOException("control characters not allowed in path");
            }
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        if (sb.length() == 0) throw new IOException("empty path");
        return sb.toString();
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }
}
