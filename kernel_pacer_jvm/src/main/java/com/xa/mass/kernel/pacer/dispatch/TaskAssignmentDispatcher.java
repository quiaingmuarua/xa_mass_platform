package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class TaskAssignmentDispatcher {

    record AssignmentAttempt(
            TaskItem item,
            long observedItemScore,
            HeldWorkerCandidate worker
    ) {
        AssignmentAttempt {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(worker, "worker");
        }
    }

    private final TaskItemScoreBandCore itemScores;
    private final WorkerScoreCore workerScores;
    private final WorkerCommandRuntime workerCommands;
    private final ResultContextCodec resultContextCodec;
    private final JsonMapper mapper = JsonMapper.builder().build();

    TaskAssignmentDispatcher(
            TaskItemScoreBandCore itemScores,
            WorkerScoreCore workerScores,
            WorkerCommandRuntime workerCommands,
            ResultContextCodec resultContextCodec
    ) {
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.workerCommands = Objects.requireNonNull(
                workerCommands,
                "workerCommands"
        );
        this.resultContextCodec = Objects.requireNonNull(
                resultContextCodec,
                "resultContextCodec"
        );
    }

    int dispatch(
            ObservedTask task,
            List<AssignmentAttempt> attempts,
            long claimUntilMillis
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(attempts, "attempts");
        if (attempts.isEmpty()) {
            return 0;
        }

        LinkedHashMap<String, AssignmentAttempt> attemptsByMessageId =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> observedWorkers = new LinkedHashMap<>();
        HashSet<String> workerIds = new HashSet<>();
        for (AssignmentAttempt attempt : attempts) {
            Objects.requireNonNull(attempt, "assignment attempt");
            TaskItem item = attempt.item();
            String messageId = item.messageId();
            HeldWorkerCandidate worker = attempt.worker();
            requireNonBlank(messageId, "messageId");
            if (attemptsByMessageId.putIfAbsent(messageId, attempt) != null) {
                throw new IllegalArgumentException(
                        "Assignments must be unique by messageId"
                );
            }
            if (!task.descriptor().workerGroupId().equals(
                    worker.workerGroupId()
            )) {
                throw new IllegalArgumentException(
                        "Assigned Worker does not belong to the Task group"
                );
            }
            if (!workerIds.add(worker.workerId())) {
                throw new IllegalArgumentException(
                        "Assignments must be unique by workerId"
                );
            }
            observedWorkers.put(
                    worker.workerId(),
                    worker.heldWorkerLeaseScore()
            );
        }

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
                    || result.status()
                    == WorkerScoreTransitionStatus.NOOP)) {
                verifiedScores.put(workerId, result.score());
            }
        });

        LinkedHashMap<String, Long> claimScores = new LinkedHashMap<>();
        attemptsByMessageId.forEach((messageId, attempt) -> {
            HeldWorkerCandidate worker = attempt.worker();
            if (verifiedScores.containsKey(worker.workerId())) {
                claimScores.put(messageId, attempt.observedItemScore());
            }
        });
        if (claimScores.isEmpty()) {
            return 0;
        }
        Map<String, TaskItemScoreBandCore.TaskItemScoreTransitionResult>
                claims = itemScores.rewriteObservedItemScores(
                        task.taskId(),
                        claimScores,
                        claimUntilMillis,
                        -1
                );

        LinkedHashMap<String, Map<String, DeliveryCommand>> byAdapter =
                new LinkedHashMap<>();
        attemptsByMessageId.forEach((messageId, attempt) -> {
            HeldWorkerCandidate worker = attempt.worker();
            var claim = claims.get(messageId);
            Long workerLeaseScore = verifiedScores.get(worker.workerId());
            if (claim == null
                    || claim.status()
                    != TaskItemScoreTransitionStatus.TRANSITIONED
                    || claim.score() == null
                    || workerLeaseScore == null) {
                return;
            }
            TaskItem item = attempt.item();
            DeliveryCommand command = DeliveryCommand.create(
                    DeliveryEndpoint.TASK,
                    DeliveryEndpoint.WORKER,
                    item.eventCode(),
                    claimUntilMillis,
                    encodePayload(item.payload()),
                    resultContextCodec.encode(new ResultContext(
                            task.taskId(),
                            messageId,
                            worker.workerId(),
                            worker.workerGroupId(),
                            workerLeaseScore
                    ))
            );
            byAdapter.computeIfAbsent(
                    worker.endpointManagerId(),
                    ignored -> new LinkedHashMap<>()
            ).put(worker.workerId(), command);
        });

        int published = 0;
        for (Map.Entry<String, Map<String, DeliveryCommand>> adapter
                : byAdapter.entrySet()) {
            Map<String, WorkerCommandAppendStatus> results =
                    workerCommands.appendWorkerCommands(
                            adapter.getKey(),
                            adapter.getValue()
                    );
            published += (int) results.values().stream()
                    .filter(status ->
                            status == WorkerCommandAppendStatus.APPENDED
                                    || status
                                    == WorkerCommandAppendStatus.REPLACED)
                    .count();
        }
        return published;
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

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
