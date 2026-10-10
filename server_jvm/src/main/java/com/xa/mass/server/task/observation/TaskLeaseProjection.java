package com.xa.mass.server.task.observation;

import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Immutable route and pure business interpretation of stored Results. No Pacer/provider access. */
public record TaskLeaseProjection(String projectId, String workerGroupId, String poolName,
        Function<Map<String, TaskItemResultResponse>, Map<Coordinate, Long>> project) {
    public TaskLeaseProjection {
        for (String value : new String[]{projectId, workerGroupId, poolName})
            if (value == null || value.isBlank()) throw new IllegalArgumentException("projection route must be nonblank");
        Objects.requireNonNull(project);
    }
}
