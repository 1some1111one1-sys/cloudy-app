package com.cloudy.app.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-tier LRU block cache (RAM, then a disk directory) with hard byte budgets.
 *
 * Blocks are immutable byte arrays. A block is either clean (identical to the cloud copy and
 * free to evict) or dirty (newer than the cloud copy and must be uploaded before it can be
 * dropped). The tiers are exclusive: a block lives in RAM or on disk, never both.
 *
 * Budget rule: when the caches are over budget, clean blocks are dropped and dirty blocks are
 * uploaded first. If an upload fails (cloud unreachable) and only dirty data is left to evict,
 * put() throws IOException. That is deliberate back-pressure so the cache never silently grows
 * beyond its budget or loses data.
 *
 * Dirty blocks that were spilled to disk survive a crash: on startup they are rediscovered and
 * uploaded by the next flushDirty().
 */
public final class BlockCache {

    public interface Flusher {
        void flush(String key, byte[] data) throws IOException;
    }

    public static final class Stats {
        public long ramHits, diskHits, misses, spills, cleanEvictions, dirtyFlushes, recoveredDirty, lostDirty;
        public long ramBytes, diskBytes;

        @Override public String toString() {
            return "ramHits=" + ramHits + " diskHits=" + diskHits + " misses=" + misses
                    + " spills=" + spills + " cleanEvictions=" + cleanEvictions
                    + " dirtyFlushes=" + dirtyFlushes + " recoveredDirty=" + recoveredDirty
                    + " lostDirty=" + lostDirty + " ramBytes=" + ramBytes + " diskBytes=" + diskBytes;
        }
    }

    private static final class Mem {
        final byte[] data;
        boolean dirty;
        final long version;
        Mem(byte[] data, boolean dirty, long version) { this.data = data; this.dirty = dirty; this.version = version; }
    }

    private static final class Disk {
        File file;
        final long len;          // total file length, header included (this is what counts toward the budget)
        boolean dirty;
        final long version;
        Disk(File file, long len, boolean dirty, long version) {
            this.file = file; this.len = len; this.dirty = dirty; this.version = version;
        }
    }

    private final File dir;
    private final long ramCap;
    private final long diskCap;
    private final Flusher flusher;

    // Insertion-ordered maps: first entry = least recently used. Touching = remove + put.
    private final LinkedHashMap<String, Mem> ram = new LinkedHashMap<>();
    private final LinkedHashMap<String, Disk> disk = new LinkedHashMap<>();
    private long ramBytes;
    private long diskBytes;
    private long versionCounter;
    private final Stats st = new Stats();

    public BlockCache(File dir, long ramCap, long diskCap, Flusher flusher) throws IOException {
        this.dir = dir;
        this.ramCap = Math.max(0, ramCap);
        this.diskCap = Math.max(0, diskCap);
        this.flusher = flusher;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        scan();
    }

    // ------------------------------------------------------------------ public API

    /** @return cached bytes (promoting disk hits into RAM), or null if not cached. */
    public synchronized byte[] get(String key) {
        Mem m = ram.remove(key);
        if (m != null) {
            ram.put(key, m);
            st.ramHits++;
            return m.data;
        }
        Disk d = disk.get(key);
        if (d == null) return null;
        byte[] data;
        try {
            data = readDisk(d);
        } catch (IOException e) {          // file vanished (e.g. OS cleared the cache dir)
            disk.remove(key);
            diskBytes -= d.len;
            d.file.delete();
            if (d.dirty) st.lostDirty++;
            return null;
        }
        disk.remove(key);
        diskBytes -= d.len;
        d.file.delete();
        ram.put(key, new Mem(data, d.dirty, d.version));
        ramBytes += data.length;
        st.diskHits++;
        try { evictRam(); } catch (IOException ignored) { /* cloud down: stay over budget until flush works */ }
        return data;
    }

    public synchronized boolean contains(String key) {
        return ram.containsKey(key) || disk.containsKey(key);
    }

