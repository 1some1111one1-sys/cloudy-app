package com.cloudy.app.store;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backend that stores objects as files in a directory. Useful as a "sync folder" backend and as a
 * stand-in for the cloud in tests (it has hooks to simulate latency and outages).
 */
public final class LocalDirBackend implements CloudBackend {
    private final File root;

    // ---- test / diagnostics hooks ----
    public volatile long latencyMs = 0;
    public volatile boolean failGets = false;
    public volatile boolean failPuts = false;
    public final AtomicLong gets = new AtomicLong();
    public final AtomicLong puts = new AtomicLong();
    public final AtomicLong deletes = new AtomicLong();
    private final AtomicLong tmpCounter = new AtomicLong();

    public LocalDirBackend(File root) throws IOException {
        this.root = root;
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("cannot create " + root);
    }

    private File file(String key) throws IOException {
        if (key.isEmpty() || key.contains("/") || key.contains("\\") || key.contains("..")) {
            throw new IOException("bad key: " + key);
        }
        return new File(root, key);
    }

    private void delay() {
        long l = latencyMs;
        if (l > 0) {
            try { Thread.sleep(l); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public byte[] get(String key) throws IOException {
        gets.incrementAndGet();
        delay();
        if (failGets) throw new IOException("simulated outage (get)");
        File f = file(key);
        if (!f.isFile()) return null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, f.length()));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    @Override public void put(String key, byte[] data) throws IOException {
        puts.incrementAndGet();
        delay();
        if (failPuts) throw new IOException("simulated outage (put)");
        File f = file(key);
        File tmp = new File(root, key + "." + tmpCounter.incrementAndGet() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename failed for " + key);
        }
    }

    @Override public void delete(String key) throws IOException {
        deletes.incrementAndGet();
        delay();
        if (failPuts) throw new IOException("simulated outage (delete)");
        File f = file(key);
        if (f.exists() && !f.delete()) throw new IOException("cannot delete " + key);
    }
}
