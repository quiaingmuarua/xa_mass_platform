package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.api.v1.runtimeview.model.ConfiguredRuntimeResourceEntry;
import com.xa.mass.server.api.v1.runtimeview.model.ConfiguredRuntimeResourcesResponse;
import com.xa.mass.server.api.v1.runtimeview.model.TaskView;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupBatchGetResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupPreviewResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupView;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerPreviewResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerSchedulingObserveResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import com.xa.mass.server.workerscheduling.WorkerSchedulingService;
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
    private static final String CONFIGURED_RESOURCES_OPERATION =
            "runtimeView.configuredResources";
    private static final String PREVIEW_OPERATION =
            "runtimeView.previewWorkers";
    private static final String GROUP_PREVIEW_OPERATION =
            "runtimeView.previewWorkerGroups";
    private static final String SCHEDULING_OBSERVE_OPERATION =
            "runtimeView.observeWorkerScheduling";

    private final WorkerResourceCatalog workerCatalog;
    private final TaskResourceCatalog taskCatalog;
    private final WorkerGroupTaskCatalog configuredTasks;
    private final WorkerSchedulingService workerScheduling;

    public RuntimeViewService(
            WorkerResourceCatalog workerCatalog,
            TaskResourceCatalog taskCatalog,
            WorkerGroupTaskCatalog configuredTasks,
            WorkerSchedulingService workerScheduling
    ) {
        this.workerCatalog = workerCatalog;
        this.taskCatalog = taskCatalog;
        this.configuredTasks = configuredTasks;
        this.workerScheduling = workerScheduling;
    }

    public ConfiguredRuntimeResourcesResponse configuredResources(
            String requestId
    ) {
        Map<String, String> configured =
                configuredTasks.taskIdsByWorkerGroup();
        if (configured.isEmpty()) {
            return new ConfiguredRuntimeResourcesResponse(List.of());
        }

        List<String> workerGroupIds = List.copyOf(configured.keySet());
        List<String> taskIds = List.copyOf(configured.values());
        try {
            Map<String, WorkerGroupDescriptor> groups = workerCatalog
                    .getWorkerGroupDescriptors(workerGroupIds);
            Map<String, TaskDescriptor> tasks = taskCatalog
                    .loadTaskAllocationDescriptors(taskIds);
            var entries = new ArrayList<ConfiguredRuntimeResourceEntry>();
            configured.forEach((workerGroupId, taskId) -> {
                WorkerGroupDescriptor group = groups.get(workerGroupId);
                TaskDescriptor task = tasks.get(taskId);
                validateConfiguredIdentity(
                        workerGroupId,
                        taskId,
                        group,
                        task
                );
                entries.add(new ConfiguredRuntimeResourceEntry(
                        workerGroupId,
                        taskId,
                        group == null ? null : toView(group),
                        task == null ? null : toView(task)
                ));
            });
            return new ConfiguredRuntimeResourcesResponse(
                    List.copyOf(entries)
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(
                    CONFIGURED_RESOURCES_OPERATION,
                    String.join(",", workerGroupIds),
                    requestId,
                    error
            );
        }
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

    public WorkerGroupPreviewResponse previewWorkerGroups(
            int sampleLimit,
            String requestId
    ) {
        try {
            Map<String, WorkerGroupDescriptor> sampled =
                    workerCatalog.sampleWorkerGroupDescriptors(sampleLimit);
            var groups = new ArrayList<WorkerGroupView>();
            int unreadableCount = 0;
            for (WorkerGroupDescriptor descriptor : sampled.values()) {
                if (descriptor == null) {
                    unreadableCount++;
                    continue;
                }
                groups.add(toView(descriptor));
            }
            return new WorkerGroupPreviewResponse(
                    sampleLimit,
                    sampled.size(),
                    groups.size(),
                    unreadableCount,
                    Instant.now(),
                    List.copyOf(groups)
            );
        } catch (RuntimeException error) {
            throw unavailable(
                    GROUP_PREVIEW_OPERATION,
                    "worker-groups",
                    requestId,
                    error
            );
        }
    }

    public WorkerSchedulingObserveResponse observeWorkerScheduling(
            String workerGroupId,
            List<String> workerIds,
            String requestId
    ) {
        if (new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new ServerException(
                    ServerErrorCode.MALFORMED_REQUEST,
                    SCHEDULING_OBSERVE_OPERATION,
                    null,
                    null
            );
        }
        try {
            WorkerSchedulingService.WorkerSchedulingObservation observation =
                    workerScheduling.observe(workerGroupId, workerIds);
            var states = new LinkedHashMap<String, String>();
            workerIds.forEach(workerId -> states.put(
                    workerId,
                    observation.statesByWorkerId()
                            .get(workerId)
                            .wireValue()
            ));
            return new WorkerSchedulingObserveResponse(
                    workerGroupId,
                    Instant.ofEpochMilli(observation.readAtMillis()),
                    states
            );
        } catch (RuntimeException error) {
            throw unavailable(
                    SCHEDULING_OBSERVE_OPERATION,
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

    private static TaskView toView(TaskDescriptor descriptor) {
        return new TaskView(
                descriptor.taskId(),
                descriptor.workerGroupId(),
                descriptor.workerAllocationMechanism().name(),
                descriptor.idleDisposition().name(),
                descriptor.allocationRule() == null
                        ? null
                        : immutableMap(descriptor.allocationRule()),
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(descriptor.config())
                )
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

    private static void validateConfiguredIdentity(
            String workerGroupId,
            String taskId,
            WorkerGroupDescriptor group,
            TaskDescriptor task
    ) {
        if (group != null
                && !workerGroupId.equals(group.workerGroupId())) {
            throw new IllegalStateException(
                    "Configured WorkerGroup identity mismatch"
            );
        }
        if (task != null
                && (!taskId.equals(task.taskId())
                || !workerGroupId.equals(task.workerGroupId()))) {
            throw new IllegalStateException(
                    "Configured Task identity mismatch"
            );
        }
    }

    static ServerException unavailable(
            String operation,
            String targetId,
            String requestId,
            Throwable cause
    ) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "code=" + ServerErrorCode.RUNTIME_VIEW_UNAVAILABLE.code()
                        + " operation=" + operation
                        + " targetId=" + safeLogValue(targetId)
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
