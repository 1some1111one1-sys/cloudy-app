package top.niunaijun.blackboxa.runner;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.BEnvironment;

/**
 * The parts of "Copy debug log" that logcat alone cannot show: how each app process really ended
 * (Android keeps a record), the crash reports the container saved to disk, and what the container's
 * apps look like inside (native libraries, game data). Every method returns text and never throws.
 */
final class DebugReport {
    private DebugReport() {
    }

    /** The text of the exit section plus the process ids of the newest container processes. */
    static final class Exits {
        final String text;
        final List<Integer> guestPids;

        Exits(String text, List<Integer> guestPids) {
            this.text = text;
            this.guestPids = guestPids;
        }
    }

    // ---------------------------------------------------------------- How processes ended

    static Exits recentExits(Context context) {
        List<Integer> guestPids = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) {
            return new Exits("How processes ended: this Android version does not record it.\n", guestPids);
        }
        StringBuilder out = new StringBuilder("How app processes ended (newest first, as recorded by Android):\n");
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 20);
            if (list == null || list.isEmpty()) {
                out.append("  nothing recorded\n");
                return new Exits(out.toString(), guestPids);
            }
            SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            long now = System.currentTimeMillis();
            int details = 0;
            for (ApplicationExitInfo info : list) {
                String process = String.valueOf(info.getProcessName());
                String shortName = process.startsWith(context.getPackageName())
                        ? process.substring(context.getPackageName().length()) : process;
                if (shortName.isEmpty()) {
                    shortName = "(main)";
                }
                boolean guest = shortName.matches(":p\\d+");
                if (guest && guestPids.size() < 2) {
                    guestPids.add(info.getPid());
                }
                int reason = info.getReason();
                out.append("  ").append(time.format(new Date(info.getTimestamp())))
                        .append("  ").append(shortName).append(" pid ").append(info.getPid())
                        .append("  ").append(reasonName(reason))
                        .append(" status=").append(info.getStatus());
                if (reason == 2 || reason == 5) {
                    out.append(" (").append(signalName(info.getStatus())).append(')');
                }
                out.append("  was ").append(importanceName(info.getImportance()))
                        .append(", ").append(info.getRss() / 1024).append(" MB");
                String description = info.getDescription();
                if (description != null && !description.isEmpty()) {
                    out.append("  \"").append(clip(description, 160)).append('"');
                }
                out.append('\n');

                // The newest couple of native crashes and hangs come with a report.
                if ((reason == 5 || reason == 6) && details < 2) {
                    String trace = readTrace(info, reason);
                    if (trace != null) {
                        details++;
                        out.append("    ").append(reason == 5 ? "native crash report" : "hang report")
                                .append(":\n");
                        for (String line : trace.split("\n")) {
                            out.append("      ").append(line).append('\n');
                        }
                    }
                }
                if (out.length() > 14000) {
                    out.append("  ...\n");
                    break;
                }
            }
        } catch (Throwable t) {
            out.append("  could not read: ").append(t).append('\n');
        }
        return new Exits(out.toString(), guestPids);
    }

    private static String readTrace(ApplicationExitInfo info, int reason) {
        try (InputStream in = info.getTraceInputStream()) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0 && bytes.size() < 1_500_000) {
                bytes.write(buffer, 0, n);
            }
            byte[] data = bytes.toByteArray();
            if (reason == 5) {
                return Tombstone.summarize(data, 18, 25);
            }
            // A hang report is plain text; the first lines name the main thread's stack.
            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length && i < 45; i++) {
                head.append(clip(lines[i], 200)).append('\n');
            }
            return head.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case 1:
                return "EXIT_SELF (the app quit by itself)";
            case 2:
                return "SIGNALED (killed by a signal)";
            case 3:
                return "LOW_MEMORY (Android needed the memory)";
            case 4:
                return "CRASH (Java error)";
            case 5:
                return "CRASH_NATIVE (native crash)";
            case 6:
                return "ANR (not responding)";
            case 7:
                return "INITIALIZATION_FAILURE";
            case 8:
                return "PERMISSION_CHANGE";
            case 9:
                return "EXCESSIVE_RESOURCE_USAGE";
            case 10:
                return "USER_REQUESTED";
            case 11:
                return "USER_STOPPED";
            case 12:
                return "DEPENDENCY_DIED";
            case 13:
                return "OTHER";
            case 14:
                return "FREEZER";
            case 15:
                return "PACKAGE_STATE_CHANGE";
            case 16:
                return "PACKAGE_UPDATED";
            default:
                return "UNKNOWN(" + reason + ")";
        }
    }

    private static String signalName(int status) {
        switch (status) {
            case 4:
                return "SIGILL";
            case 6:
                return "SIGABRT";
            case 7:
                return "SIGBUS";
            case 8:
                return "SIGFPE";
            case 9:
                return "SIGKILL";
            case 11:
                return "SIGSEGV";
            case 13:
                return "SIGPIPE";
            case 15:
                return "SIGTERM";
            case 31:
                return "SIGSYS";
            default:
                return "signal " + status;
        }
    }

    private static String importanceName(int importance) {
        if (importance <= 100) {
            return "in the foreground";
        } else if (importance <= 125) {
            return "foreground service";
        } else if (importance <= 230) {
            return "visible";
        } else if (importance <= 300) {
            return "background service";
        } else if (importance < 1000) {
            return "cached";
        }
        return "gone";
    }

    // ---------------------------------------------------------------- Saved crash reports

    /**
     * The container writes a report to files/crash_logs every time an app inside it dies from a
     * Java error. The reports are older than this log, so they can name a crash that has already
     * scrolled out of logcat.
     */
    static String crashReports(Context context) {
        try {
            File dir = new File(context.getFilesDir(), "crash_logs");
            File[] files = dir.listFiles((d, name) -> name.startsWith("crash_") && name.endsWith(".log"));
            if (files == null || files.length == 0) {
                return "Saved crash reports: none\n";
            }
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            StringBuilder out = new StringBuilder("Saved crash reports (newest first, ")
                    .append(files.length).append(" on this phone):\n");
            Set<String> seen = new HashSet<>();
            int repeats = 0;
            int shown = 0;
            long now = System.currentTimeMillis();
            for (File file : files) {
                if (shown >= 7 || out.length() > 22000) {
                    break;
                }
                List<String> lines = readLines(file, 700);
                String pkg = valueOf(lines, "Package:");
                String error = valueOf(lines, "Error:");
                int trace = lines.indexOf("=== STACK TRACE ===");
                String first = trace >= 0 && trace + 1 < lines.size() ? lines.get(trace + 1) : "";
                String key = pkg + "|" + clip(error, 100) + "|" + clip(first, 100);
                if (!seen.add(key)) {
                    repeats++;
                    continue;
                }
                shown++;
                long ageMinutes = Math.max(0, (now - file.lastModified()) / 60000);
                out.append("  ").append(file.getName()).append("  (").append(ageMinutes)
                        .append(" min ago)  app: ").append(pkg).append('\n');
                if (trace >= 0) {
                    appendTrace(out, lines, trace + 1);
                } else {
                    for (int i = 0; i < lines.size() && i < 25; i++) {
                        out.append("    ").append(clip(lines.get(i), 220)).append('\n');
                    }
                }
            }
            if (repeats > 0) {
                out.append("  (").append(repeats).append(" more reports with the same error hidden)\n");
            }
            return out.toString();
        } catch (Throwable t) {
            return "Saved crash reports: could not read (" + t + ")\n";
        }
    }

    /** The top of the trace, plus every "Caused by" with a few lines after it. */
    private static void appendTrace(StringBuilder out, List<String> lines, int from) {
        int printed = 0;
        int sinceCause = 100;
        int causes = 0;
        int skipped = 0;
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals("=== END ===")) {
                break;
            }
            boolean cause = line.startsWith("Caused by:");
            if (cause) {
                sinceCause = 0;
                causes++;
            }
            boolean wanted = printed < 22 || (sinceCause < 7 && causes <= 4);
            sinceCause++;
            if (wanted) {
                if (skipped > 0) {
                    out.append("    ... ").append(skipped).append(" lines\n");
                    skipped = 0;
                }
                out.append("    ").append(clip(line, 240)).append('\n');
                printed++;
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            out.append("    ... ").append(skipped).append(" lines\n");
        }
    }

    private static String valueOf(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "?";
    }

    private static List<String> readLines(File file, int max) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < max) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ---------------------------------------------------------------- Apps in the container

    static String guestApps(Context context) {
        StringBuilder out = new StringBuilder("Apps in the container:\n");
        boolean any = false;
        try {
            PackageManager phone = context.getPackageManager();
            for (int userId = 0; userId < 16; userId++) {
                List<PackageInfo> list;
                try {
                    list = BlackBoxCore.get().getInstalledPackages(0, userId);
                } catch (Throwable t) {
                    continue;
                }
                if (list == null) {
                    continue;
                }
                for (PackageInfo info : list) {
                    any = true;
                    try {
                        describeApp(out, phone, info, userId);
                    } catch (Throwable t) {
                        out.append("  ").append(info.packageName).append(": could not inspect (")
                                .append(t).append(")\n");
                    }
                    if (out.length() > 12000) {
                        out.append("  ...\n");
                        return out.toString();
                    }
                }
            }
        } catch (Throwable t) {
            out.append("  could not list: ").append(t).append('\n');
        }
        if (!any) {
            out.append("  none\n");
        }
        return out.toString();
    }

    private static void describeApp(StringBuilder out, PackageManager phone, PackageInfo info, int userId) {
        String pkg = info.packageName;
        ApplicationInfo ai = info.applicationInfo;
        out.append("  ").append(pkg).append("  (container space ").append(userId).append(")  version ")
                .append(info.versionName).append(" / ").append(versionCode(info)).append('\n');
        if (ai != null) {
            out.append("    apk: ").append(ai.sourceDir).append('\n');
            out.append("    libraries inside the apk: ").append(abis(ai.sourceDir)).append('\n');
            out.append("    library folder: ").append(ai.nativeLibraryDir).append("  ->  ")
                    .append(folder(ai.nativeLibraryDir)).append('\n');
            int splits = ai.splitSourceDirs == null ? 0 : ai.splitSourceDirs.length;
            out.append("    split apks: ").append(splits).append("   target sdk: ")
                    .append(ai.targetSdkVersion).append('\n');
        }
        try {
            PackageInfo real = phone.getPackageInfo(pkg, 0);
            int realSplits = real.applicationInfo != null && real.applicationInfo.splitSourceDirs != null
                    ? real.applicationInfo.splitSourceDirs.length : 0;
            out.append("    also installed on the phone: yes, version ").append(real.versionName)
                    .append(", ").append(realSplits).append(" split apks\n");
        } catch (Throwable t) {
            out.append("    also installed on the phone: no (or hidden from this app)\n");
        }
        out.append("    game data in the container (Android/obb): ")
                .append(tree(BEnvironment.getExternalObbDir(pkg, userId))).append('\n');
        out.append("    app files in the container (Android/data): ")
                .append(tree(BEnvironment.getExternalDataDir(pkg, userId))).append('\n');
        try {
            File phoneObb = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + pkg);
            out.append("    game data on the phone that this app can see: ")
                    .append(phoneObb.exists() ? tree(phoneObb) : "none visible").append('\n');
        } catch (Throwable ignored) {
            // not worth a line
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** Which CPU types the apk carries native libraries for, e.g. "arm64-v8a: 14". */
    private static String abis(String apk) {
        if (apk == null) {
            return "unknown";
        }
        try (ZipFile zip = new ZipFile(apk)) {
            TreeMap<String, Integer> counts = new TreeMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        Integer old = counts.get(parts[1]);
                        counts.put(parts[1], old == null ? 1 : old + 1);
                    }
                }
            }
            if (counts.isEmpty()) {
                return "none (a plain Java app, or the libraries are in split apks)";
            }
            StringBuilder s = new StringBuilder();
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                if (s.length() > 0) {
                    s.append(", ");
                }
                s.append(e.getKey()).append(": ").append(e.getValue());
            }
            return s.toString();
        } catch (Throwable t) {
            return "could not read (" + t.getMessage() + ")";
        }
    }

    private static String folder(String path) {
        if (path == null) {
            return "unknown";
        }
        File dir = new File(path);
        String[] names = dir.list();
        if (names == null) {
            return dir.exists() ? "not readable" : "missing";
        }
        if (names.length == 0) {
            return "empty";
        }
        Arrays.sort(names);
        StringBuilder s = new StringBuilder().append(names.length).append(" files: ");
        for (int i = 0; i < names.length && i < 4; i++) {
            s.append(i > 0 ? ", " : "").append(names[i]);
        }
        if (names.length > 4) {
            s.append(", ...");
        }
        return s.toString();
    }

    /** "missing", "empty" or "N files, X MB" for a folder, counted to a fixed depth and size. */
    private static String tree(File root) {
        if (root == null || !root.exists()) {
            return "missing";
        }
        long[] totals = new long[2];
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        int visited = 0;
        while (!pending.isEmpty() && visited < 4000) {
            File dir = pending.poll();
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                visited++;
                if (child.isDirectory()) {
                    pending.add(child);
                } else {
                    totals[0]++;
                    totals[1] += child.length();
                }
            }
        }
        if (totals[0] == 0) {
            return "empty";
        }
        return totals[0] + " files, " + (totals[1] / (1024 * 1024)) + " MB"
                + (visited >= 4000 ? " (counted only part)" : "");
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
