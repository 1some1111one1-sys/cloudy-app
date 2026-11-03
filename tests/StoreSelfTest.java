package com.cloudy.app.store;

import com.cloudy.app.store.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

/** Dependency-free tests. Run: java -cp out com.cloudy.app.store.StoreSelfTest */
public final class StoreSelfTest {
    static int passed, failed;

    static void check(boolean ok, String name) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }

    static File tmp(String n) throws IOException {
        return Files.createTempDirectory("cloudrun-" + n).toFile();
    }

    static byte[] readAll(CloudStore.CloudFile f) throws IOException {
        byte[] out = new byte[(int) f.size()];
        int pos = 0;
        while (pos < out.length) {
            int n = f.read(pos, out, pos, Math.min(50_000, out.length - pos));
            if (n <= 0) break;
            pos += n;
        }
        return out;
    }

    static final int BS = 64 * 1024;

    // Random reads/writes/truncates against an in-memory model, with caches far smaller than the file.
    static void fuzz() throws Exception {
        System.out.println("fuzz: random I/O vs in-memory model, tiny caches");
        LocalDirBackend cloud = new LocalDirBackend(tmp("cloud1"));
        long ramCap = 3L * BS, diskCap = 6L * BS + 4096;
        CloudStore s = new CloudStore(cloud, tmp("cache1"), ramCap, diskCap, BS, 2);
        CloudStore.CloudFile f = s.create("games/data/big.bin");
        byte[] model = new byte[0];
        Random rnd = new Random(42);
        boolean ok = true, capOk = true;
        for (int i = 0; i < 1500 && ok; i++) {
            int op = rnd.nextInt(10);
            if (op < 4) {                                            // write
                long pos = rnd.nextInt(2_000_000);
                byte[] d = new byte[1 + rnd.nextInt(150_000)];
                rnd.nextBytes(d);
                f.write(pos, d, 0, d.length);
                if (model.length < pos + d.length) model = Arrays.copyOf(model, (int) (pos + d.length));
                System.arraycopy(d, 0, model, (int) pos, d.length);
            } else if (op < 9) {                                     // read + compare
                if (model.length == 0) continue;
                long pos = rnd.nextInt(model.length);
                byte[] got = new byte[1 + rnd.nextInt(200_000)];
                int n = f.read(pos, got, 0, got.length);
                int exp = (int) Math.min(got.length, model.length - pos);
                ok = n == exp && Arrays.equals(Arrays.copyOf(got, n), Arrays.copyOfRange(model, (int) pos, (int) pos + exp));
            } else {                                                 // truncate (shrink or grow)
                int ns = rnd.nextInt(2_200_000);
                f.truncate(ns);
                model = Arrays.copyOf(model, ns);
            }
            if (s.cache().ramBytes() > ramCap || s.cache().diskBytes() > diskCap) capOk = false;
        }
        check(ok, "1500 random ops match the model");
        check(capOk, "RAM <= " + ramCap + " and disk <= " + diskCap + " bytes after every op");
        check(Arrays.equals(readAll(f), model), "full read matches model (size " + model.length + ")");
        System.out.println("        cache: " + s.cache().stats());

        s.flush();
        // A brand-new device with an empty cache sees the same bytes: the cloud is the source of truth.
        CloudStore s2 = new CloudStore(cloud, tmp("cache1b"), ramCap, diskCap, BS, 2);
        check(Arrays.equals(readAll(s2.open("games/data/big.bin")), model), "fresh device reads identical data from cloud");
        s.close(); s2.close();
    }

    static void importAndSparse() throws Exception {
        System.out.println("import / sparse / rename / delete");
        LocalDirBackend cloud = new LocalDirBackend(tmp("cloud2"));
        CloudStore s = new CloudStore(cloud, tmp("cache2"), 2L * BS, 4L * BS, BS, 1);
        byte[] data = new byte[BS * 5 + 123];
        new Random(7).nextBytes(data);
        CloudStore.CloudFile f = s.importStream("apps/demo/base.apk", new ByteArrayInputStream(data));
        check(f.size() == data.length, "imported size correct");
        check(cloud.puts.get() == 6, "imported as 6 blocks (write-through), puts=" + cloud.puts.get());
        check(s.cache().ramBytes() == 0 && s.cache().diskBytes() == 0, "import leaves the local cache empty");
        check(Arrays.equals(readAll(f), data), "import reads back identically");

        CloudStore.CloudFile sp = s.create("sparse.bin");
        sp.truncate(BS * 3L);
        byte[] z = new byte[BS * 3];
        check(sp.read(0, z, 0, z.length) == z.length && Arrays.equals(z, new byte[z.length]), "grown file reads as zeros");

        s.rename("apps/demo/base.apk", "apps/demo/renamed.apk");
        check(s.open("apps/demo/base.apk") == null && s.open("apps/demo/renamed.apk") != null, "rename");
        s.flush();
        long before = cloud.deletes.get();
        s.delete("apps/demo/renamed.apk");
        s.flush();
        check(cloud.deletes.get() - before == 6, "delete removes the 6 cloud blocks");
        boolean threw = false;
        try { f.read(0, new byte[10], 0, 10); } catch (IOException e) { threw = true; }
        check(threw, "reading a deleted file fails cleanly");

        boolean bad = false;
        try { s.create("../escape"); } catch (IOException e) { bad = true; }
        check(bad, "path traversal rejected");
        s.close();
    }

    // Truncate then regrow must read zeros, not stale cloud data.
    static void truncateRegrow() throws Exception {
        System.out.println("truncate + regrow does not resurrect old data");
        LocalDirBackend cloud = new LocalDirBackend(tmp("cloud3"));
        CloudStore s = new CloudStore(cloud, tmp("cache3"), 2L * BS, 4L * BS, BS, 0);
        byte[] ones = new byte[BS * 4];
        Arrays.fill(ones, (byte) 1);
        CloudStore.CloudFile f = s.importStream("x.bin", new ByteArrayInputStream(ones));
        f.truncate(BS + 10);
        f.truncate(BS * 4);
        byte[] got = readAll(f);
        boolean stale = false;
        for (int i = BS + 10; i < got.length; i++) if (got[i] != 0) { stale = true; break; }
        check(!stale, "regrown region is zero before flush");
        check(got[0] == 1 && got[BS + 9] == 1, "kept prefix intact");
        s.flush();
        CloudStore s2 = new CloudStore(cloud, tmp("cache3b"), 2L * BS, 4L * BS, BS, 0);
        got = readAll(s2.open("x.bin"));
        stale = false;
        for (int i = BS + 10; i < got.length; i++) if (got[i] != 0) { stale = true; break; }
        check(!stale, "regrown region is zero after flush on a fresh device");
        s.close(); s2.close();
    }

    // Crash with unsent writes. With RAM budget 0 every dirty block is on disk immediately, so nothing is lost.
    static void crashRecovery() throws Exception {
        System.out.println("crash recovery (dirty blocks on disk survive)");
        LocalDirBackend cloud = new LocalDirBackend(tmp("cloud4"));
        File cache = tmp("cache4");
        CloudStore s = new CloudStore(cloud, cache, 0, 64L * BS, BS, 0);
        byte[] data = new byte[BS * 4 + 500];
        new Random(9).nextBytes(data);
        CloudStore.CloudFile f = s.create("save/progress.dat");
        f.write(0, data, 0, data.length);
        // "crash": no flush, no close. Cloud has no catalog and no blocks.
        check(cloud.puts.get() == 0, "nothing reached the cloud before the crash");

        CloudStore s2 = new CloudStore(cloud, cache, 0, 64L * BS, BS, 0);
        check(s2.cache().stats().recoveredDirty == 5, "5 unsent blocks recovered from cache dir");
        CloudStore.CloudFile g = s2.open("save/progress.dat");
        check(g != null && Arrays.equals(readAll(g), data), "file present with identical content after restart");
        s2.flush();
        CloudStore s3 = new CloudStore(cloud, tmp("cache4b"), 0, 64L * BS, BS, 0);
        check(Arrays.equals(readAll(s3.open("save/progress.dat")), data), "recovered data reached the cloud");
        s2.close(); s3.close();
    }

    // Cloud goes away: writes must back-pressure (throw) instead of growing the cache without bound.
    static void outage() throws Exception {
        System.out.println("cloud outage: back-pressure, no data loss, recovery");
        LocalDirBackend cloud = new LocalDirBackend(tmp("cloud5"));
        long ramCap = 2L * BS, diskCap = 4L * BS + 4096;
        CloudStore s = new CloudStore(cloud, tmp("cache5"), ramCap, diskCap, BS, 0);
        CloudStore.CloudFile f = s.create("o.bin");
        byte[] blk = new byte[BS];
        Random rnd = new Random(3);
        cloud.failPuts = true;
        int written = 0;
        boolean threw = false;
        for (int i = 0; i < 40; i++) {
            rnd.nextBytes(blk);
            try {
                f.write((long) i * BS, blk, 0, BS);
                written++;
            } catch (IOException e) {
                threw = true;
                break;
            }
        }
        check(threw, "write throws once the cache is full of unsent data (after " + written + " blocks)");
        check(s.cache().diskBytes() <= diskCap + BS + 4096, "disk stays within budget + one block: " + s.cache().diskBytes());
        check(s.cache().ramBytes() <= ramCap + BS, "RAM stays within budget + one block: " + s.cache().ramBytes());

        cloud.failPuts = false;
        s.flush();
        CloudStore s2 = new CloudStore(cloud, tmp("cache5b"), ramCap, diskCap, BS, 0);
        CloudStore.CloudFile g = s2.open("o.bin");
        check(g != null && g.size() >= (long) written * BS, "after recovery the cloud holds everything acknowledged");
        s.close(); s2.close();
    }

    // Reads should be served from RAM/disk after the first touch.
    static void cachingAndReadAhead() throws Exception {
        System.out.println("cache hits and read-ahead");
        LocalDirBackend cloud = new LocalDirBackend(tmp("cloud6"));
        CloudStore s = new CloudStore(cloud, tmp("cache6"), 16L * BS, 32L * BS, BS, 3);
        byte[] data = new byte[BS * 12];
        new Random(5).nextBytes(data);
        CloudStore.CloudFile f = s.importStream("v.bin", new ByteArrayInputStream(data));
        cloud.gets.set(0);
        byte[] buf = new byte[BS];
        for (int i = 0; i < 12; i++) f.read((long) i * BS, buf, 0, BS);   // sequential scan
        Thread.sleep(300);
        long firstPass = cloud.gets.get();
        check(firstPass <= 12 + 0, "sequential scan fetched each block at most once (gets=" + firstPass + ")");
        for (int i = 0; i < 12; i++) f.read((long) i * BS, buf, 0, BS);   // second scan: everything cached
        check(cloud.gets.get() == firstPass, "second scan made zero cloud requests");
        s.close();
    }

    // Slow network: read-ahead should hide latency on a sequential scan.
    static void latency() throws Exception {
        System.out.println("latency hiding (50 ms per cloud request)");
        byte[] data = new byte[BS * 20];
        new Random(1).nextBytes(data);
        long[] ms = new long[2];
        for (int mode = 0; mode < 2; mode++) {
            LocalDirBackend cloud = new LocalDirBackend(tmp("cloud7" + mode));
            CloudStore s = new CloudStore(cloud, tmp("cache7" + mode), 64L * BS, 64L * BS, BS, mode == 0 ? 0 : 4);
            CloudStore.CloudFile f = s.importStream("s.bin", new ByteArrayInputStream(data));
            cloud.latencyMs = 50;
            byte[] buf = new byte[BS];
            long t = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                f.read((long) i * BS, buf, 0, BS);
                Thread.sleep(40);                                       // the "app" does some work between reads
            }
            ms[mode] = (System.nanoTime() - t) / 1_000_000;
            s.close();
        }
        System.out.println("        no read-ahead: " + ms[0] + " ms, read-ahead 4: " + ms[1] + " ms");
        check(ms[1] < ms[0], "read-ahead makes the sequential scan faster");
    }

    public static void main(String[] a) throws Exception {
        fuzz();
        importAndSparse();
        truncateRegrow();
        crashRecovery();
        outage();
        cachingAndReadAhead();
        latency();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
