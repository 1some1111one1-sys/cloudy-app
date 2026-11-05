package com.clouddroid.app.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static com.clouddroid.app.core.TestMain.check;
import static com.clouddroid.app.core.TestMain.eq;
import static com.clouddroid.app.core.TestMain.expectFail;
import static com.clouddroid.app.core.TestMain.run;
import static com.clouddroid.app.core.TestMain.tmpDir;

/** Tests for partial reads, the bounded block cache and lazily-read remote files. */
final class TestStream {
    private TestStream() {
    }

    static void runAll() {
        run("block cache limits", TestStream::testCacheLimits);
        run("mega partial reads + lazy file", TestStream::testMega);
        run("terabox partial reads + lazy file", TestStream::testTeraBox);
        run("concurrent readers", TestStream::testConcurrent);
        run("sequential read-ahead", TestStream::testReadAhead);
        run("zip pack", TestStream::testZipPack);
    }

    private static byte[] bytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static final Executor NOW = Runnable::run;       // no background threads: deterministic

    // ------------------------------------------------------------------ cache

    private static void testCacheLimits() throws Exception {
        BlockCache c = new BlockCache(tmpDir(), 3000, 5000);
        for (int i = 0; i < 10; i++) {
            c.put("k" + i, bytes(1000, i));
            BlockCache.Stats s = c.stats();
            check("ram within cap after put " + i, s.ramBytes <= 3000);
            check("disk within cap after put " + i, s.diskBytes <= 5000);
        }
        check("newest block in cache", c.get("k9") != null);
        check("oldest block evicted", !c.contains("k0") && !c.contains("k1"));
        check("a middle block comes back from disk intact", java.util.Arrays.equals(bytes(1000, 6), c.get("k6")));
        check("disk hit counted", c.stats().diskHits >= 1);
        check("still within caps after a disk hit", c.stats().ramBytes <= 3000 && c.stats().diskBytes <= 5000);
        c.drop("k");
        eq("drop empties it", 0L, c.stats().ramBytes + c.stats().diskBytes);

        BlockCache noDisk = new BlockCache(tmpDir(), 2000, 0);
        for (int i = 0; i < 5; i++) noDisk.put("a" + i, bytes(1000, i));
        check("no disk tier: memory capped, old blocks dropped", noDisk.stats().ramBytes <= 2000 && !noDisk.contains("a0"));

        BlockCache huge = new BlockCache(tmpDir(), 100, 10_000);
        huge.put("big", bytes(5000, 1));
        check("block bigger than memory goes to disk", huge.stats().ramBytes == 0 && huge.get("big") != null);

        File d = tmpDir();
        BlockCache first = new BlockCache(d, 1000, 10_000);
        first.put("x", bytes(900, 1));
        first.put("y", bytes(900, 2));
        new BlockCache(d, 1000, 10_000);
        eq("a new cache starts empty", 0, d.listFiles().length);
    }

    // ------------------------------------------------------------------ providers

    private static void readChecks(String label, CloudClient c, String path, byte[] expected) throws Exception {
        Random rnd = new Random(5);
        boolean all = true;
        for (int i = 0; i < 60 && all; i++) {
            int off = rnd.nextInt(expected.length);
            int len = 1 + rnd.nextInt(Math.min(300_000, expected.length - off));
            byte[] out = new byte[len];
            int got = c.readRange(path, off, out, len);
            all = got == len && java.util.Arrays.equals(java.util.Arrays.copyOfRange(expected, off, off + len), out);
        }
        check(label + ": 60 random unaligned ranges match the original", all);
        byte[] tail = new byte[50];
        eq(label + ": tail read short at EOF", 10, c.readRange(path, expected.length - 10, tail, 50));
        check(label + ": tail content", java.util.Arrays.equals(java.util.Arrays.copyOfRange(expected, expected.length - 10, expected.length),
                java.util.Arrays.copyOf(tail, 10)));
        eq(label + ": read at EOF", 0, c.readRange(path, expected.length, tail, 50));
        eq(label + ": read past EOF", 0, c.readRange(path, expected.length + 99, tail, 50));
    }

