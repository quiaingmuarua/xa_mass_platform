package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.TaskItemObservation;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.TaskItemWorkerAssignment;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionStatus;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class DefaultTaskExecutionMechanism
        implements TaskExecutionMechanism {

    private final TaskScoreBandCore taskScores;
    private final TaskItemScoreBandCore itemScores;
    private final WorkerScoreCore workerScores;
    private final TaskRuntime taskRuntime;
    private final WorkerCommandRuntime workerCommands;
    private final ResultContextCodec resultContextCodec;
    private final JsonMapper mapper = JsonMapper.builder().build();

    DefaultTaskExecutionMechanism(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            WorkerScoreCore workerScores,
            TaskRuntime taskRuntime,
            WorkerCommandRuntime workerCommands,
            ResultContextCodec resultContextCodec
    ) {
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.workerCommands = Objects.requireNonNull(
                workerCommands,
                "workerCommands"
        );
        this.resultContextCodec = Objects.requireNonNull(
                resultContextCodec,
                "resultContextCodec"
        );
    }

    @Override
    public List<TaskItemObservation> observeTaskItems(
            String taskId,
            int limit
    ) {
        Map<String, TaskItemScoreBandCore.TaskItemScoreObservation> observed =
                itemScores.acquireItemScoreCandidates(taskId, limit);
        if (observed.isEmpty()) {
            return List.of();
        }
        List<String> loadIds = observed.entrySet().stream()
                .filter(entry -> entry.getValue().remainingBudget() > 0)
                .map(Map.Entry::getKey)
                .toList();
        Map<String, TaskItem> items = loadIds.isEmpty()
                ? Map.of()
                : taskRuntime.loadTaskItems(taskId, loadIds);
        List<TaskItemObservation> result = new ArrayList<>();
        observed.forEach((messageId, score) -> result.add(
                new TaskItemObservation(
                        messageId,
                        score.remainingBudget(),
                        items.get(messageId),
                        new TaskItemReference(
                                taskId,
                                messageId,
                                score.score()
                        )
                )
        ));
        return List.copyOf(result);
    }

    @Override
    public int finalizeFailedItems(
            String taskId,
            List<TaskItemObservation> items,
            long observedAtMillis
    ) {
        List<String> messageIds = List.copyOf(
                Objects.requireNonNull(items, "items")
        ).stream()
                .map(TaskItemObservation::messageId)
                .toList();
        if (messageIds.isEmpty()) {
            return 0;
        }
        return (int) itemScores.promoteItemOutcomes(
                taskId,
                messageIds,
                TaskItemScoreBand.FINAL_FAILED,
                observedAtMillis
        ).values().stream()
                .filter(result -> result.status()
                        == TaskItemScoreTransitionStatus.TRANSITIONED)
                .count();
    }

    @Override
    public int dispatch(
            DueTaskObservation task,
            List<TaskItemWorkerAssignment> assignments,
            long claimUntilMillis
    ) {
        return dispatchSelected(
                task,
                List.copyOf(Objects.requireNonNull(
                        assignments,
                        "assignments"
                )),
                claimUntilMillis
        );
    }

    @Override
    public void onDispatchAttemptFinished(
            DueTaskObservation task,
            long dispatchTimeMillis
    ) {
        taskScores.rewriteSameBandTimeMillis(
                task.taskId(),
                TaskScoreBand.RUNNING_VISIBLE,
                dispatchTimeMillis
        );
    }

    @Override
    public void settleNoClaimableItems(
            DueTaskObservation task,
            IdleAction action,
            long observedAtMillis
    ) {
        Objects.requireNonNull(action, "action");
        boolean active = itemScores.hasActiveItems(
                List.of(task.taskId())
        ).getOrDefault(task.taskId(), false);
        if (active) {
            taskScores.rewriteSameBandTimeMillis(
                    task.taskId(),
                    TaskScoreBand.RUNNING_VISIBLE,
                    observedAtMillis
            );
            return;
        }
        TaskSchedulingReference reference = task.reference();
        requireTaskReference(task.taskId(), reference);
        if (action == IdleAction.CLOSE) {
            taskScores.closeObservedScore(
                    task.taskId(),
                    reference.encodedScore(),
                    TaskScoreBandCore.TERMINAL_SCORE_MAX
            );
            return;
        }
        var parked = taskScores.parkObservedIdleTask(
                task.taskId(),
                reference.encodedScore()
        );
        if (parked.status() != TaskScoreTransitionStatus.TRANSITIONED) {
            return;
        }
        boolean appeared = itemScores.hasActiveItems(
                List.of(task.taskId())
        ).getOrDefault(task.taskId(), false);
        if (appeared) {
            taskScores.tryReleaseIdlePark(task.taskId());
        }
    }

    private int dispatchSelected(
            DueTaskObservation task,
            List<TaskItemWorkerAssignment> assignments,
            long claimUntilMillis
    ) {
        if (assignments.isEmpty()) {
            return 0;
        }
        validateAssignments(task, assignments);
        LinkedHashMap<String, Long> observedWorkers = new LinkedHashMap<>();
        assignments.forEach(assignment -> observedWorkers.put(
                assignment.worker().workerId(),
                assignment.worker().reference().encodedScore()
        ));
        Map<String, WorkerScoreTransitionResult> verified =
                workerScores.renewActiveHotScoreLeases(
                        task.descriptor().workerGroupId(),
                        observedWorkers,
                        claimUntilMillis
                );
        LinkedHashMap<String, Long> verifiedScores = new LinkedHashMap<>();
        verified.forEach((workerId, result) -> {
            if (result.score() != null
                    && (result.status()
                    == WorkerScoreTransitionStatus.TRANSITIONED
                    || result.status() == WorkerScoreTransitionStatus.NOOP)) {
                verifiedScores.put(workerId, result.score());
            }
        });
        LinkedHashMap<String, Long> observedItems = new LinkedHashMap<>();
        assignments.forEach(assignment -> {
            if (verifiedScores.containsKey(assignment.worker().workerId())) {
                TaskItemReference reference = assignment.item().reference();
                requireItemReference(
                        task.taskId(),
                        assignment.item().messageId(),
                        reference
                );
                observedItems.put(
                        assignment.item().messageId(),
                        reference.encodedScore()
                );
            }
        });
        Map<String, TaskItemScoreBandCore.TaskItemScoreTransitionResult>
                claims = itemScores.rewriteObservedItemScores(
                        task.taskId(),
                        observedItems,
                        claimUntilMillis,
                        -1
                );
        LinkedHashMap<String, Map<String, DeliveryCommand>> byAdapter =
                new LinkedHashMap<>();
        for (TaskItemWorkerAssignment assignment : assignments) {
            String messageId = assignment.item().messageId();
            var claim = claims.get(messageId);
            Long workerLeaseScore = verifiedScores.get(
                    assignment.worker().workerId()
            );
            if (claim == null
                    || claim.status()
                    != TaskItemScoreTransitionStatus.TRANSITIONED
                    || claim.score() == null
                    || workerLeaseScore == null) {
                continue;
            }
            TaskItem item = Objects.requireNonNull(
                    assignment.item().item(),
                    "assigned TaskItem"
            );
            DeliveryCommand command = DeliveryCommand.create(
                    DeliveryEndpoint.TASK,
                    DeliveryEndpoint.WORKER,
                    item.eventCode(),
                    claimUntilMillis,
                    encodePayload(item.payload()),
                    resultContextCodec.encode(new ResultContext(
                            task.taskId(),
                            messageId,
                            assignment.worker().workerId(),
                            assignment.worker().workerGroupId(),
                            workerLeaseScore
                    ))
            );
            byAdapter.computeIfAbsent(
                    assignment.worker().descriptor().endpointManagerId(),
                    ignored -> new LinkedHashMap<>()
            ).put(assignment.worker().workerId(), command);
        }
        int published = 0;
        for (Map.Entry<String, Map<String, DeliveryCommand>> adapter
                : byAdapter.entrySet()) {
            Map<String, WorkerCommandAppendStatus> results =
                    workerCommands.appendWorkerCommands(
                            adapter.getKey(),
                            adapter.getValue()
                    );
            published += (int) results.values().stream()
                    .filter(status -> status == WorkerCommandAppendStatus.APPENDED
                            || status == WorkerCommandAppendStatus.REPLACED)
                    .count();
        }
        return published;
    }

    private static void validateAssignments(
            DueTaskObservation task,
            List<TaskItemWorkerAssignment> assignments
    ) {
        requireTaskReference(task.taskId(), task.reference());
        String workerGroupId = task.descriptor().workerGroupId();
        java.util.HashSet<String> messageIds = new java.util.HashSet<>();
        java.util.HashSet<String> workerIds = new java.util.HashSet<>();
        for (TaskItemWorkerAssignment assignment : assignments) {
            TaskItemObservation item = assignment.item();
            WorkerCandidateMechanism.WorkerCandidateObservation worker =
                    assignment.worker();
            requireItemReference(
                    task.taskId(),
                    item.messageId(),
                    item.reference()
            );
            if (!workerGroupId.equals(worker.workerGroupId())
                    || !workerGroupId.equals(
                    worker.reference().workerGroupId()
            )
                    || !worker.workerId().equals(
                    worker.reference().workerId()
            )) {
                throw new IllegalArgumentException(
                        "Assigned Worker does not belong to the Task group"
                );
            }
            if (!messageIds.add(item.messageId())) {
                throw new IllegalArgumentException(
                        "Assignments must be unique by messageId"
                );
            }
            if (!workerIds.add(worker.workerId())) {
                throw new IllegalArgumentException(
                        "Assignments must be unique by workerId"
                );
            }
        }
    }

    private String encodePayload(Map<String, Object> payload) {
        try {
            return mapper.writeValueAsString(normalize(payload));
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "TaskItem payload is not JSON serializable",
                    error
            );
        }
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> result = new TreeMap<>();
            map.forEach((key, child) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings"
                    );
                }
                result.put(stringKey, normalize(child));
            });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(child -> result.add(normalize(child)));
            return result;
        }
        if (value instanceof Double doubleValue
                && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue
                && !Float.isFinite(floatValue)) {
            throw new IllegalArgumentException(
                    "JSON numbers must be finite"
            );
        }
        return value;
    }

    private static void requireTaskReference(
            String taskId,
            TaskSchedulingReference reference
    ) {
        if (!taskId.equals(reference.taskId())) {
            throw new IllegalArgumentException(
                    "Task scheduling reference identity mismatch"
            );
        }
    }

    private static void requireItemReference(
            String taskId,
            String messageId,
            TaskItemReference reference
    ) {
        if (!taskId.equals(reference.taskId())
                || !messageId.equals(reference.messageId())) {
            throw new IllegalArgumentException(
                    "TaskItem reference identity mismatch"
            );
        }
    }
}
