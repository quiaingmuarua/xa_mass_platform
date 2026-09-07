package com.xa.mass.server.worker.resource;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class WorkerResourceCommandService {

    private static final String PATCH_OPERATION =
            "workerResource.patchPlatformProperties";

    private final WorkerMatchingCatalog matchingCatalog;
    private final WorkerBindingService bindings;
    private final WorkerResourceCatalog workers;

    public WorkerResourceCommandService(
            WorkerMatchingCatalog matchingCatalog,
            WorkerBindingService bindings,
            WorkerResourceCatalog workers
    ) {
        this.matchingCatalog = Objects.requireNonNull(
                matchingCatalog,
                "matchingCatalog"
        );
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    /** Admits complete observations for existing, bound Workers; returns accepted identities. */
    public Set<String> replaceReportedProperties(
            String adapterId,
            Map<String, Map<String, String>> propertiesByWorkerId
    ) {
        String operation = "workerResource.replaceReportedProperties";
        if (adapterId == null || adapterId.isBlank() || propertiesByWorkerId == null
                || propertiesByWorkerId.isEmpty()
                || propertiesByWorkerId.size() > WorkerMatchingCatalog.MAX_BATCH_SIZE) {
            throw new ServerException(ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST, operation, null, null);
        }
        List<String> workerIds = List.copyOf(propertiesByWorkerId.keySet());
        try {
            Map<String, String> endpoints = bindings.currentEndpointManagerIds(workerIds);
            Map<String, String> groups = workers.getWorkerGroupIds(workerIds);
            Map<String, Map<String, Map<String, String>>> byGroup = new LinkedHashMap<>();
            for (String workerId : workerIds) {
                String groupId = groups.get(workerId);
                if (adapterId.equals(endpoints.get(workerId)) && groupId != null && !groupId.isBlank()) {
                    byGroup.computeIfAbsent(groupId, ignored -> new LinkedHashMap<>())
                            .put(workerId, propertiesByWorkerId.get(workerId));
                }
            }
            Set<String> accepted = new LinkedHashSet<>();
            for (var group : byGroup.entrySet()) {
                Map<String, MutationResult> results = matchingCatalog.upsertWorkerFactsBatch(
                        group.getKey(), group.getValue()
                );
                for (String workerId : group.getValue().keySet()) {
                    MutationResult result = Objects.requireNonNull(results.get(workerId), "Worker mutation result");
                    switch (result.status()) {
                        case APPLIED, UNCHANGED -> accepted.add(workerId);
                        case NOT_FOUND, INVALID, CONFLICT -> { }
                    }
                }
            }
            return Set.copyOf(accepted);
        } catch (RuntimeException error) {
            // Earlier Group writes may already have committed. SYSTEM has no replay contract.
            throw new ServerException(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE, operation, null, error);
        }
    }

    public ActionOutcome patchPlatformProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> properties
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        if (properties == null) {
            throw failure(ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST);
        }
        MutationResult result;
        try {
            result = matchingCatalog.patchWorkerPlatformProperties(
                    workerGroupId,
                    workerId,
                    properties
            );
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                    PATCH_OPERATION,
                    null,
                    error
            );
        }
        if (result == null) {
            throw failure(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE);
        }
        return switch (result.status()) {
            case APPLIED -> ActionOutcome.applied();
            case UNCHANGED -> ActionOutcome.unchanged();
            case NOT_FOUND -> throw failure(
                    ServerErrorCode.WORKER_RESOURCE_NOT_FOUND
            );
            case INVALID -> throw failure(
                    ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST
            );
            case CONFLICT -> throw failure(
                    ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
            );
        };
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw failure(ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST);
        }
    }

    private static ServerException failure(ServerErrorCode code) {
        return new ServerException(code, PATCH_OPERATION, null, null);
    }

}
