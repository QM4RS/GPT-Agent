package com.QM4RS.agent.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Local chat history store (persisted on disk).
 */
public class ChatStore {

    public static class ChatRevision {
        public OffsetDateTime at;
        public String model;
        public String userPrompt;
        public Boolean historyIncluded;

        public Integer inputTokens;
        public Integer outputTokens;
        public Integer totalTokens;

        public String responseText;
    }

    public static class ChatSession {
        public String id;
        public String title;
        public String promptText;
        public Boolean includeHistory;

        public LocalDateTime createdAt;
        public LocalDateTime updatedAt;

        public List<ChatRevision> revisions;
        public Integer currentRevisionIndex;

        @JsonIgnore
        public int revisionCount() {
            return revisions == null ? 0 : revisions.size();
        }

        @JsonIgnore
        public void clampRevisionIndex() {
            int n = revisionCount();
            if (n <= 0) {
                currentRevisionIndex = -1;
                return;
            }
            if (currentRevisionIndex == null) currentRevisionIndex = n - 1;
            if (currentRevisionIndex < 0) currentRevisionIndex = 0;
            if (currentRevisionIndex >= n) currentRevisionIndex = n - 1;
        }

        @JsonIgnore
        public ChatRevision getCurrentRevision() {
            int n = revisionCount();
            if (n <= 0) return null;
            clampRevisionIndex();
            int idx = currentRevisionIndex == null ? (n - 1) : currentRevisionIndex;
            if (idx < 0 || idx >= n) return null;
            return revisions.get(idx);
        }

        @JsonIgnore
        public LocalDateTime getUpdatedAtSafe() {
            if (updatedAt != null) return updatedAt;
            return createdAt;
        }
    }

    private final Path storePath;
    private final ObjectMapper om;

    private final List<ChatSession> sessions = new ArrayList<>();

    public ChatStore() {
        this.storePath = Path.of(System.getProperty("user.home"), ".gpt-agent", "chats.json");
        this.om = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void load() {
        sessions.clear();
        try {
            if (!Files.exists(storePath)) return;
            String json = Files.readString(storePath);
            if (json == null || json.isBlank()) return;

            ChatSession[] arr = om.readValue(json, ChatSession[].class);
            if (arr != null) sessions.addAll(Arrays.asList(arr));

            // sanity defaults
            for (ChatSession s : sessions) {
                if (s.id == null || s.id.isBlank()) s.id = UUID.randomUUID().toString();
                if (s.title == null) s.title = "Chat";
                if (s.promptText == null) s.promptText = "";
                if (s.includeHistory == null) s.includeHistory = false;
                if (s.revisions == null) s.revisions = new ArrayList<>();
                if (s.createdAt == null) s.createdAt = LocalDateTime.now();
                if (s.updatedAt == null) s.updatedAt = s.createdAt;
                if (s.currentRevisionIndex == null) s.currentRevisionIndex = s.revisionCount() - 1;
                s.clampRevisionIndex();
            }
        } catch (Exception ignored) {
        }
    }

    public void save() throws Exception {
        Files.createDirectories(storePath.getParent());
        String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(sessions);
        Files.writeString(storePath, json);
    }

    public ChatSession createNew() {
        ChatSession s = new ChatSession();
        s.id = UUID.randomUUID().toString();
        s.title = "New Chat";
        s.promptText = "";
        s.includeHistory = false;
        s.revisions = new ArrayList<>();
        s.currentRevisionIndex = -1;
        s.createdAt = LocalDateTime.now();
        s.updatedAt = s.createdAt;

        sessions.add(s);
        return s;
    }

    public void delete(ChatSession s) {
        if (s == null) return;
        sessions.removeIf(x -> Objects.equals(x.id, s.id));
    }

    public List<ChatSession> getSessionsSorted() {
        List<ChatSession> out = new ArrayList<>(sessions);

        out.sort((a, b) -> {
            int ar = a == null ? 0 : a.revisionCount();
            int br = b == null ? 0 : b.revisionCount();

            if (ar == 0 && br > 0) return 1;
            if (ar > 0 && br == 0) return -1;

            // Both no revisions: keep them ordered by createdAt (newer first)
            if (ar == 0 && br == 0) {
                LocalDateTime ac = (a == null ? null : a.createdAt);
                LocalDateTime bc = (b == null ? null : b.createdAt);
                return compareDesc(bc, ac); // newer first
            }

            // Both have revisions: order by updatedAt (newer first)
            LocalDateTime au = (a == null ? null : a.getUpdatedAtSafe());
            LocalDateTime bu = (b == null ? null : b.getUpdatedAtSafe());
            return compareDesc(bu, au);
        });

        return out;
    }

    /**
     * promote=false => do NOT update updatedAt (so selection/prompt edits won't reorder chats with 0 revisions)
     * promote=true  => update updatedAt (used when a new revision is added or you explicitly want to bump)
     */
    public void touch(ChatSession s, boolean promote) {
        if (s == null) return;

        // if chat has no revisions, we NEVER bump updatedAt unless explicitly promote=true AND you want it.
        // Your requirement: until a revision exists, don't jump to top on select/edit => so callers use promote=false.
        if (promote) {
            s.updatedAt = LocalDateTime.now();
        } else {
            // keep updatedAt unchanged
            if (s.updatedAt == null) s.updatedAt = s.createdAt == null ? LocalDateTime.now() : s.createdAt;
        }
    }

    // Backward compat: old calls default to promote=true
    public void touch(ChatSession s) {
        touch(s, true);
    }

    private static int compareDesc(LocalDateTime a, LocalDateTime b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        // DESC
        return b.compareTo(a);
    }
}
