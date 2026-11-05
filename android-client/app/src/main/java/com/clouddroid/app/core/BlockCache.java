package com.clouddroid.app.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Two-tier least-recently-used cache for blocks of remote files: memory first, then a directory of files
 * (the app's cache directory on a phone). Both tiers have hard byte limits that are never exceeded for longer
 * than one call. Every block is a copy of data that still exists in the cloud, so any block can be dropped at any
 * time; nothing here is ever the only copy. A block lives in memory or on disk, not both.
 *
 * <p>The directory is emptied when the cache is created: blocks left by an earlier run might belong to a file that
 * has changed since.
 */
public final class BlockCache {
    public static final class Stats {
        public long ramHits, diskHits, misses, evictions;
        public long ramBytes, diskBytes;

        @Override
        public String toString() {
            return "ramHits=" + ramHits + " diskHits=" + diskHits + " misses=" + misses + " evictions=" + evictions
                    + " ramBytes=" + ramBytes + " diskBytes=" + diskBytes;
        }
    }

    private static final class OnDisk {
        final File file;
        final long len;

        OnDisk(File file, long len) {
            this.file = file;
            this.len = len;
        }
    }

    private final File dir;
    private final long ramCap;
    private final long diskCap;
    // First entry = least recently used. Touching a block = remove + put again.
    private final LinkedHashMap<String, byte[]> ram = new LinkedHashMap<>();
    private final LinkedHashMap<String, OnDisk> disk = new LinkedHashMap<>();
    private long ramBytes;
    private long diskBytes;
    private final Stats stats = new Stats();

    public BlockCache(File dir, long ramCap, long diskCap) throws IOException {
        this.dir = dir;
        this.ramCap = Math.max(0, ramCap);
        this.diskCap = Math.max(0, diskCap);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create the cache folder " + dir);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** The block, or null if it is not cached. A hit on disk moves the block back into memory. */
    public synchronized byte[] get(String key) {
        byte[] m = ram.remove(key);
        if (m != null) {
            ram.put(key, m);
            stats.ramHits++;
            return m;
        }
        OnDisk d = disk.remove(key);
        if (d == null) {
            stats.misses++;
            return null;
        }
        diskBytes -= d.len;
        byte[] data;
        try {
            data = readBlock(d);
        } catch (IOException e) {                       // the system may have cleared the folder
            //noinspection ResultOfMethodCallIgnored
            d.file.delete();
            stats.misses++;
            return null;
        }
        //noinspection ResultOfMethodCallIgnored
        d.file.delete();
        stats.diskHits++;
        insertRam(key, data);
        return data;
    }

    public synchronized boolean contains(String key) {
        return ram.containsKey(key) || disk.containsKey(key);
    }

    public synchronized void put(String key, byte[] data) {
        byte[] prev = ram.remove(key);
        if (prev != null) ramBytes -= prev.length;
        OnDisk d = disk.remove(key);
        if (d != null) {
            diskBytes -= d.len;
            //noinspection ResultOfMethodCallIgnored
            d.file.delete();
        }
        insertRam(key, data);
    }

    /** Forgets every block whose key starts with the prefix (a file that changed or was closed). */
    public synchronized void drop(String prefix) {
        for (Iterator<Map.Entry<String, byte[]>> it = ram.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, byte[]> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                ramBytes -= e.getValue().length;
                it.remove();
            }
        }
        for (Iterator<Map.Entry<String, OnDisk>> it = disk.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, OnDisk> e = it.next();
            if (e.getKey().startsWith(prefix)) {
                diskBytes -= e.getValue().len;
                //noinspection ResultOfMethodCallIgnored
                e.getValue().file.delete();
                it.remove();
            }
        }
    }

    public synchronized void clear() {
        ram.clear();
        ramBytes = 0;
        for (OnDisk d : disk.values()) //noinspection ResultOfMethodCallIgnored
            d.file.delete();
        disk.clear();
        diskBytes = 0;
    }

    public synchronized Stats stats() {
        Stats s = new Stats();
        s.ramHits = stats.ramHits;
        s.diskHits = stats.diskHits;
        s.misses = stats.misses;
        s.evictions = stats.evictions;
        s.ramBytes = ramBytes;
        s.diskBytes = diskBytes;
        return s;
    }

    public long ramCap() {
        return ramCap;
    }

    public long diskCap() {
        return diskCap;
    }

    // ------------------------------------------------------------------ internals

    private void insertRam(String key, byte[] data) {
        if (data.length > ramCap) {                      // larger than the whole memory budget: go straight to disk
            spill(key, data);
            return;
        }
        ram.put(key, data);
        ramBytes += data.length;
        while (ramBytes > ramCap) {
            Iterator<Map.Entry<String, byte[]>> it = ram.entrySet().iterator();
            Map.Entry<String, byte[]> oldest = it.next();
            it.remove();
            ramBytes -= oldest.getValue().length;
            spill(oldest.getKey(), oldest.getValue());
        }
    }

    /** A block leaving memory moves to disk if the disk budget can take it, otherwise it is dropped. */
    private void spill(String key, byte[] data) {
        long len = 4L + keyBytes(key).length + data.length;
        if (diskCap <= 0 || len > diskCap) {
            stats.evictions++;
            return;
        }
        File f = new File(dir, hash(key));
        try {
            writeBlock(f, key, data);
        } catch (IOException e) {                        // no space or no folder: just don't cache it
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            stats.evictions++;
            return;
        }
        disk.put(key, new OnDisk(f, len));
        diskBytes += len;
        while (diskBytes > diskCap) {
            Iterator<Map.Entry<String, OnDisk>> it = disk.entrySet().iterator();
            Map.Entry<String, OnDisk> oldest = it.next();
            it.remove();
            diskBytes -= oldest.getValue().len;
            //noinspection ResultOfMethodCallIgnored
            oldest.getValue().file.delete();
            stats.evictions++;
        }
    }

    private static byte[] keyBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(keyBytes(key));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeBlock(File f, String key, byte[] data) throws IOException {
        byte[] kb = keyBytes(key);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 16))) {
            out.writeInt(kb.length);
            out.write(kb);
            out.write(data);
        }
    }

    private static byte[] readBlock(OnDisk d) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(d.file), 1 << 16))) {
            int kl = in.readInt();
            in.readFully(new byte[kl]);
            byte[] data = new byte[(int) (d.len - 4 - kl)];
            in.readFully(data);
            return data;
        }
    }
}
