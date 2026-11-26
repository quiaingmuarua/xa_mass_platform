package com.xa.mass.server.api.v1.contract.runtimeview;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Startup-associated identities and read-only Project topology, not the complete registered Group directory. */
public record WorkerGroupBindingsDirectoryResponse(
        List<String> workerGroupIds,
        List<ProjectTopology> projects,
        Instant readAt
) {
    public WorkerGroupBindingsDirectoryResponse {
        workerGroupIds = workerGroupIds == null ? List.of() : List.copyOf(workerGroupIds);
        projects = projects == null ? List.of() : List.copyOf(projects);
    }

    public WorkerGroupBindingsDirectoryResponse(List<String> workerGroupIds, Instant readAt) {
        this(workerGroupIds, List.of(), readAt);
    }

    @Schema(name = "ProjectResourceTopology")
    public record ProjectTopology(
            String projectId,
            String workerGroupSource,
            List<String> workerGroupIds,
            @Schema(nullable = true) @Nullable String exclusiveInputOwner
    ) {
        public ProjectTopology {
            workerGroupIds = workerGroupIds == null ? List.of() : List.copyOf(workerGroupIds);
        }
    }
}
