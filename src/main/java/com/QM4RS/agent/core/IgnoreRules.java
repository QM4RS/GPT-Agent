package com.QM4RS.agent.core;

import java.nio.file.Path;
import java.util.Set;

public class IgnoreRules {

    private final Set<String> ignoredDirNames = Set.of(
            ".git", ".idea", ".gradle", "build", "out", "target", "node_modules",
            ".vscode", ".settings", ".classpath", ".project"
    );

    private final Set<String> ignoredExtensions = Set.of(
            // binaries / archives
            ".class", ".jar", ".war", ".ear", ".zip", ".7z", ".rar", ".tar", ".gz",
            ".exe", ".dll", ".so", ".dylib",
            // images / media
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico",
            ".mp3", ".mp4", ".mov", ".avi", ".mkv", ".wav",
            // misc large/binary
            ".pdf", ".psd", ".ttf", ".otf", ".woff", ".woff2",
            // lock/caches
            ".lock"
    );

    public boolean shouldIgnore(Path path, Path projectRoot) {
        if (path == null || projectRoot == null) return true;

        // ignore by any segment name
        Path rel;
        try {
            rel = projectRoot.relativize(path);
        } catch (Exception e) {
            return false;
        }

        for (Path part : rel) {
            String name = part.toString();
            if (ignoredDirNames.contains(name)) return true;
        }

        // ignore extensions for files
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        String lower = name.toLowerCase();
        for (String ext : ignoredExtensions) {
            if (lower.endsWith(ext)) return true;
        }

        return false;
    }
}
