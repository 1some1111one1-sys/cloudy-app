package com.cloudy.app.core;

import com.cloudy.app.store.CloudStore;
import com.cloudy.app.store.DriveBackend;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;

/**
 * Runs the block store on top of every drive type, against in-process imitations of Google Drive, MEGA, TeraBox and
 * WebDAV: import, random writes with a tiny cache, overwrite, flush, reading back from a second "phone" with an empty
 * cache and a fresh sign-in, delete, and the on-drive layout.
 */
public final class TestDrives {
    static int passed, failed;
    static final int BS = 64 * 1024;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("  PASS  " + name); }
        else { failed++; System.out.println("  FAIL  " + name); }
    }

    static byte[] readAll(CloudStore.CloudFile f) throws IOException {
        byte[] out = new byte[(int) f.size()];
        int pos = 0;
        while (pos < out.length) {
            int n = f.read(pos, out, pos, Math.min(100_000, out.length - pos));
            if (n <= 0) break;
            pos += n;
        }
        return out;
    }

    static File tmp(String n) throws IOException { return Files.createTempDirectory("cy-" + n).toFile(); }

    /** One drive type: how to sign in (twice, to model two sessions) and how to stop the fake server. */
    interface Drive {
        CloudClient signIn() throws Exception;
        void stop();
    }

    static void scenario(String label, Drive d) throws Exception {
        System.out.println(label);
        long ram = 3L * BS, disk = 6L * BS + 4096;

        // ---- phone A: import a file, write some data with a cache far smaller than the data, overwrite, flush
        DriveBackend ba = new DriveBackend(d.signIn(), "/CloudyDrive", tmp("tmpA"));
        CloudStore a = new CloudStore(ba, tmp("cacheA"), ram, disk, BS, 2);

        byte[] apk = new byte[BS * 5 + 321];
        new Random(11).nextBytes(apk);
        CloudStore.CloudFile imp = a.importStream("apps/demo/base.apk", new ByteArrayInputStream(apk));
        check("imported " + apk.length + " bytes as write-through", imp.size() == apk.length);
        check("import left the local cache empty", a.cache().ramBytes() == 0 && a.cache().diskBytes() == 0);
        check("read back through the cache", Arrays.equals(readAll(imp), apk));

        byte[] data = new byte[BS * 8 + 99];
        new Random(12).nextBytes(data);
        CloudStore.CloudFile f = a.create("data/com.demo/save.bin");
        f.write(0, data, 0, data.length);                         // 9 blocks into a 3-block RAM / 6-block disk cache
        check("cache stayed inside its budget (RAM " + a.cache().ramBytes() + ", disk " + a.cache().diskBytes() + ")",
                a.cache().ramBytes() <= ram && a.cache().diskBytes() <= disk);

        byte[] patch = new byte[1000];
        new Random(13).nextBytes(patch);
        f.write(BS * 2 + 50, patch, 0, patch.length);              // overwrite inside an existing block
        System.arraycopy(patch, 0, data, BS * 2 + 50, patch.length);
        a.flush();
        f.write(BS * 2 + 50, patch, 0, 10);                        // and again after a flush: the block already exists remotely
        a.flush();
        check("sequence of overwrites flushed without error", true);

        // ---- phone B: new session, empty cache, signs in again, sees everything
        DriveBackend bb = new DriveBackend(d.signIn(), "/CloudyDrive", tmp("tmpB"));
        CloudStore b = new CloudStore(bb, tmp("cacheB"), ram, disk, BS, 2);
        CloudStore.CloudFile g = b.open("data/com.demo/save.bin");
        check("second phone finds the file list", g != null && b.open("apps/demo/base.apk") != null);
        check("second phone reads the written data", g != null && Arrays.equals(readAll(g), data));
        check("second phone reads the imported APK", Arrays.equals(readAll(b.open("apps/demo/base.apk")), apk));
        check("catalog and blocks are where documented",
                bb.get("catalog") != null && bb.get("b-" + "0".repeat(32) + "-0") == null);

        // ---- truncate + delete through phone B
        g.truncate(BS + 5);
        b.delete("apps/demo/base.apk");
        b.flush();
        DriveBackend bc = new DriveBackend(d.signIn(), "/CloudyDrive", tmp("tmpC"));
        CloudStore c = new CloudStore(bc, tmp("cacheC"), ram, disk, BS, 0);
        check("delete + truncate are visible to a third session",
                c.open("apps/demo/base.apk") == null && c.open("data/com.demo/save.bin").size() == BS + 5);
        check("truncated data still correct",
                Arrays.equals(readAll(c.open("data/com.demo/save.bin")), Arrays.copyOf(data, BS + 5)));

        a.close(); b.close(); c.close();
        d.stop();
    }

    static void layout() throws Exception {
        System.out.println("on-drive layout");
        DriveBackend b = new DriveBackend(new WebDavClient(new JdkTransport(), "http://127.0.0.1:1/", "", ""),
                "/CloudyDrive", tmp("lay"));
        String id = "0123456789abcdef0123456789abcdef";
        check("block 0 path", b.pathFor("b-" + id + "-0").equals("/CloudyDrive/" + id + "/0/0"));
        check("block 255 and 256 are in different folders",
                b.pathFor("b-" + id + "-255").equals("/CloudyDrive/" + id + "/0/255")
                        && b.pathFor("b-" + id + "-256").equals("/CloudyDrive/" + id + "/1/256"));
        check("catalog path", b.pathFor("catalog").equals("/CloudyDrive/catalog"));
    }

    // 600 blocks on one file must never put more than 256 entries in one folder.
    static void manyBlocks() throws Exception {
        System.out.println("many blocks (folder sharding), WebDAV");
        File root = tmp("davmany");
        MockDavServer s = new MockDavServer(root, "", "me", "pw", false);
        try {
            WebDavClient c = new WebDavClient(new JdkTransport(), s.url() + "/", "me", "pw");
            int bs = 4096;
            CloudStore st = new CloudStore(new DriveBackend(c, "/CloudyDrive", tmp("manytmp")), tmp("manycache"), 16L * bs, 16L * bs, bs, 0);
            byte[] data = new byte[bs * 600];
            new Random(5).nextBytes(data);
            CloudStore.CloudFile f = st.importStream("big.bin", new ByteArrayInputStream(data));
            int worst = 0;
            File base = new File(root, "CloudyDrive");
            File[] ids = base.listFiles(File::isDirectory);
            for (File idDir : ids) for (File shard : idDir.listFiles()) worst = Math.max(worst, shard.list().length);
            check("600 blocks -> largest folder has " + worst + " entries (<= 256)", worst > 0 && worst <= 256);
            check("all 600 blocks read back", Arrays.equals(readAll(f), data));
            st.close();
        } finally {
            s.stop();
        }
    }

    static void failures() throws Exception {
        System.out.println("errors are reported, not swallowed");
        File root = tmp("daverr");
        MockDavServer s = new MockDavServer(root, "", "me", "pw", false);
        try {
            WebDavClient bad = new WebDavClient(new JdkTransport(), s.url() + "/", "me", "WRONG");
            DriveBackend b = new DriveBackend(bad, "/CloudyDrive", tmp("errtmp"));
            boolean threw = false;
            try { b.put("catalog", new byte[]{1}); } catch (IOException e) { threw = true; }
            check("wrong password -> put throws", threw);
            threw = false;
            try { b.get("catalog"); } catch (IOException e) { threw = true; }
            check("wrong password -> get throws (is not mistaken for 'missing')", threw);
        } finally {
            s.stop();
        }
    }

    public static void main(String[] args) throws Exception {
        layout();

        scenario("Google Drive", new Drive() {
            final MockDriveServer s = new MockDriveServer("RT-1");
            public CloudClient signIn() {
                return new GoogleDriveClient(new JdkTransport(), s.base(), s.base() + "/token",
                        MockDriveServer.CLIENT_ID, MockDriveServer.CLIENT_SECRET, s.refreshToken);
            }
            public void stop() { s.stop(); }
        });

        scenario("MEGA", new Drive() {
            final MockMegaServer s = new MockMegaServer(2);
            public CloudClient signIn() {
                return new MegaClient(new JdkTransport(), s.base(), MockMegaServer.EMAIL, MockMegaServer.PASSWORD, "", "");
            }
            public void stop() { s.stop(); }
        });

        scenario("TeraBox (web-session cookie)", new Drive() {
            final MockTeraBoxServer s = new MockTeraBoxServer();
            public CloudClient signIn() {
                TeraBoxClient.clearCaches();                      // a different phone: no remembered tokens or listings
                return new TeraBoxClient(new JdkTransport(), s.base(), MockTeraBoxServer.COOKIE);
            }
            public void stop() { s.stop(); }
        });

        final File davRoot = tmp("dav");
        scenario("WebDAV", new Drive() {
            final MockDavServer s = davServer(davRoot);
            public CloudClient signIn() {
                return new WebDavClient(new JdkTransport(), s.url() + "/", "me", "pw");
            }
            public void stop() { s.stop(); }
        });

        manyBlocks();
        failures();
        System.out.println("\n" + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static MockDavServer davServer(File root) {
        try { return new MockDavServer(root, "", "me", "pw", false); }
        catch (IOException e) { throw new RuntimeException(e); }
    }
}
