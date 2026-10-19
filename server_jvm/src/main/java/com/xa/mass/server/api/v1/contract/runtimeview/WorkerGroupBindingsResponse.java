package com.xa.mass.server.api.v1.contract.runtimeview;

import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record WorkerGroupBindingsResponse(String workerGroupId, @Nullable WorkerGroupView workerGroup,
        WorkerMatchingCatalog.Capabilities matching, List<WorkerGroupBindings.WindowPool> fixedWindowPools,
        List<Project> projects, Instant readAt) {
    public record Project(String projectId, String workerGroupSource) {}
    public WorkerGroupBindingsResponse { fixedWindowPools = List.copyOf(fixedWindowPools); projects = List.copyOf(projects); }
}
