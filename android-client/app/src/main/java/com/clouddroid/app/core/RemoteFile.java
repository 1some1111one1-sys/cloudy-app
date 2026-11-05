package com.clouddroid.app.core;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A file that lives in the cloud and is read on demand, block by block, through a {@link BlockCache}.
 * Nothing is downloaded until something reads it, and only the blocks that are read (plus a little read-ahead when
 * the reads are sequential) are fetched. Memory use is bounded by the cache's limits plus the few blocks being fetched.
 */
public final class RemoteFile {
    public static final int DEFAULT_BLOCK = 1024 * 1024;

    private final CloudClient client;
    private final String path;
    private final long size;
    private final String keyPrefix;
    private final BlockCache cache;
    private final int blockSize;
    private final int readAhead;
    private final Executor prefetcher;
    private final Map<Integer, FutureTask<byte[]>> inFlight = new HashMap<>();
    private final AtomicLong networkBytes = new AtomicLong();
    private volatile int lastBlock = -2;

    /**
     * @param id        anything that changes when the file changes (provider, path, size, modified time)
     * @param blockSize a multiple of 16
     * @param readAhead how many following blocks to fetch in the background when reads are sequential (0 = none)
     */
    public RemoteFile(CloudClient client, String path, long size, String id, BlockCache cache, int blockSize,
                      int readAhead, Executor prefetcher) {
        if (blockSize <= 0 || blockSize % 16 != 0) throw new IllegalArgumentException("blockSize must be a multiple of 16");
        this.client = client;
        this.path = path;
        this.size = size;
        this.keyPrefix = id + "#" + blockSize + "#";
        this.cache = cache;
        this.blockSize = blockSize;
        this.readAhead = Math.max(0, readAhead);
        this.prefetcher = prefetcher;
    }

    /** Two background threads and a short queue: prefetching never piles up. Daemon threads, so they never keep the app alive. */
    public static Executor newPrefetcher() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), r -> {
            Thread t = new Thread(r, "clouddroid-prefetch");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    public long size() {
        return size;
    }

    /** Bytes this file has fetched from the network so far. */
    public long networkBytes() {
        return networkBytes.get();
    }

    /**
     * Reads up to {@code len} bytes at {@code pos} into {@code buf}. Returns the count, or -1 at or beyond the end.
     * Returns fewer than {@code len} only at the end of the file.
     */
    public int read(long pos, byte[] buf, int off, int len) throws IOException {
        if (pos < 0) throw new IOException("Negative position");
        if (pos >= size) return -1;
        int want = (int) Math.min(len, size - pos);
        int done = 0;
        while (done < want) {
            long p = pos + done;
            int idx = (int) (p / blockSize);
            byte[] block = block(idx, true);
            int inBlock = (int) (p - (long) idx * blockSize);
            int n = Math.min(want - done, block.length - inBlock);
            if (n <= 0) throw new IOException("The cloud returned less data than the file's size says");
            System.arraycopy(block, inBlock, buf, off + done, n);
            done += n;
        }
        int first = (int) (pos / blockSize);
        if (readAhead > 0 && (first == lastBlock + 1 || first == lastBlock)) {
            int last = (int) ((pos + done - 1) / blockSize);
            for (int i = 1; i <= readAhead; i++) prefetch(last + i);
        }
        lastBlock = (int) ((pos + done - 1) / blockSize);
        return done;
    }

    private int blockCount() {
        return (int) ((size + blockSize - 1) / blockSize);
    }

    private void prefetch(int idx) {
        if (idx >= blockCount() || cache.contains(key(idx))) return;
        synchronized (inFlight) {
            if (inFlight.containsKey(idx)) return;
        }
        prefetcher.execute(() -> {
            try {
                block(idx, false);
            } catch (Exception ignored) {
                // a failed read-ahead is retried by the real read if it is still needed
            }
        });
    }

    private String key(int idx) {
        return keyPrefix + idx;
    }

    /** The block from the cache, or fetched now. Concurrent callers for one block share a single fetch. */
    private byte[] block(int idx, boolean count) throws IOException {
        String key = key(idx);
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        FutureTask<byte[]> task;
        boolean mine = false;
        synchronized (inFlight) {
            task = inFlight.get(idx);
            if (task == null) {
                task = new FutureTask<>(() -> fetch(idx));
                inFlight.put(idx, task);
                mine = true;
            }
        }
        if (mine) {
            try {
                task.run();
            } finally {
                synchronized (inFlight) {
                    inFlight.remove(idx);
                }
            }
        }
        try {
            byte[] data = task.get();
            if (mine) cache.put(key, data);
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading from the cloud");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(String.valueOf(c), c);
        }
    }

    private byte[] fetch(int idx) throws IOException {
        long start = (long) idx * blockSize;
        int len = (int) Math.min(blockSize, size - start);
        byte[] data = new byte[len];
        IOException last = new IOException("Interrupted while reading from the cloud");
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                int got = client.readRange(path, start, data, len);
                if (got != len) throw new IOException("The cloud sent " + got + " bytes instead of " + len);
                networkBytes.addAndGet(len);
                return data;
            } catch (CloudException e) {
                if (e.status == 401 || e.status == 403 || e.status == 404 || e.status == 400 || e.status == 501) throw e;
                last = e;
            } catch (IOException e) {
                last = e;
            }
            try {
                Thread.sleep(250L << attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last;
    }
}
