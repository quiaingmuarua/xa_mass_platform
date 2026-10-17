package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBindingsResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class WorkerGroupBindingsQueryService {
    private final WorkerResourceCatalog workers;
    private final WorkerMatchingCatalog matching;
    private final WorkerGroupBindings bindings;

    public WorkerGroupBindingsQueryService(WorkerResourceCatalog workers, WorkerMatchingCatalog matching, WorkerGroupBindings bindings) {
        this.workers = workers; this.matching = matching; this.bindings = bindings;
    }

    public WorkerGroupBindingsResponse load(String groupId, String requestId) {
        if (groupId == null || groupId.isBlank()) throw new ServerException(ServerErrorCode.MALFORMED_REQUEST,
                "workerBindings.load", null, null);
        try {
            var descriptor = workers.getWorkerGroupDescriptors(List.of(groupId)).get(groupId);
            if (descriptor != null && !groupId.equals(descriptor.workerGroupId()))
                throw new IllegalStateException("WorkerGroup descriptor identity differs");
            var group = bindings.group(groupId);
            return new WorkerGroupBindingsResponse(groupId, descriptor == null ? null : new WorkerGroupView(groupId,
                    descriptor.attributes(), descriptor.eventCodes().stream().sorted().toList()), matching.capabilities(groupId),
                    group.fixedWindowPools(), group.feedbacks(), Instant.now());
        } catch (RuntimeException failure) {
            throw RuntimeViewService.unavailable("workerBindings.load", groupId, requestId, failure);
        }
    }
}
