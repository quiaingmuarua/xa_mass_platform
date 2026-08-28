package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.IdleAction;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.TaskItemObservation;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.TaskItemWorkerAssignment;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

final class TaskDispatchPolicy {

    private final TaskExecutionMechanism execution;
    private final WorkerCandidateSelectionPolicy candidateSelection;
    private final LongSupplier currentTimeMillis;

    TaskDispatchPolicy(
            TaskExecutionMechanism execution,
            WorkerCandidateSelectionPolicy candidateSelection
    ) {
        this(execution, candidateSelection, System::currentTimeMillis);
    }

    TaskDispatchPolicy(
            TaskExecutionMechanism execution,
            WorkerCandidateSelectionPolicy candidateSelection,
            LongSupplier currentTimeMillis
    ) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.candidateSelection = Objects.requireNonNull(
                candidateSelection,
                "candidateSelection"
        );
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int dispatchTasks(
            List<DueTaskObservation> tasks,
            TaskDispatchConfig config
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        long dispatchTimeMillis = currentTimeMillis.getAsLong();
        long claimUntilMillis = Math.addExact(
                dispatchTimeMillis,
                config.itemClaimLeaseDurationMillis()
        );
        Set<String> roundWorkerIds = new LinkedHashSet<>();
        int published = 0;
        for (DueTaskObservation task : tasks) {
            List<TaskItemObservation> observed = execution.observeTaskItems(
                    task.taskId(),
                    config.perTaskDispatchLimit()
            );
            List<TaskItemObservation> failed = observed.stream()
                    .filter(item -> failed(item, dispatchTimeMillis))
                    .toList();
            execution.finalizeFailedItems(
                    task.taskId(),
                    failed,
                    dispatchTimeMillis
            );
            Set<String> failedIds = failed.stream()
                    .map(TaskItemObservation::messageId)
                    .collect(java.util.stream.Collectors.toSet());
            List<TaskItemObservation> claimable = observed.stream()
                    .filter(item -> item.remainingBudget() > 0)
                    .filter(item -> item.item() != null)
                    .filter(item -> !failedIds.contains(item.messageId()))
                    .toList();
            if (claimable.isEmpty()) {
                execution.settleNoClaimableItems(
                        task,
                        task.descriptor().idleDisposition()
                                == TaskIdleDisposition.CLOSE_WHEN_IDLE
                                ? IdleAction.CLOSE
                                : IdleAction.PARK,
                        dispatchTimeMillis
                );
                continue;
            }
            try {
                List<TaskItemWorkerAssignment> assignments = assignments(
                        task,
                        claimable,
                        claimUntilMillis,
                        roundWorkerIds
                );
                published += execution.dispatch(
                        task,
                        assignments,
                        claimUntilMillis
                );
            } finally {
                execution.onDispatchAttemptFinished(
                        task,
                        dispatchTimeMillis
                );
            }
        }
        return published;
    }

    private List<TaskItemWorkerAssignment> assignments(
            DueTaskObservation task,
            List<TaskItemObservation> items,
            long leaseUntilMillis,
            Set<String> roundWorkerIds
    ) {
        int priority = Integer.parseInt(
                task.descriptor().config().get("priority")
        );
        Map<String, List<WorkerCandidateObservation>> acquired;
        if (task.descriptor().workerAllocationMechanism()
                == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE) {
            acquired = candidateSelection.acquireWorkerCandidates(
                    WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
                    task.descriptor().workerGroupId(),
                    Map.of(task.taskId(), new WorkerCandidateRequest(
                            priority,
                            items.size(),
                            Objects.requireNonNull(
                                    task.descriptor().allocationRule()
                            )
                    )),
                    leaseUntilMillis
            );
            return pair(
                    items,
                    acquired.getOrDefault(task.taskId(), List.of()),
                    roundWorkerIds
            );
        }

        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        items.forEach(item -> requests.put(
                item.messageId(),
                new WorkerCandidateRequest(
                        priority,
                        1,
                        Objects.requireNonNull(
                                item.item().allocationRule()
                        )
                )
        ));
        acquired = candidateSelection.acquireWorkerCandidates(
                WorkerCandidateAcquisitionStrategy.DIRECT,
                task.descriptor().workerGroupId(),
                requests,
                leaseUntilMillis
        );
        List<TaskItemWorkerAssignment> result = new ArrayList<>();
        for (TaskItemObservation item : items) {
            List<WorkerCandidateObservation> workers = acquired.getOrDefault(
                    item.messageId(),
                    List.of()
            );
            if (!workers.isEmpty()
                    && roundWorkerIds.add(workers.get(0).workerId())) {
                result.add(new TaskItemWorkerAssignment(
                        item,
                        workers.get(0)
                ));
            }
        }
        return List.copyOf(result);
    }

    private static List<TaskItemWorkerAssignment> pair(
            List<TaskItemObservation> items,
            List<WorkerCandidateObservation> workers,
            Set<String> roundWorkerIds
    ) {
        List<TaskItemWorkerAssignment> result = new ArrayList<>();
        int count = Math.min(items.size(), workers.size());
        for (int index = 0; index < count; index++) {
            WorkerCandidateObservation worker = workers.get(index);
            if (roundWorkerIds.add(worker.workerId())) {
                result.add(new TaskItemWorkerAssignment(
                        items.get(index),
                        worker
                ));
            }
        }
        return List.copyOf(result);
    }

    private static boolean failed(
            TaskItemObservation item,
            long observedAtMillis
    ) {
        return item.remainingBudget() == 0
                || item.item() != null
                && item.item().expireAtMillis() != null
                && observedAtMillis >= item.item().expireAtMillis();
    }

}
