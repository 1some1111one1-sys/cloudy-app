package com.clouddroid.app.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Packs a folder's files into one zip stream and unpacks one back, refusing names that could escape the target folder. */
public final class ZipPack {
    private ZipPack() {
    }

    /** Most files accepted from one archive (guards against an archive built to exhaust the phone). */
    public static final int MAX_ENTRIES = 200_000;

    /** A relative path with no "..", no leading slash and forward slashes only, or null if the name is not safe. */
    public static String safeName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        StringBuilder out = new StringBuilder();
        for (String part : n.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") || part.indexOf('\0') >= 0) return null;
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        return out.length() == 0 ? null : out.toString();
    }

    public static final class Writer implements Closeable {
        private final ZipOutputStream zip;
        private final byte[] buf = new byte[256 * 1024];
        private int count;

        public Writer(OutputStream out) {
            this.zip = new ZipOutputStream(out);
            this.zip.setLevel(1);          // quick: game saves are mostly already compressed
        }

        public void add(String relPath, InputStream in) throws IOException {
            String name = safeName(relPath);
            if (name == null) throw new IOException("Unsafe file name: " + relPath);
            zip.putNextEntry(new ZipEntry(name));
            int n;
            while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
            zip.closeEntry();
            count++;
        }

        public int count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    public interface Opener {
        /** Creates (or replaces) the file at a relative path, making its folders, and returns a stream to write it. */
        OutputStream open(String relPath) throws IOException;
    }

    /** Unpacks every file through the opener. Returns how many files were written. */
    public static int unpack(InputStream in, Opener opener) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        byte[] buf = new byte[256 * 1024];
        int files = 0;
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            if (e.isDirectory()) continue;
            String name = safeName(e.getName());
            if (name == null) throw new IOException("The backup contains an unsafe file name (\"" + e.getName() + "\"), so it was not restored");
            if (++files > MAX_ENTRIES) throw new IOException("The backup has too many files");
            try (OutputStream out = opener.open(name)) {
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return files;
    }
}