    /** Store a block fetched from the cloud. Ignored if the key is already cached (cache is newer). */
    public synchronized void putClean(String key, byte[] data) {
        if (ram.containsKey(key) || disk.containsKey(key)) return;
        ram.put(key, new Mem(data, false, ++versionCounter));
        ramBytes += data.length;
        try { evictRam(); } catch (IOException ignored) { }
    }

    /** Store a block (replacing any previous version). Dirty blocks are uploaded before eviction. */
    public synchronized void put(String key, byte[] data, boolean dirty) throws IOException {
        // If an earlier outage left us over budget, try to recover before accepting more data.
        if (ramBytes > ramCap || diskBytes > diskCap) {
            evictRam();
            if (diskBytes > diskCap) evictDisk();
            if (diskBytes > diskCap) {
                throw new IOException("cache is full of data not yet uploaded; cloud unreachable?");
            }
        }
        dropAll(key);
        ram.put(key, new Mem(data, dirty, ++versionCounter));
        ramBytes += data.length;
        evictRam();
    }

    /** Drop a block without uploading it (file deleted / truncated). */
    public synchronized void invalidate(String key) {
        dropAll(key);
    }

    public synchronized void recordMiss() { st.misses++; }

    /** Upload every dirty block. One block at a time is held in memory. Throws the first failure. */
    public void flushDirty() throws IOException {
        List<String> keys = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<String, Mem> e : ram.entrySet()) if (e.getValue().dirty) keys.add(e.getKey());
            for (Map.Entry<String, Disk> e : disk.entrySet()) if (e.getValue().dirty) keys.add(e.getKey());
        }
        IOException first = null;
        for (String key : keys) {
            byte[] data;
            long ver;
            synchronized (this) {
                Mem m = ram.get(key);
                if (m != null) {
                    if (!m.dirty) continue;
                    data = m.data;
                    ver = m.version;
                } else {
                    Disk d = disk.get(key);
                    if (d == null || !d.dirty) continue;
                    try {
                        data = readDisk(d);
                    } catch (IOException ex) {
                        disk.remove(key);
                        diskBytes -= d.len;
                        st.lostDirty++;
                        continue;
                    }
                    ver = d.version;
                }
            }
            try {
                flusher.flush(key, data);   // network call, outside the lock
            } catch (IOException ex) {
                if (first == null) first = ex;
                continue;
            }
            synchronized (this) {
                st.dirtyFlushes++;
                markClean(key, ver);
            }
        }
        synchronized (this) {
            try { evictRam(); } catch (IOException ignored) { }
        }
        if (first != null) throw first;
    }

    public synchronized long ramBytes() { return ramBytes; }
    public synchronized long diskBytes() { return diskBytes; }
    public long ramCap() { return ramCap; }
    public long diskCap() { return diskCap; }

    public synchronized Stats stats() {
        Stats s = new Stats();
        s.ramHits = st.ramHits; s.diskHits = st.diskHits; s.misses = st.misses; s.spills = st.spills;
        s.cleanEvictions = st.cleanEvictions; s.dirtyFlushes = st.dirtyFlushes;
        s.recoveredDirty = st.recoveredDirty; s.lostDirty = st.lostDirty;
        s.ramBytes = ramBytes; s.diskBytes = diskBytes;
        return s;
    }

    // ------------------------------------------------------------------ internals

    private void dropAll(String key) {
        Mem m = ram.remove(key);
        if (m != null) ramBytes -= m.data.length;
        Disk d = disk.remove(key);
        if (d != null) {
            diskBytes -= d.len;
            d.file.delete();
        }
    }

    private void markClean(String key, long version) {
        Mem m = ram.get(key);
        if (m != null) {
            if (m.version == version) m.dirty = false;
            return;
        }
        Disk d = disk.get(key);
        if (d != null && d.dirty && d.version == version) {
            File nf = fileFor(key, false);
            if (d.file.renameTo(nf)) {
                d.file = nf;
                d.dirty = false;
            }
        }
    }

    private void evictRam() throws IOException {
        Iterator<Map.Entry<String, Mem>> it = ram.entrySet().iterator();
        while (ramBytes > ramCap && it.hasNext()) {
            Map.Entry<String, Mem> e = it.next();
            Mem m = e.getValue();
            it.remove();
            ramBytes -= m.data.length;
            spill(e.getKey(), m);
        }
    }

    /** Move a block that was just evicted from RAM into the disk tier (or upload / drop it). */
    private void spill(String key, Mem m) throws IOException {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        long fileLen = 4L + kb.length + m.data.length;
        if (diskCap <= 0 || fileLen > diskCap) {           // no usable disk tier
            if (m.dirty) {
                try {
                    flusher.flush(key, m.data);
                    st.dirtyFlushes++;
                } catch (IOException e) {
                    ram.put(key, m);
                    ramBytes += m.data.length;
                    throw e;
                }
            } else {
                st.cleanEvictions++;
            }
            return;
        }
        File f = fileFor(key, m.dirty);
        try {
            writeDisk(f, kb, m.data);
        } catch (IOException e) {
            if (!m.dirty) {                    // clean block: just drop it, the cloud still has it
                st.cleanEvictions++;
                return;
            }
            ram.put(key, m);                   // dirty block: keep it, report the failure
            ramBytes += m.data.length;
            throw e;
        }
        Disk prev = disk.put(key, new Disk(f, fileLen, m.dirty, m.version));
        if (prev != null) {
            diskBytes -= prev.len;
            if (!prev.file.equals(f)) prev.file.delete();
        }
        diskBytes += fileLen;
        st.spills++;
        evictDisk();
    }

    private void evictDisk() throws IOException {
        IOException failure = null;
        Iterator<Map.Entry<String, Disk>> it = disk.entrySet().iterator();
        while (diskBytes > diskCap && it.hasNext()) {
            Map.Entry<String, Disk> e = it.next();
            Disk d = e.getValue();
            if (d.dirty) {
                try {
                    byte[] data = readDisk(d);
                    flusher.flush(e.getKey(), data);
                    st.dirtyFlushes++;
                } catch (IOException ex) {
                    if (failure == null) failure = ex;
                    continue;                                // keep the dirty block, try evicting others
                }
            } else {
                st.cleanEvictions++;
            }
            it.remove();
            diskBytes -= d.len;
            d.file.delete();
        }
        if (diskBytes > diskCap && failure != null) throw failure;
    }

    private File fileFor(String key, boolean dirty) {
        return new File(dir, hash(key) + (dirty ? ".d" : ".c"));
    }

    private static String hash(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void writeDisk(File f, byte[] keyBytes, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            out.writeInt(keyBytes.length);
            out.write(keyBytes);
            out.write(data);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("rename failed: " + f);
        }
    }

    private static byte[] readDisk(Disk d) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(d.file), 1 << 16))) {
            int kl = in.readInt();
            in.readFully(new byte[kl]);
            byte[] data = new byte[(int) (d.len - 4 - kl)];
            in.readFully(data);
            return data;
        }
    }

    /** Rebuild the disk index from a previous run: warms the cache and recovers unsent dirty blocks. */
    private void scan() {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".tmp")) { f.delete(); continue; }
            boolean dirty = n.endsWith(".d");
            if (!dirty && !n.endsWith(".c")) continue;
            try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
                int kl = in.readInt();
                if (kl <= 0 || kl > 4096 || 4L + kl > f.length()) { f.delete(); continue; }
                byte[] kb = new byte[kl];
                in.readFully(kb);
                String key = new String(kb, StandardCharsets.UTF_8);
                Disk old = disk.get(key);
                if (old != null) {
                    if (old.dirty) { f.delete(); continue; }       // keep the dirty one
                    diskBytes -= old.len;
                    old.file.delete();
                }
                disk.put(key, new Disk(f, f.length(), dirty, ++versionCounter));
                diskBytes += f.length();
                if (dirty) st.recoveredDirty++;
            } catch (IOException e) {
                f.delete();
            }
        }
        try { evictDisk(); } catch (IOException ignored) { }
    }
}
