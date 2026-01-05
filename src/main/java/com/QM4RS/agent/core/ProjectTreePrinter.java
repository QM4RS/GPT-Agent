package com.QM4RS.agent.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

public class ProjectTreePrinter {

    private final IgnoreRules ignoreRules;

    public ProjectTreePrinter(IgnoreRules ignoreRules) {
        this.ignoreRules = ignoreRules;
    }

    public String printTree(Path projectRoot) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(projectRoot.getFileName() != null ? projectRoot.getFileName() : projectRoot.toString()).append("/\n");

        Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (ignoreRules.shouldIgnore(dir, projectRoot)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!dir.equals(projectRoot)) {
                            appendLine(sb, projectRoot, dir, true);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (ignoreRules.shouldIgnore(file, projectRoot)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (Files.isRegularFile(file)) {
                            appendLine(sb, projectRoot, file, false);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });

        return sb.toString();
    }

    private void appendLine(StringBuilder sb, Path root, Path path, boolean isDir) {
        Path rel = root.relativize(path);
        int depth = rel.getNameCount();

        // indent
        sb.append("  ".repeat(Math.max(0, depth)));
        sb.append(isDir ? "📁 " : "📄 ");
        sb.append(rel.getFileName() != null ? rel.getFileName().toString() : rel.toString());
        if (isDir) sb.append("/");
        sb.append("\n");
    }
}
