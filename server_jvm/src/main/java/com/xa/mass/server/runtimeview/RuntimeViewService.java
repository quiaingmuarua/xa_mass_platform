package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupBatchGetResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupView;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerPreviewResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class RuntimeViewService {

    private static final System.Logger LOGGER =
            System.getLogger(RuntimeViewService.class.getName());
    private static final String BATCH_GET_OPERATION =
            "runtimeView.batchGetWorkerGroups";
    private static final String PREVIEW_OPERATION =
            "runtimeView.previewWorkers";

    private final WorkerResourceCatalog workerCatalog;

    public RuntimeViewService(WorkerResourceCatalog workerCatalog) {
        this.workerCatalog = workerCatalog;
    }

    public WorkerGroupBatchGetResponse batchGetWorkerGroups(
            List<String> workerGroupIds,
            String requestId
    ) {
        if (new LinkedHashSet<>(workerGroupIds).size()
                != workerGroupIds.size()) {
            throw new ServerException(
                    ServerErrorCode.MALFORMED_REQUEST,
                    BATCH_GET_OPERATION,
                    null,
                    null
            );
        }

        try {
            Map<String, WorkerGroupDescriptor> loaded =
                    workerCatalog.getWorkerGroupDescriptors(
                            workerGroupIds
                    );
            var groups = new ArrayList<WorkerGroupView>();
            var missing = new ArrayList<String>();
            for (String workerGroupId : workerGroupIds) {
                WorkerGroupDescriptor descriptor =
                        loaded.get(workerGroupId);
                if (descriptor == null) {
                    missing.add(workerGroupId);
                    continue;
                }
                groups.add(toView(descriptor));
            }
            return new WorkerGroupBatchGetResponse(
                    List.copyOf(groups),
                    List.copyOf(missing)
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(
                    BATCH_GET_OPERATION,
                    String.join(",", workerGroupIds),
                    requestId,
                    error
            );
        }
    }

    public WorkerPreviewResponse previewWorkers(
            String workerGroupId,
            int sampleLimit,
            Object filter,
            String requestId
    ) {
        try {
            WorkerGroupDescriptor group = workerCatalog
                    .getWorkerGroupDescriptors(
                            List.of(workerGroupId)
                    )
                    .get(workerGroupId);
            if (group == null) {
                throw new ServerException(
                        ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                        PREVIEW_OPERATION,
                        null,
                        null
                );
            }
            if (filter != null) {
                throw new ServerException(
                        ServerErrorCode.RUNTIME_VIEW_FILTER_NOT_AVAILABLE,
                        PREVIEW_OPERATION,
                        null,
                        null
                );
            }

            Map<String, WorkerDescriptor> sampled =
                    workerCatalog.sampleWorkerDescriptors(
                            workerGroupId,
                            sampleLimit
                    );
            var workers = new ArrayList<WorkerView>();
            int unreadableCount = 0;
            for (WorkerDescriptor descriptor : sampled.values()) {
                if (descriptor == null) {
                    unreadableCount++;
                    continue;
                }
                workers.add(toView(descriptor));
            }
            return new WorkerPreviewResponse(
                    workerGroupId,
                    sampleLimit,
                    sampled.size(),
                    workers.size(),
                    unreadableCount,
                    Instant.now(),
                    List.copyOf(workers)
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(
                    PREVIEW_OPERATION,
                    workerGroupId,
                    requestId,
                    error
            );
        }
    }

    private static WorkerGroupView toView(
            WorkerGroupDescriptor descriptor
    ) {
        return new WorkerGroupView(
                descriptor.workerGroupId(),
                immutableMap(descriptor.attributes()),
                sorted(descriptor.eventCodes())
        );
    }

    private static WorkerView toView(WorkerDescriptor descriptor) {
        return new WorkerView(
                descriptor.workerId(),
                descriptor.workerGroupId(),
                descriptor.endpointManagerId(),
                immutableMap(descriptor.workerProperties()),
                immutableMap(descriptor.platformProperties())
        );
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static Map<String, Object> immutableMap(
            Map<String, Object> values
    ) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(values)
        );
    }

    private static ServerException unavailable(
            String operation,
            String workerGroupId,
            String requestId,
            RuntimeException cause
    ) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "code=" + ServerErrorCode.RUNTIME_VIEW_UNAVAILABLE.code()
                        + " operation=" + operation
                        + " workerGroupId=" + safeLogValue(workerGroupId)
                        + " requestId=" + safeLogValue(requestId)
        );
        return new ServerException(
                ServerErrorCode.RUNTIME_VIEW_UNAVAILABLE,
                operation,
                null,
                cause
        );
    }

    private static String safeLogValue(String value) {
        if (value == null) {
            return "-";
        }
        StringBuilder safe = new StringBuilder();
        value.codePoints()
                .limit(256)
                .forEach(codePoint -> safe.append(
                        Character.isLetterOrDigit(codePoint)
                                || "._:,-".indexOf(codePoint) >= 0
                                ? Character.toString(codePoint)
                                : "_"
                ));
        return safe.toString();
    }
}
