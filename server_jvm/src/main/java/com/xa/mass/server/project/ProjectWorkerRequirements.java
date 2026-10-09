package com.xa.mass.server.project;

import java.util.Set;

/** Startup dependencies on a shared Group; grants no resource mutation authority. */
public record ProjectWorkerRequirements(
        String projectId,
        String workerGroupId,
        Set<String> eventCodes,
        Set<String> pools,
        Set<String> functions
) {
    public ProjectWorkerRequirements {
        requireName(projectId); requireName(workerGroupId);
        eventCodes = capture(eventCodes);
        pools = capture(pools);
        functions = capture(functions);
    }

    private static Set<String> capture(Set<String> values) {
        if (values == null) throw new IllegalArgumentException("requirement sets must be present");
        values.forEach(ProjectWorkerRequirements::requireName);
        return Set.copyOf(values);
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("requirement names must be non-blank");
    }
}
