package com.clouddroid.app.core;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

/** Plain-Java test runner (no frameworks): javac the core + tests, then run TestMain. Exit code 1 on any failure. */
public final class TestMain {
    private static int passed;
    private static int failed;

    static void check(String name, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + name);
        }
    }

    static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) System.out.println("   expected: " + expected + "\n   actual:   " + actual);
        check(name, ok);
    }

    static void run(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL (exception): " + name + " -> " + t);
            t.printStackTrace(System.out);
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static CloudException expectFail(String name, ThrowingRunnable r) {
        try {
            r.run();
        } catch (CloudException e) {
            passed++;
            return e;
        } catch (Exception e) {
            failed++;
            System.out.println("FAIL: " + name + " threw " + e + " instead of CloudException");
            return null;
        }
        failed++;
        System.out.println("FAIL: " + name + " did not throw");
        return null;
    }

    static File tmpDir() throws IOException {
        return Files.createTempDirectory("cbtest").toFile();
    }

    static File randomFile(int size, long seed) throws IOException {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        File f = File.createTempFile("cbt", ".bin");
        f.deleteOnExit();
        Files.write(f.toPath(), b);
        return f;
    }

    static List<String> names(List<CloudEntry> l) {
        List<String> n = new ArrayList<>();
        for (CloudEntry e : l) n.add(e.path + (e.dir ? "/" : ""));
        Collections.sort(n);
        return n;
    }

    public static void main(String[] args) {
        TestMega.runAll();
        TestTeraBox.runAll();
        System.out.println(passed + " checks passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
