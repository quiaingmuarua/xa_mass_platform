package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskPreviewEntry;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskPreviewResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBatchGetResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupPreviewResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupView;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerPreviewResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerSchedulingObserveResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.TaskRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
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
    private static final String TASK_PREVIEW_OPERATION =
            "runtimeView.previewTasks";
    private static final String PREVIEW_OPERATION =
            "runtimeView.previewWorkers";
    private static final String GROUP_PREVIEW_OPERATION =
            "runtimeView.previewWorkerGroups";
    private static final String SCHEDULING_OBSERVE_OPERATION =
            "runtimeView.observeWorkerScheduling";

    private final WorkerResourceCatalog workerCatalog;
    private final TaskResourceCatalog taskCatalog;
    private final TaskScoreBandCore taskScores;
    private final WorkerSchedulingService workerScheduling;
    private final WorkerMatchingCatalog matchingCatalog;

    public RuntimeViewService(
            WorkerResourceCatalog workerCatalog,
            TaskResourceCatalog taskCatalog,
            TaskScoreBandCore taskScores,
            WorkerSchedulingService workerScheduling,
            WorkerMatchingCatalog matchingCatalog
    ) {
        this.workerCatalog = workerCatalog;
        this.taskCatalog = taskCatalog;
        this.taskScores = taskScores;
        this.workerScheduling = workerScheduling;
        this.matchingCatalog = matchingCatalog;
    }

    public TaskPreviewResponse previewTasks(
            int sampleLimit,
            String requestId
    ) {
        try {
            List<TaskScoreState> scoreStates =
                    taskScores.previewScoreStates(sampleLimit);
            List<String> taskIds = scoreStates.stream()
                    .map(TaskScoreState::taskId)
                    .toList();
            validatePreviewTaskIds(taskIds);
            Map<String, TaskDescriptor> tasks = taskIds.isEmpty()
                    ? Map.of()
                    : taskCatalog.loadTaskAllocationDescriptors(taskIds);
            var workerGroupIds = new LinkedHashSet<String>();
            var taskRuleIds = new ArrayList<String>();
            for (String taskId : taskIds) {
                TaskDescriptor task = tasks.get(taskId);
                validateTaskIdentity(taskId, task);
                if (task != null) {
                    workerGroupIds.add(task.workerGroupId());
                    if (task.workerAllocationMechanism()
                            == WorkerAllocationMechanism
                            .PRECOMPUTED_TASK_RULE) {
                        taskRuleIds.add(taskId);
                    }
                }
            }
            Map<String, TaskRule> taskRules = taskRuleIds.isEmpty()
                    ? Map.of()
                    : matchingCatalog.loadTaskRules(taskRuleIds);
            Map<String, WorkerGroupDescriptor> groups = workerGroupIds.isEmpty()
                    ? Map.of()
                    : workerCatalog.getWorkerGroupDescriptors(
                            List.copyOf(workerGroupIds)
                    );
            workerGroupIds.forEach(workerGroupId ->
                    validateWorkerGroupIdentity(
                            workerGroupId,
                            groups.get(workerGroupId)
                    ));

            var entries = new ArrayList<TaskPreviewEntry>(scoreStates.size());
            for (TaskScoreState scoreState : scoreStates) {
                TaskDescriptor task = tasks.get(scoreState.taskId());
                WorkerGroupDescriptor group = task == null
                        ? null
                        : groups.get(task.workerGroupId());
                entries.add(new TaskPreviewEntry(
                        scoreState.taskId(),
                        taskScoreView(scoreState),
                        task == null
                                ? null
                                : toView(
                                        task,
                                        taskRules.get(task.taskId())
                                ),
                        group == null ? null : toView(group)
                ));
            }
            return new TaskPreviewResponse(
                    sampleLimit,
                    Instant.now(),
                    List.copyOf(entries)
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(
                    TASK_PREVIEW_OPERATION,
                    "tasks",
                    requestId,
                    error
            );
        }
    }

    private static String taskScoreView(TaskScoreState state) {
        if (state.isInitial()) {
            return "running-initial";
        }
        return state.band().wireValue();
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
            Map<String, WorkerDescriptor> sampled =
                    workerCatalog.sampleWorkerDescriptors(
                            workerGroupId,
                            sampleLimit
                    );
            Map<String, WorkerFacts> facts = sampled.isEmpty()
                    ? Map.of()
                    : matchingCatalog.loadWorkerFacts(
                            workerGroupId,
                            List.copyOf(sampled.keySet())
                    );
            var workers = new ArrayList<WorkerView>();
            int unreadableCount = 0;
            for (Map.Entry<String, WorkerDescriptor> entry
                    : sampled.entrySet()) {
                WorkerDescriptor descriptor = entry.getValue();
                WorkerFacts workerFacts = facts.get(entry.getKey());
                if (descriptor == null || workerFacts == null) {
                    unreadableCount++;
                    continue;
                }
                validateWorkerFacts(descriptor, workerFacts);
                workers.add(toView(descriptor, workerFacts));
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

    private static TaskView toView(
            TaskDescriptor descriptor,
            TaskRule rule
    ) {
        Map<String, Object> allocationRule = null;
        if (descriptor.workerAllocationMechanism()
                == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE) {
            if (rule == null
                    || !descriptor.taskId().equals(rule.taskId())
                    || !descriptor.workerGroupId().equals(
                            rule.workerGroupId()
                    )) {
                throw new IllegalStateException(
                        "Task matching rule is missing or inconsistent"
                );
            }
            allocationRule = immutableMap(rule.allocationRule());
        }
        return new TaskView(
                descriptor.taskId(),
                descriptor.workerGroupId(),
                descriptor.workerAllocationMechanism().name(),
                descriptor.idleDisposition().name(),
                allocationRule,
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(descriptor.config())
                )
        );
    }

    private static WorkerView toView(
            WorkerDescriptor descriptor,
            WorkerFacts facts
    ) {
        return new WorkerView(
                descriptor.workerId(),
                descriptor.workerGroupId(),
                descriptor.endpointManagerId(),
                immutableMap(facts.workerProperties()),
                immutableMap(facts.platformProperties())
        );
    }

    private static void validateWorkerFacts(
            WorkerDescriptor descriptor,
            WorkerFacts facts
    ) {
        if (!descriptor.workerId().equals(facts.workerId())
                || !descriptor.workerGroupId().equals(
                        facts.workerGroupId()
                )) {
            throw new IllegalStateException(
                    "Worker matching facts identity mismatch"
            );
        }
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

    private static void validatePreviewTaskIds(List<String> taskIds) {
        if (taskIds.stream().anyMatch(
                taskId -> taskId == null || taskId.isBlank()
        ) || new LinkedHashSet<>(taskIds).size() != taskIds.size()) {
            throw new IllegalStateException(
                    "Task Score preview identities are invalid"
            );
        }
    }

    private static void validateTaskIdentity(
            String taskId,
            TaskDescriptor task
    ) {
        if (task != null && !taskId.equals(task.taskId())) {
            throw new IllegalStateException(
                    "Task preview identity mismatch"
            );
        }
    }

    private static void validateWorkerGroupIdentity(
            String workerGroupId,
            WorkerGroupDescriptor group
    ) {
        if (group != null
                && !workerGroupId.equals(group.workerGroupId())) {
            throw new IllegalStateException(
                    "Task preview WorkerGroup identity mismatch"
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
