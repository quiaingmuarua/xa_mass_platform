package com.xa.mass.server.project;

import java.util.HashSet;
import java.util.List;

/** Immutable Project topology shared by deployment configuration and module declarations. */
public record ProjectDefinition(String projectId, List<String> workerGroupIds, @org.jspecify.annotations.Nullable String exclusiveInputOwner) {
    public ProjectDefinition {
        if (projectId == null || projectId.isBlank())
            throw new IllegalArgumentException("projectId must be non-blank");
        workerGroupIds = workerGroupIds == null ? List.of() : List.copyOf(workerGroupIds);
        if ("default".equals(projectId) && !workerGroupIds.isEmpty())
            throw new IllegalArgumentException("default uses registered WorkerGroups; do not declare bindings");
        if (!"default".equals(projectId) && workerGroupIds.isEmpty())
            throw new IllegalArgumentException("Project must declare WorkerGroups");
        var groups = new HashSet<String>();
        for (String group : workerGroupIds)
            if (group == null || group.isBlank() || !groups.add(group))
                throw new IllegalArgumentException("Project WorkerGroups must be non-blank and unique");
        workerGroupIds = List.copyOf(workerGroupIds);
        if (exclusiveInputOwner != null && exclusiveInputOwner.isBlank())
            throw new IllegalArgumentException("exclusiveInputOwner must be non-blank");
    }
}