    private static void lazyFileChecks(String label, CloudClient c, String path, byte[] expected, java.util.concurrent.atomic.AtomicLong served)
            throws Exception {
        int block = 256 * 1024;
        BlockCache cache = new BlockCache(tmpDir(), 1_000_000, 2_000_000);
        RemoteFile f = new RemoteFile(c, path, expected.length, label + path, cache, block, 0, NOW);
        long before = served.get();

        byte[] out = new byte[100];
        int mid = 3_000_007;
        eq(label + ": small read in the middle", 100, f.read(mid, out, 0, 100));
        check(label + ": middle content", java.util.Arrays.equals(java.util.Arrays.copyOfRange(expected, mid, mid + 100), out));
        check(label + ": only one block fetched for 100 bytes (" + (served.get() - before) + ")", served.get() - before <= block);
        long after1 = served.get();
        f.read(mid, out, 0, 100);
        f.read(mid + 50, out, 0, 100);
        eq(label + ": re-reading the same place uses no network", after1, served.get());

        // a read that spans three blocks
        byte[] span = new byte[block * 2 + 1000];
        int pos = block * 5 - 500;
        eq(label + ": multi-block read", span.length, f.read(pos, span, 0, span.length));
        check(label + ": multi-block content", java.util.Arrays.equals(java.util.Arrays.copyOfRange(expected, pos, pos + span.length), span));

        // reading the whole file in odd sized pieces keeps memory and disk inside their limits
        long maxRam = 0, maxDisk = 0;
        ByteArrayBuilder all = new ByteArrayBuilder();
        byte[] piece = new byte[70_001];
        for (long p = 0; ; ) {
            int n = f.read(p, piece, 0, piece.length);
            if (n < 0) break;
            all.write(piece, n);
            p += n;
            BlockCache.Stats s = cache.stats();
            maxRam = Math.max(maxRam, s.ramBytes);
            maxDisk = Math.max(maxDisk, s.diskBytes);
        }
        check(label + ": whole file read in pieces equals the original", java.util.Arrays.equals(expected, all.toByteArray()));
        check(label + ": memory never above its limit (" + maxRam + ")", maxRam <= 1_000_000);
        check(label + ": disk never above its limit (" + maxDisk + ")", maxDisk <= 2_000_000);
        eq(label + ": read at end", -1, f.read(expected.length, piece, 0, 10));
        check(label + ": fetched about one file's worth, not more (" + f.networkBytes() + ")", f.networkBytes() <= expected.length + 4L * block);
    }

    private static final class ByteArrayBuilder {
        private final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();

        void write(byte[] x, int n) {
            b.write(x, 0, n);
        }

        byte[] toByteArray() {
            return b.toByteArray();
        }
    }

