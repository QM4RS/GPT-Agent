package com.QM4RS.agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ContextPackBuilder {

    private final ProjectTreePrinter treePrinter;
    private final FileTextReader fileTextReader;

    // No nanos, readable
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");

    public ContextPackBuilder(ProjectTreePrinter treePrinter, FileTextReader fileTextReader) {
        this.treePrinter = treePrinter;
        this.fileTextReader = fileTextReader;
    }

    // Backward compatible
    public String build(Path projectRoot, List<Path> selectedFiles, String userPrompt) throws IOException {
        return build(projectRoot, selectedFiles, userPrompt, null);
    }

    // New: optional chat history
    public String build(Path projectRoot, List<Path> selectedFiles, String userPrompt, String chatHistoryAddon) throws IOException {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Project root is invalid.");
        }

        StringBuilder sb = new StringBuilder();

        sb.append("=== GPT-Agent Context Pack ===\n");
        sb.append("generated_at: ").append(TS.format(OffsetDateTime.now())).append("\n");
        sb.append("project_root: ").append(projectRoot.toAbsolutePath()).append("\n");
        sb.append("selected_files_count: ").append(selectedFiles == null ? 0 : selectedFiles.size()).append("\n");
        sb.append("\n");

        sb.append("=== Project Tree (paths only) ===\n");
        sb.append(treePrinter.printTree(projectRoot));
        sb.append("\n");

        if (chatHistoryAddon != null && !chatHistoryAddon.isBlank()) {
            sb.append("=== Chat History (AI suggested changes) ===\n");
            sb.append(chatHistoryAddon.strip()).append("\n\n");
        }

        sb.append("=== User Prompt ===\n");
        sb.append(userPrompt == null ? "" : userPrompt.trim());
        sb.append("\n\n");

        sb.append("=== Selected Files Content (UTF-8) ===\n");
        if (selectedFiles != null) {
            for (Path f : selectedFiles) {
                if (f == null) continue;
                if (!Files.exists(f) || !Files.isRegularFile(f)) continue;

                String rel = projectRoot.relativize(f).toString().replace('\\', '/');
                sb.append("\n--- FILE: ").append(rel).append(" ---\n");

                try {
                    String content = fileTextReader.readUtf8(f);
                    sb.append(content);
                    if (!content.endsWith("\n")) sb.append("\n");
                } catch (Exception ex) {
                    sb.append("[[ERROR reading file: ").append(ex.getMessage()).append("]]\n");
                }
            }
        }

        sb.append("\n=== End Context Pack ===\n");
        return sb.toString();
    }
}
