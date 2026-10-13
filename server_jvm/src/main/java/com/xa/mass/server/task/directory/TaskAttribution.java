package com.xa.mass.server.task.directory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Server interpretation of otherwise opaque, immutable Task metadata. */
public record TaskAttribution(String projectId, long createdAtMillis, @Nullable String userId) {
    public static final String PROJECT = "server.projectId";
    public static final String CREATED = "server.createdAtMillis";

    public TaskAttribution {
        if (projectId == null || projectId.isBlank() || createdAtMillis < 0 || createdAtMillis > 9_007_199_254_740_991L)
            throw new IllegalArgumentException("Invalid Task attribution");
        if (userId != null && !TaskUserId.normalize(userId).equals(userId))
            throw new IllegalArgumentException("Task userId is not canonical");
    }

    public long score() { return userId == null ? 0 : TaskUserId.score(userId); }

    public static Map<String, String> input(Map<String, String> metadata) {
        var copy = new LinkedHashMap<>(metadata);
        if (copy.keySet().stream().anyMatch(key -> key.startsWith("server.")))
            throw new IllegalArgumentException("server.* metadata is reserved");
        if (copy.containsKey("userId")) copy.put("userId", TaskUserId.normalize(copy.get("userId")));
        return Map.copyOf(copy);
    }

    public Map<String, String> stamp(Map<String, String> metadata) {
        var copy = new LinkedHashMap<>(metadata);
        copy.put(PROJECT, projectId);
        copy.put(CREATED, Long.toString(createdAtMillis));
        return Map.copyOf(copy);
    }

    public static @Nullable TaskAttribution read(Map<String, String> metadata) {
        String project = metadata.get(PROJECT), created = metadata.get(CREATED);
        if (project == null && created == null) return null;
        if (project == null || created == null || !created.matches("0|[1-9][0-9]*"))
            throw new IllegalArgumentException("Task attribution is incomplete");
        return new TaskAttribution(project, Long.parseLong(created), metadata.get("userId"));
    }

    public static TaskAttribution require(Map<String, String> metadata) {
        var value = read(metadata);
        if (value == null) throw new IllegalArgumentException("Task has no Server attribution");
        return value;
    }
}
