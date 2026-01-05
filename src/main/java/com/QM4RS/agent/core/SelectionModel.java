package com.QM4RS.agent.core;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class SelectionModel {

    private final Set<Path> selectedFiles = new LinkedHashSet<>();

    public void setSelected(Path file, boolean selected) {
        if (file == null) return;
        if (selected) selectedFiles.add(file);
        else selectedFiles.remove(file);
    }

    public void clear() {
        selectedFiles.clear();
    }

    public List<Path> getSelectedFilesSorted() {
        return selectedFiles.stream()
                .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    public int count() {
        return selectedFiles.size();
    }
}
