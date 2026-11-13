package top.niunaijun.blackboxa.runner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pulls the useful facts out of an Android native crash report (a "tombstone"), which Android 12 and
 * newer hands out in protobuf form, without any library: what signal killed the process, the crashing
 * thread's call stack and the last log lines Android saw. The field numbers are the ones of AOSP's
 * debuggerd/proto/tombstone.proto.
 *
 * Everything here is best effort and never throws. When the data is not laid out as expected the
 * readable strings in it are returned instead, which still names the libraries and functions involved.
 */
final class Tombstone {
    private Tombstone() {
    }

    static String summarize(byte[] data, int maxFrames, int maxLogLines) {
        if (data == null || data.length == 0) {
            return "(empty report)";
        }
        if (looksLikeText(data)) {
            return head(new String(data, StandardCharsets.UTF_8), 70);
        }
        try {
            String decoded = decode(data, maxFrames, maxLogLines);
            if (decoded != null && !decoded.isEmpty()) {
                return decoded;
            }
        } catch (Throwable ignored) {
            // fall through to the plain strings
        }
        return "could not decode " + data.length + " bytes, readable strings in it:\n" + strings(data, 60);
    }

    // ---------------------------------------------------------------- Decoding

    private static String decode(byte[] bytes, int maxFrames, int maxLogLines) {
        Reader top = new Reader(bytes, 0, bytes.length);
        long pid = 0;
        long tid = 0;
        long uid = 0;
        long uptime = -1;
        String abortMessage = null;
        String timestamp = null;
        String signal = null;
        List<String> commandLine = new ArrayList<>();
        List<String> causes = new ArrayList<>();
        List<CrashThread> threads = new ArrayList<>();
        List<String> logLines = new ArrayList<>();

        while (top.more()) {
            int tag = top.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 4 && wire == 2) {
                timestamp = top.string();
            } else if (field == 5 && wire == 0) {
                pid = top.varint();
            } else if (field == 6 && wire == 0) {
                tid = top.varint();
            } else if (field == 7 && wire == 0) {
                uid = top.varint();
            } else if (field == 9 && wire == 2) {
                commandLine.add(top.string());
            } else if (field == 10 && wire == 2) {
                signal = readSignal(top.sub());
            } else if (field == 14 && wire == 2) {
                abortMessage = top.string();
            } else if (field == 15 && wire == 2) {
                String cause = readCause(top.sub());
                if (cause != null) {
                    causes.add(cause);
                }
            } else if (field == 16 && wire == 2) {
                CrashThread thread = readThreadEntry(top.sub());
                if (thread != null) {
                    threads.add(thread);
                }
            } else if (field == 18 && wire == 2) {
                readLogBuffer(top.sub(), logLines);
            } else if (field == 20 && wire == 0) {
                uptime = top.varint();
            } else {
                top.skip(wire);
            }
        }

        StringBuilder out = new StringBuilder();
        if (signal != null) {
            out.append(signal).append('\n');
        }
        out.append("process ").append(commandLine.isEmpty() ? "?" : join(commandLine))
                .append(" (pid ").append(pid).append(", tid ").append(tid)
                .append(", uid ").append(uid).append(")");
        if (uptime >= 0) {
            out.append(", running ").append(uptime).append(" s");
        }
        if (timestamp != null) {
            out.append(", at ").append(timestamp);
        }
        out.append('\n');
        if (abortMessage != null && !abortMessage.isEmpty()) {
            out.append("abort message: ").append(clip(abortMessage, 400)).append('\n');
        }
        for (String cause : causes) {
            out.append("cause: ").append(clip(cause, 300)).append('\n');
        }

