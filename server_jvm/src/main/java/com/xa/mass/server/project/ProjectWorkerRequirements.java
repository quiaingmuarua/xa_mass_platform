package com.xa.mass.server.project;

import java.util.*;

/** Startup dependencies on shared Groups; grants no resource mutation authority. */
public record ProjectWorkerRequirements(String projectId, Map<String, Resources> requirementsByGroup) {
    public ProjectWorkerRequirements {
        requireName(projectId);
        if (requirementsByGroup == null || requirementsByGroup.isEmpty())
            throw new IllegalArgumentException("requirement WorkerGroups must be present");
        var copy = new LinkedHashMap<String, Resources>();
        requirementsByGroup.forEach((group, resources) -> {
            requireName(group);
            copy.put(group, Objects.requireNonNull(resources, "resources"));
        });
        requirementsByGroup = Collections.unmodifiableMap(copy);
    }
    public record Resources(Set<String> eventCodes, Set<String> pools, Set<String> functions) {
        public Resources { eventCodes = capture(eventCodes); pools = capture(pools); functions = capture(functions); }
    }
    private static Set<String> capture(Set<String> values) {
        if (values == null) throw new IllegalArgumentException("requirement sets must be present");
        values.forEach(ProjectWorkerRequirements::requireName);
        return Set.copyOf(values);
    }
    private static void requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank name required");
    }
}
