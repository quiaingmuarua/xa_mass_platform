package com.xa.mass.server.api.v1.contract.runtimeview;

import java.time.Instant;
import java.util.List;

/** Startup-associated identities only, not the complete registered Group directory. */
public record WorkerGroupBindingsDirectoryResponse(List<String> workerGroupIds, Instant readAt) {
    public WorkerGroupBindingsDirectoryResponse { workerGroupIds = List.copyOf(workerGroupIds); }
}