        CrashThread crashing = null;
        for (CrashThread thread : threads) {
            if (thread.id == tid) {
                crashing = thread;
                break;
            }
        }
        if (crashing == null && !threads.isEmpty()) {
            crashing = threads.get(0);
        }
        if (crashing != null) {
            out.append("crashing thread ").append(crashing.id);
            if (crashing.name != null && !crashing.name.isEmpty()) {
                out.append(" \"").append(crashing.name).append('"');
            }
            out.append(", call stack:\n");
            int shown = 0;
            for (String frame : crashing.frames) {
                if (shown++ >= maxFrames) {
                    out.append("    ... ").append(crashing.frames.size() - maxFrames).append(" more\n");
                    break;
                }
                out.append(String.format(Locale.US, "    #%02d %s%n", shown - 1, frame));
            }
            if (crashing.frames.isEmpty()) {
                out.append("    (no frames recorded)\n");
            }
            for (String note : crashing.notes) {
                out.append("    note: ").append(clip(note, 200)).append('\n');
            }
        }
        if (threads.size() > 1) {
            out.append("other threads: ");
            int count = 0;
            for (CrashThread thread : threads) {
                if (thread == crashing) {
                    continue;
                }
                if (count++ >= 12) {
                    out.append("...");
                    break;
                }
                out.append(thread.name == null ? String.valueOf(thread.id) : thread.name).append("  ");
            }
            out.append('\n');
        }
        if (!logLines.isEmpty()) {
            int from = Math.max(0, logLines.size() - maxLogLines);
            out.append("log lines Android saved with the crash (last ").append(logLines.size() - from)
                    .append("):\n");
            for (int i = from; i < logLines.size(); i++) {
                out.append("    ").append(clip(logLines.get(i), 220)).append('\n');
            }
        }
        return out.toString();
    }

    private static String readSignal(Reader r) {
        long number = 0;
        long code = 0;
        long sender = 0;
        long fault = 0;
        String name = null;
        String codeName = null;
        boolean hasFault = false;
        boolean hasSender = false;
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                number = r.varint();
            } else if (field == 2 && wire == 2) {
                name = r.string();
            } else if (field == 3 && wire == 0) {
                code = r.varint();
            } else if (field == 4 && wire == 2) {
                codeName = r.string();
            } else if (field == 5 && wire == 0) {
                hasSender = r.varint() != 0;
            } else if (field == 7 && wire == 0) {
                sender = r.varint();
            } else if (field == 8 && wire == 0) {
                hasFault = r.varint() != 0;
            } else if (field == 9 && wire == 0) {
                fault = r.varint();
            } else {
                r.skip(wire);
            }
        }
        StringBuilder s = new StringBuilder("signal ").append(number);
        if (name != null) {
            s.append(" (").append(name).append(')');
        }
        s.append(", code ").append(code);
        if (codeName != null) {
            s.append(" (").append(codeName).append(')');
        }
        if (hasFault) {
            s.append(", fault address 0x").append(Long.toHexString(fault));
        }
        if (hasSender && sender != 0) {
            s.append(", sent by pid ").append(sender);
        }
        return s.toString();
    }

    private static String readCause(Reader r) {
        String text = null;
        while (r.more()) {
            int tag = r.tag();
            if ((tag >>> 3) == 1 && (tag & 7) == 2) {
                text = r.string();
            } else {
                r.skip(tag & 7);
            }
        }
        return text;
    }

    /** One entry of the "threads" map: key 1 is the thread id, value 2 the thread itself. */
    private static CrashThread readThreadEntry(Reader r) {
        long key = 0;
        CrashThread thread = null;
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                key = r.varint();
            } else if (field == 2 && wire == 2) {
                thread = readThread(r.sub());
            } else {
                r.skip(wire);
            }
        }
        if (thread != null && thread.id == 0) {
            thread.id = key;
        }
        return thread;
    }

    private static CrashThread readThread(Reader r) {
        CrashThread thread = new CrashThread();
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                thread.id = r.varint();
            } else if (field == 2 && wire == 2) {
                thread.name = r.string();
            } else if (field == 4 && wire == 2) {
                thread.frames.add(readFrame(r.sub()));
            } else if (field == 7 && wire == 2) {
                thread.notes.add(r.string());
            } else {
                r.skip(wire);
            }
        }
        return thread;
    }

    private static String readFrame(Reader r) {
        long relPc = 0;
        long offset = 0;
        String function = null;
        String file = null;
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 0) {
                relPc = r.varint();
            } else if (field == 4 && wire == 2) {
                function = r.string();
            } else if (field == 5 && wire == 0) {
                offset = r.varint();
            } else if (field == 6 && wire == 2) {
                file = r.string();
            } else {
                r.skip(wire);
            }
        }
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "pc %016x  ", relPc));
        s.append(file == null || file.isEmpty() ? "?" : file);
        if (function != null && !function.isEmpty()) {
            s.append(" (").append(function);
            if (offset != 0) {
                s.append('+').append(offset);
            }
            s.append(')');
        }
        return s.toString();
    }

    private static void readLogBuffer(Reader r, List<String> into) {
        String bufferName = null;
        List<String> lines = new ArrayList<>();
        while (r.more()) {
            int tag = r.tag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) {
                bufferName = r.string();
            } else if (field == 2 && wire == 2) {
                lines.add(readLogMessage(r.sub()));
            } else {
                r.skip(wire);
            }
        }
        // The crash buffer repeats what the crash handler already printed, the others hold the context.
        if (bufferName != null && !bufferName.equals("crash")) {
            into.addAll(lines);
        }
    }

    private static String readLogMessage(Reader r) {
        String time = "";
        String tag = "";
        String message = "";
        long pid = 0;
        long priority = 0;
        while (r.more()) {
            int t = r.tag();
            int field = t >>> 3;
            int wire = t & 7;
            if (field == 1 && wire == 2) {
                time = r.string();
            } else if (field == 2 && wire == 0) {
                pid = r.varint();
            } else if (field == 4 && wire == 0) {
                priority = r.varint();
            } else if (field == 5 && wire == 2) {
                tag = r.string();
            } else if (field == 6 && wire == 2) {
                message = r.string();
            } else {
                r.skip(wire);
            }
        }
        String letters = "??VDIWEF";
        char level = priority >= 0 && priority < letters.length() ? letters.charAt((int) priority) : '?';
        return time + " " + pid + " " + level + " " + tag + ": " + message.replace('\n', ' ');
    }

    private static final class CrashThread {
        long id;
        String name;
        final List<String> frames = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
    }

    // ---------------------------------------------------------------- Small helpers

    private static boolean looksLikeText(byte[] data) {
        int n = Math.min(data.length, 256);
        int printable = 0;
        for (int i = 0; i < n; i++) {
            int c = data[i] & 0xFF;
            if ((c >= 0x20 && c < 0x7F) || c == '\n' || c == '\t' || c == '\r') {
                printable++;
            }
        }
        return printable * 100 >= n * 95;
    }

    private static String head(String text, int lines) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String line : text.split("\n")) {
            if (count++ >= lines) {
                out.append("...\n");
                break;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String strings(byte[] data, int max) {
        StringBuilder out = new StringBuilder();
        StringBuilder run = new StringBuilder();
        int found = 0;
        for (int i = 0; i <= data.length && found < max; i++) {
            int c = i < data.length ? data[i] & 0xFF : 0;
            if (c >= 0x20 && c < 0x7F) {
                run.append((char) c);
            } else {
                if (run.length() >= 6) {
                    out.append("    ").append(clip(run.toString(), 200)).append('\n');
                    found++;
                }
                run.setLength(0);
            }
        }
        return out.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder s = new StringBuilder();
        for (String part : parts) {
            if (s.length() > 0) {
                s.append(' ');
            }
            s.append(part);
        }
        return s.toString();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** A minimal protobuf reader over a byte range. Throws on malformed data, the caller catches. */
    private static final class Reader {
        private final byte[] bytes;
        private int pos;
        private final int end;

        Reader(byte[] bytes, int pos, int end) {
            this.bytes = bytes;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        int tag() {
            return (int) varint();
        }

        long varint() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= end || shift > 63) {
                    throw new IllegalStateException("bad varint");
                }
                int b = bytes[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
        }

        /** The next length-delimited field as a reader over just its bytes. */
        Reader sub() {
            int length = (int) varint();
            if (length < 0 || pos + length > end) {
                throw new IllegalStateException("bad length");
            }
            Reader inner = new Reader(bytes, pos, pos + length);
            pos += length;
            return inner;
        }

        String string() {
            Reader inner = sub();
            return new String(bytes, inner.pos, inner.end - inner.pos, StandardCharsets.UTF_8);
        }

        void skip(int wire) {
            switch (wire) {
                case 0:
                    varint();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2:
                    sub();
                    break;
                case 5:
                    pos += 4;
                    break;
                default:
                    throw new IllegalStateException("bad wire type " + wire);
            }
            if (pos > end) {
                throw new IllegalStateException("past the end");
            }
        }
    }
}
