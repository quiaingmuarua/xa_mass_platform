package com.xa.mass.server.api.v1.contract.runtimeview;

import java.util.List;

public record WorkerGroupBatchGetResponse(
        List<WorkerGroupView> workerGroups,
        List<String> missingWorkerGroupIds
) {
}
