package com.cloudy.app.store;

import com.cloudy.app.core.CloudClient;
import com.cloudy.app.core.CloudEntry;
import com.cloudy.app.core.CloudException;
import com.cloudy.app.core.Paths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets the block store keep its blocks on any {@link CloudClient}: Google Drive, MEGA, TeraBox, WebDAV or S3.
 *
 * Layout inside the drive (everything lives under one folder, {@code root}):
 * <pre>
 *   /CloudyDrive/catalog                      the file list
 *   /CloudyDrive/&lt;fileId&gt;/&lt;n/256&gt;/&lt;n&gt;       block n of a file
 * </pre>
 * Blocks are spread over sub-folders of at most 256 entries because some services (TeraBox in particular) find a
 * file by listing its whole folder, which would otherwise get slower with every block.
 *
 * The clients work with files, so every block is staged through a temporary file in {@code tmpDir}
 * (one block's worth of disk per request in flight, deleted straight afterwards).
 */
public final class DriveBackend implements CloudBackend {
    private static final Pattern BLOCK = Pattern.compile("b-([0-9a-zA-Z]+)-(\\d+)");
    private static final int PER_FOLDER = 256;
    private static final String MIME = "application/octet-stream";

    private final CloudClient client;
    private final String root;
    private final File tmpDir;
    private final Set<String> knownDirs = ConcurrentHashMap.newKeySet();
    private final AtomicLong counter = new AtomicLong();

    public DriveBackend(CloudClient client, String rootFolder, File tmpDir) throws IOException {
        this.client = client;
        this.root = Paths.normalize(rootFolder);
        this.tmpDir = tmpDir;
        if (!tmpDir.isDirectory() && !tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        File[] old = tmpDir.listFiles();                         // leftovers from a crash
        if (old != null) for (File f : old) f.delete();
    }

    /** Where a key lives in the drive. Public so the layout can be checked in tests and shown in the UI. */
    public String pathFor(String key) {
        Matcher m = BLOCK.matcher(key);
        if (m.matches()) {
            long idx = Long.parseLong(m.group(2));
            return root + "/" + m.group(1) + "/" + (idx / PER_FOLDER) + "/" + idx;
        }
        return root + "/" + key;
    }

    @Override public byte[] get(String key) throws IOException {
        String path = pathFor(key);
        File t = temp();
        try {
            client.download(path, t);
            return Files.readAllBytes(t.toPath());
        } catch (CloudException e) {
            if (e.isNotFound()) return null;
            throw e;
        } finally {
            cleanup(t);
        }
    }

    @Override public void put(String key, byte[] data) throws IOException {
        String path = pathFor(key);
        File t = temp();
        try (FileOutputStream out = new FileOutputStream(t)) {
            out.write(data);
        } catch (IOException e) {
            cleanup(t);
            throw e;
        }
        try {
            try {
                ensureDir(Paths.parent(path));
                client.upload(path, t, MIME);
            } catch (CloudException e) {
                if (!e.isNotFound()) throw e;
                knownDirs.clear();                                // someone removed a folder behind our back: rebuild once
                ensureDir(Paths.parent(path));
                client.upload(path, t, MIME);
            }
        } finally {
            cleanup(t);
        }
    }

    @Override public void delete(String key) throws IOException {
        try {
            client.delete(pathFor(key), false);
        } catch (CloudException e) {
            if (!e.isNotFound()) throw e;
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Create a folder (and its parents) if it is missing. Serialized, because Drive happily allows two folders of one name. */
    private void ensureDir(String dir) throws IOException {
        if (dir.equals("/") || knownDirs.contains(dir)) return;
        synchronized (this) {
            if (knownDirs.contains(dir)) return;
            ensureDir(Paths.parent(dir));
            boolean exists = false;
            try {
                CloudEntry e = client.stat(dir);
                if (!e.dir) throw new CloudException(409, "\"" + dir + "\" exists but is not a folder");
                exists = true;
            } catch (CloudException e) {
                if (!e.isNotFound()) throw e;
            }
            if (!exists) {
                try {
                    client.mkdir(dir);
                } catch (CloudException e) {
                    // 405 / 409 / 412 = "already exists" on the different services: fine, unless it is not really there.
                    if (e.status != 405 && e.status != 409 && e.status != 412) throw e;
                    if (!client.stat(dir).dir) throw e;
                }
            }
            knownDirs.add(dir);
        }
    }

    private File temp() {
        return new File(tmpDir, "blk-" + counter.incrementAndGet() + ".tmp");
    }

    private static void cleanup(File f) {
        f.delete();
        new File(f.getPath() + ".part").delete();                 // MEGA stages downloads in "<name>.part"
    }
}
