package com.QM4RS.agent.core;

import javafx.scene.control.CheckBoxTreeItem;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class ProjectScanner {

    public record ScanResult(CheckBoxTreeItem<Path> rootItem, List<Path> allFiles) {}

    private final IgnoreRules ignoreRules;

    public ProjectScanner(IgnoreRules ignoreRules) {
        this.ignoreRules = ignoreRules;
    }

    public ScanResult scan(Path projectRoot) throws IOException {
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            throw new IllegalArgumentException("Project root is not a directory: " + projectRoot);
        }

        // Root item
        CheckBoxTreeItem<Path> root = new CheckBoxTreeItem<>(projectRoot);
        root.setExpanded(true);

        // We need fast parent->child building
        Map<Path, CheckBoxTreeItem<Path>> itemByPath = new HashMap<>();
        itemByPath.put(projectRoot, root);

        List<Path> allFiles = new ArrayList<>();

        Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (ignoreRules.shouldIgnore(dir, projectRoot)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        if (!dir.equals(projectRoot)) {
                            CheckBoxTreeItem<Path> parentItem = itemByPath.get(dir.getParent());
                            if (parentItem != null) {
                                CheckBoxTreeItem<Path> dirItem = new CheckBoxTreeItem<>(dir);
                                dirItem.setExpanded(false);
                                parentItem.getChildren().add(dirItem);
                                itemByPath.put(dir, dirItem);
                            }
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (ignoreRules.shouldIgnore(file, projectRoot)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!Files.isRegularFile(file)) {
                            return FileVisitResult.CONTINUE;
                        }

                        allFiles.add(file);

                        CheckBoxTreeItem<Path> parentItem = itemByPath.get(file.getParent());
                        if (parentItem != null) {
                            CheckBoxTreeItem<Path> fileItem = new CheckBoxTreeItem<>(file);
                            parentItem.getChildren().add(fileItem);
                            itemByPath.put(file, fileItem);
                        }

                        return FileVisitResult.CONTINUE;
                    }
                });

        // Sort children alphabetically: directories first then files
        sortTree(root);

        return new ScanResult(root, allFiles);
    }

    private void sortTree(CheckBoxTreeItem<Path> root) {
        Deque<CheckBoxTreeItem<Path>> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            CheckBoxTreeItem<Path> node = stack.pop();

            node.getChildren().sort((a, b) -> {
                Path pa = a.getValue();
                Path pb = b.getValue();
                boolean da = Files.isDirectory(pa);
                boolean db = Files.isDirectory(pb);
                if (da != db) return da ? -1 : 1;
                String na = pa.getFileName() == null ? pa.toString() : pa.getFileName().toString();
                String nb = pb.getFileName() == null ? pb.toString() : pb.getFileName().toString();
                return na.compareToIgnoreCase(nb);
            });

            for (var child : node.getChildren()) {
                stack.push((CheckBoxTreeItem<Path>) child);
            }
        }
    }
}