    private static void testMega() throws Exception {
        MegaClient.retryBaseMs = 1;
        MockMegaServer s = new MockMegaServer(2);
        try {
            MockMegaServer.N docs = s.folder(s.rootId, "Docs");
            byte[] data = bytes(5_300_003, 21);
            s.file(docs.h, "movie.bin", data);
            s.file(docs.h, "tiny.txt", "hello".getBytes());
            MegaClient c = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "", "");
            readChecks("mega", c, "/Docs/movie.bin", data);
            lazyFileChecks("mega", c, "/Docs/movie.bin", data, s.dataServed);
            check("mega: folder refused", expectFail("range of folder", () -> c.readRange("/Docs", 0, new byte[4], 4)) != null);
            byte[] t = new byte[10];
            eq("mega: tiny file", 5, c.readRange("/Docs/tiny.txt", 0, t, 10));
            eq("mega: tiny file content", "hello", new String(t, 0, 5));
        } finally {
            s.stop();
        }
    }

    private static void testTeraBox() throws Exception {
        MockTeraBoxServer s = new MockTeraBoxServer();
        try {
            s.dir("/Videos");
            byte[] data = bytes(5_300_003, 22);
            s.put("/Videos/movie.bin", data);
            TeraBoxClient.clearCaches();
            TeraBoxClient c = new TeraBoxClient(new JdkTransport(), s.base(), MockTeraBoxServer.COOKIE);
            readChecks("terabox", c, "/Videos/movie.bin", data);
            lazyFileChecks("terabox", c, "/Videos/movie.bin", data, s.dataServed);

            s.ignoreRange = true;
            CloudException e = expectFail("server ignoring Range", () -> c.readRange("/Videos/movie.bin", 1_000_000, new byte[1000], 1000));
            check("terabox: a server that ignores Range gives a clear error", e != null && e.getMessage().contains("ignored the Range"));
        } finally {
            s.stop();
        }
    }

    // ------------------------------------------------------------------ concurrency and read-ahead

    private static void testConcurrent() throws Exception {
        MegaClient.retryBaseMs = 1;
        MockMegaServer s = new MockMegaServer(2);
        try {
            byte[] data = bytes(4_000_000, 33);
            s.file(s.rootId, "f.bin", data);
            MegaClient c = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "", "");
            BlockCache cache = new BlockCache(tmpDir(), 600_000, 1_000_000);
            RemoteFile f = new RemoteFile(c, "/f.bin", data.length, "conc", cache, 128 * 1024, 2, RemoteFile.newPrefetcher());
            AtomicInteger bad = new AtomicInteger();
            List<Thread> ts = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                final int seed = t;
                Thread th = new Thread(() -> {
                    Random r = new Random(seed);
                    try {
                        for (int i = 0; i < 40; i++) {
                            int off = r.nextInt(data.length - 1);
                            int len = 1 + r.nextInt(Math.min(200_000, data.length - off));
                            byte[] out = new byte[len];
                            int n = f.read(off, out, 0, len);
                            if (n != len || !java.util.Arrays.equals(java.util.Arrays.copyOfRange(data, off, off + len), out)) bad.incrementAndGet();
                        }
                    } catch (Exception e) {
                        bad.incrementAndGet();
                    }
                });
                ts.add(th);
                th.start();
            }
            for (Thread th : ts) th.join();
            eq("8 threads, 320 random reads, no wrong byte", 0, bad.get());
            check("limits held under concurrency", cache.stats().ramBytes <= 600_000 && cache.stats().diskBytes <= 1_000_000);
        } finally {
            s.stop();
        }
    }

    private static void testReadAhead() throws Exception {
        MegaClient.retryBaseMs = 1;
        MockMegaServer s = new MockMegaServer(2);
        try {
            byte[] data = bytes(3_000_000, 44);
            s.file(s.rootId, "seq.bin", data);
            MegaClient c = new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "", "");
            BlockCache cache = new BlockCache(tmpDir(), 2_000_000, 0);
            int block = 128 * 1024;
            RemoteFile f = new RemoteFile(c, "/seq.bin", data.length, "ra", cache, block, 3, RemoteFile.newPrefetcher());
            byte[] out = new byte[block];
            f.read(0, out, 0, block);
            f.read(block, out, 0, block);          // sequential: this starts the read-ahead
            long deadline = System.currentTimeMillis() + 5000;
            while (!cache.contains("ra#" + block + "#4") && System.currentTimeMillis() < deadline) Thread.sleep(20);
            check("blocks after a sequential read are fetched ahead", cache.contains("ra#" + block + "#4"));
            check("read-ahead stops at a few blocks (not the whole file)", !cache.contains("ra#" + block + "#10"));
        } finally {
            s.stop();
        }
    }

    private static void testZipPack() throws Exception {
        eq("safe name plain", "a/b.txt", ZipPack.safeName("a/b.txt"));
        eq("safe name cleans slashes", "a/b", ZipPack.safeName("/a//./b"));
        eq("safe name backslashes", "a/b", ZipPack.safeName("a\\b"));
        check("dot-dot refused", ZipPack.safeName("../x") == null && ZipPack.safeName("a/../../x") == null);
        check("empty refused", ZipPack.safeName("") == null && ZipPack.safeName("/") == null && ZipPack.safeName(null) == null);

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] big = bytes(3_000_000, 9);
        try (ZipPack.Writer w = new ZipPack.Writer(bos)) {
            w.add("save/slot1.dat", new java.io.ByteArrayInputStream("one".getBytes()));
            w.add("save/deep/slot2.dat", new java.io.ByteArrayInputStream(big));
            w.add("empty.txt", new java.io.ByteArrayInputStream(new byte[0]));
            eq("writer counts files", 3, w.count());
        }
        java.util.Map<String, byte[]> got = new java.util.TreeMap<>();
        int n = ZipPack.unpack(new java.io.ByteArrayInputStream(bos.toByteArray()), name -> new java.io.ByteArrayOutputStream() {
            @Override
            public void close() {
                got.put(name, toByteArray());
            }
        });
        eq("unpack count", 3, n);
        check("unpacked content equals the original", java.util.Arrays.equals(big, got.get("save/deep/slot2.dat"))
                && "one".equals(new String(got.get("save/slot1.dat"))) && got.get("empty.txt").length == 0);

        java.io.ByteArrayOutputStream evil = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(evil)) {
            z.putNextEntry(new java.util.zip.ZipEntry("../../escape.txt"));
            z.write(1);
            z.closeEntry();
        }
        java.util.List<String> opened = new ArrayList<>();
        boolean refused = false;
        try {
            ZipPack.unpack(new java.io.ByteArrayInputStream(evil.toByteArray()), name -> {
                opened.add(name);
                return new java.io.ByteArrayOutputStream();
            });
        } catch (java.io.IOException e) {
            refused = true;
        }
        check("an archive with ../ names is refused and nothing is opened", refused && opened.isEmpty());
    }
}
