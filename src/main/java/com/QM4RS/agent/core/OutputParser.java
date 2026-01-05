package com.QM4RS.agent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OutputParser {

    public List<OutputBlock> parse(String text) {
        List<OutputBlock> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) return blocks;

        String normalized = text.replace("\r\n", "\n");

        // 1) Try legacy format first (CHANGE:/code:)
        List<OutputBlock> legacy = parseLegacy(normalized);
        if (!legacy.isEmpty()) return legacy;

        // 2) Try snippet format (PromptTemplates headers in comments)
        List<OutputBlock> snippets = parseSnippets(normalized);
        if (!snippets.isEmpty()) return snippets;

        return blocks;
    }

    // ---------------- Legacy format: CHANGE: ... code: ----------------

    private List<OutputBlock> parseLegacy(String text) {
        List<OutputBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        OutputBlock cur = null;
        boolean inCode = false;
        StringBuilder code = new StringBuilder();

        for (String line : lines) {
            if (line.equals("CHANGE:")) {
                if (cur != null) {
                    cur.code = code.toString().stripTrailing();
                    blocks.add(cur);
                }
                cur = new OutputBlock();
                inCode = false;
                code.setLength(0);
                continue;
            }

            if (cur == null) continue;

            if (line.startsWith("code:")) {
                inCode = true;
                continue;
            }

            if (inCode) {
                code.append(line).append("\n");
                continue;
            }

            if (line.startsWith("file:")) cur.file = line.substring("file:".length()).trim();
            else if (line.startsWith("action:")) cur.action = line.substring("action:".length()).trim();
            else if (line.startsWith("range:")) cur.range = line.substring("range:".length()).trim();
            else if (line.startsWith("target:")) cur.target = line.substring("target:".length()).trim();
            else if (line.startsWith("anchor:")) cur.anchor = line.substring("anchor:".length()).trim();
            else if (line.startsWith("note:")) cur.note = line.substring("note:".length()).trim();
        }

        if (cur != null) {
            cur.code = code.toString().stripTrailing();
            blocks.add(cur);
        }

        // If we saw no CHANGE:, treat as not legacy
        boolean hasAnyNonRaw = blocks.stream().anyMatch(b -> b != null && b.action != null);
        return hasAnyNonRaw ? blocks : new ArrayList<>();
    }

    // ---------------- Snippet format: comment headers + code ----------------
    //
    // Rules we implement:
    // - Snippets are separated by ONE blank line (we accept 1+ blank lines to be tolerant)
    // - Header lines can be in //, #, or <!-- --> comments
    // - Header keys: FILE:, OPERATION:, ANCHOR:, LANGUAGE:
    // - After headers, remaining lines are code to paste
    //
    // We store:
    // - block.file  <- FILE
    // - block.action <- OPERATION (mapped to your action field)
    // - block.anchor <- ANCHOR
    // - block.code <- full snippet text (headers + code) to keep display-friendly
    //
    private List<OutputBlock> parseSnippets(String text) {
        List<String> rawSnippets = splitIntoSnippets(text);
        List<OutputBlock> out = new ArrayList<>();

        for (String snippet : rawSnippets) {
            OutputBlock b = parseOneSnippet(snippet);
            if (b != null) out.add(b);
        }

        // if nothing parseable, return empty (caller will fallback to RAW)
        return out;
    }

    private List<String> splitIntoSnippets(String text) {
        List<String> snippets = new ArrayList<>();
        String[] lines = text.split("\n", -1);

        StringBuilder cur = new StringBuilder();
        int blankRun = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            boolean blank = line.trim().isEmpty();
            if (blank) {
                blankRun++;
            } else {
                blankRun = 0;
            }

            // We consider a snippet boundary if we had at least 1 blank line AND
            // next non-blank appears to start a header (FILE/OPERATION/LANGUAGE/ANCHOR in comment)
            // This prevents splitting inside code where blank lines are common.
            if (blank && cur.length() > 0) {
                // peek ahead to next non-blank
                int j = i + 1;
                while (j < lines.length && lines[j].trim().isEmpty()) j++;
                if (j < lines.length) {
                    String next = lines[j];
                    if (looksLikeHeaderLine(next)) {
                        // flush current snippet
                        snippets.add(cur.toString().stripTrailing());
                        cur.setLength(0);
                        continue;
                    }
                }
            }

            cur.append(line);
            if (i < lines.length - 1) cur.append("\n");
        }

        String last = cur.toString().strip();
        if (!last.isEmpty()) snippets.add(last);

        return snippets;
    }

    private OutputBlock parseOneSnippet(String snippet) {
        if (snippet == null || snippet.isBlank()) return null;

        String[] lines = snippet.replace("\r\n", "\n").split("\n", -1);

        String file = null;
        String op = null;
        String anchor = null;
        String lang = null;

        int headerEndExclusive = 0;
        boolean sawAnyHeader = false;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String header = extractHeaderFromCommentLine(raw);
            if (header == null) {
                // stop header scan at first non-header (after having seen at least one header)
                if (sawAnyHeader) {
                    headerEndExclusive = i;
                    break;
                }
                // If we haven't seen any header yet, we keep scanning only if this is blank/comment noise
                // but if it's real code, stop early.
                if (!raw.trim().isEmpty() && !isCommentLine(raw)) {
                    return null; // doesn't look like a snippet at all
                }
                continue;
            }

            sawAnyHeader = true;
            headerEndExclusive = i + 1;

            String h = header.trim();
            int colon = h.indexOf(':');
            if (colon <= 0) continue;

            String key = h.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String val = h.substring(colon + 1).trim();

            switch (key) {
                case "FILE" -> file = val;
                case "OPERATION" -> op = val;
                case "ANCHOR" -> anchor = val;
                case "LANGUAGE" -> lang = val;
            }
        }

        if (!sawAnyHeader) return null;
        if (file == null || file.isBlank()) return null;
        if (op == null || op.isBlank()) return null;

        OutputBlock b = new OutputBlock();
        b.file = file;
        b.action = normalizeOperation(op); // stored in action field
        b.anchor = anchor;
        b.note = (lang == null || lang.isBlank()) ? null : ("LANGUAGE=" + lang);

        // For display friendliness, keep the snippet as-is (headers + code)
        b.code = snippet.stripTrailing();
        return b;
    }

    private String normalizeOperation(String op) {
        if (op == null) return null;
        String x = op.trim().toUpperCase(Locale.ROOT);

        // Map PromptTemplates OPERATION values to your existing "action" semantics
        // (Your UI just displays "action" anyway.)
        // Keep as-is for known values.
        return switch (x) {
            case "REPLACE_ANCHOR" -> "REPLACE_ANCHOR";
            case "INSERT_AFTER_ANCHOR" -> "INSERT_AFTER_ANCHOR";
            case "REPLACE_METHOD" -> "REPLACE_METHOD";
            case "REPLACE_CLASS" -> "REPLACE_CLASS";
            case "CREATE_FILE" -> "CREATE_FILE";
            default -> x;
        };
    }

    private boolean looksLikeHeaderLine(String line) {
        String h = extractHeaderFromCommentLine(line);
        if (h == null) return false;
        String u = h.trim().toUpperCase(Locale.ROOT);
        return u.startsWith("FILE:") || u.startsWith("OPERATION:") || u.startsWith("LANGUAGE:") || u.startsWith("ANCHOR:");
    }

    private boolean isCommentLine(String line) {
        if (line == null) return false;
        String t = line.trim();
        return t.startsWith("//") || t.startsWith("#") || t.startsWith("<!--");
    }

    /**
     * If the line is a comment and contains a header like:
     *   // FILE: ...
     *   # OPERATION: ...
     *   <!-- LANGUAGE: ... -->
     * returns "FILE: ..." etc (without comment tokens).
     * Otherwise returns null.
     */
    private String extractHeaderFromCommentLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty()) return null;

        String inner = null;

        if (t.startsWith("//")) {
            inner = t.substring(2).trim();
        } else if (t.startsWith("#")) {
            inner = t.substring(1).trim();
        } else if (t.startsWith("<!--")) {
            inner = t;
            // strip <!-- and -->
            inner = inner.substring(4).trim();
            if (inner.endsWith("-->")) {
                inner = inner.substring(0, inner.length() - 3).trim();
            }
        } else {
            return null;
        }

        if (inner == null) return null;

        String u = inner.toUpperCase(Locale.ROOT);
        if (u.startsWith("FILE:") || u.startsWith("OPERATION:") || u.startsWith("ANCHOR:") || u.startsWith("LANGUAGE:")) {
            return inner;
        }
        return null;
    }
}
