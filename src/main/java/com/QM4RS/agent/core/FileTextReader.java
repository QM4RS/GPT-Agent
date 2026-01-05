package com.QM4RS.agent.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileTextReader {

    private final long maxBytesPerFile;

    public FileTextReader(long maxBytesPerFile) {
        this.maxBytesPerFile = Math.max(4 * 1024, maxBytesPerFile);
    }

    public String readUtf8(Path file) throws IOException {
        long size = Files.size(file);

        // Read a small probe first to detect binary safely
        byte[] probe = readHeadBytes(file, (int) Math.min(4096, size));
        if (looksBinary(probe)) {
            return "[[BINARY FILE SKIPPED: " + safeName(file) + " (" + size + " bytes)]]\n";
        }

        boolean truncated = size > maxBytesPerFile;
        int toRead = (int) Math.min(size, maxBytesPerFile);

        byte[] bytes = readHeadBytes(file, toRead);
        if (size > 0 && bytes.length == 0) {
            return "[[READ_WARNING: file had size=" + size +
                    " but 0 bytes were read. Possibly locked or mid-write: "
                    + safeName(file) + "]]\n";
        }

        String content = new String(bytes, StandardCharsets.UTF_8);

        if (truncated) {
            content = content
                    + "\n[[TRUNCATED: file size=" + size + " bytes, read=" + toRead + " bytes]]\n";
        }

        return content;
    }

    private static byte[] readHeadBytes(Path file, int maxBytes) throws IOException {
        if (maxBytes <= 0) return new byte[0];
        byte[] out = new byte[maxBytes];
        int off = 0;

        try (InputStream in = Files.newInputStream(file)) {
            while (off < maxBytes) {
                int r = in.read(out, off, maxBytes - off);
                if (r < 0) break;
                off += r;
            }
        }

        if (off == out.length) return out;

        byte[] trimmed = new byte[off];
        System.arraycopy(out, 0, trimmed, 0, off);
        return trimmed;
    }

    private static boolean looksBinary(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;

        int nul = 0;
        int weird = 0;

        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c == 0) nul++;

            // allow: tab, lf, cr, and common printable range
            boolean ok =
                    c == 9 || c == 10 || c == 13 ||
                            (c >= 32 && c <= 126) ||
                            (c >= 0xC2 && c <= 0xF4); // rough UTF-8 leading bytes range

            if (!ok) weird++;
        }

        if (nul > 0) return true;

        // if a notable portion is weird, treat as binary
        double ratio = (double) weird / (double) bytes.length;
        return ratio > 0.20;
    }

    private static String safeName(Path p) {
        if (p == null) return "";
        Path n = p.getFileName();
        return n == null ? p.toString() : n.toString();
    }
}
