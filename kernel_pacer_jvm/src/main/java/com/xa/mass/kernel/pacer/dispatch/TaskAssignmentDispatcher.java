package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
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
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class TaskAssignmentDispatcher {

    record AssignmentAttempt(
            TaskItem item,
            long observedItemScore,
            RoutedWorkerCandidate worker
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
    private final Consumer<WorkerObservation> workerObservations;
    private final JsonMapper mapper = JsonMapper.builder().build();

    TaskAssignmentDispatcher(
            TaskItemScoreBandCore itemScores,
            WorkerScoreCore workerScores,
            WorkerCommandRuntime workerCommands,
            ResultContextCodec resultContextCodec,
            Consumer<WorkerObservation> workerObservations
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
        this.workerObservations = Objects.requireNonNull(workerObservations, "workerObservations");
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
        List<String> currentWorkers = new ArrayList<>();
        HashSet<String> workerIds = new HashSet<>();
        for (AssignmentAttempt attempt : attempts) {
            Objects.requireNonNull(attempt, "assignment attempt");
            TaskItem item = attempt.item();
            String messageId = item.messageId();
            RoutedWorkerCandidate worker = attempt.worker();
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
            if (worker.expectedScore() == 0) {
                currentWorkers.add(worker.workerId());
            } else {
                observedWorkers.put(worker.workerId(), worker.expectedScore());
            }
        }

        long confirmedAt = DispatchStageEvent.start();
        Map<String, WorkerScoreTransitionResult> verified = new LinkedHashMap<>();
        if (!observedWorkers.isEmpty()) {
            verified.putAll(workerScores.acquireObservedHotScoreLeases(
                        task.descriptor().workerGroupId(),
                        observedWorkers,
                        claimUntilMillis
                ));
        }
        if (!currentWorkers.isEmpty()) {
            verified.putAll(workerScores.acquireCurrentHotScoreLeases(
                    task.descriptor().workerGroupId(), currentWorkers, claimUntilMillis));
        }
        LinkedHashMap<String, Long> verifiedScores = new LinkedHashMap<>();
        verified.forEach((workerId, result) -> {
            if (result.score() != null
                    && result.status() == WorkerScoreTransitionStatus.TRANSITIONED) {
                verifiedScores.put(workerId, result.score());
            }
        });
        DispatchStageEvent.batch(confirmedAt, "WORKER_CONFIRM", workerIds.size(), verifiedScores.size(), false);
        DispatchStageEvent.batch(confirmedAt, "WORKER_CONFIRM_REJECTED", workerIds.size(), workerIds.size()-verifiedScores.size(), false);

        LinkedHashMap<String, Long> claimScores = new LinkedHashMap<>();
        attemptsByMessageId.forEach((messageId, attempt) -> {
            RoutedWorkerCandidate worker = attempt.worker();
            if (verifiedScores.containsKey(worker.workerId())) {
                claimScores.put(messageId, attempt.observedItemScore());
            }
        });
        if (claimScores.isEmpty()) {
            return 0;
        }
        long claimedAt = DispatchStageEvent.start();
        Map<String, TaskItemScoreBandCore.TaskItemScoreTransitionResult>
                claims = itemScores.rewriteObservedItemScores(
                        task.taskId(),
                        claimScores,
                        claimUntilMillis,
                        -1
                );
        long observedAtMillis = System.currentTimeMillis();
        List<AssignmentAttempt> assigned = new ArrayList<>();
        LinkedHashMap<String, List<String>> workersByEvent = new LinkedHashMap<>();
        attemptsByMessageId.forEach((messageId, attempt) -> {
            var claim = claims.get(messageId);
            if (claim != null && claim.status() == TaskItemScoreTransitionStatus.TRANSITIONED
                    && claim.score() != null && verifiedScores.containsKey(attempt.worker().workerId())) {
                assigned.add(attempt);
                workersByEvent.computeIfAbsent(attempt.item().eventCode(), ignored -> new ArrayList<>())
                        .add(attempt.worker().workerId());
            }
        });
        workersByEvent.forEach((event, workers) -> {
            try {
                workerObservations.accept(new WorkerObservation(task.descriptor().workerGroupId(),
                        workers, observedAtMillis, event, "worker.assigned"));
            } catch (RuntimeException ignored) {
                // An observation gap must not prevent already-claimed Commands from being published.
                DispatchStageEvent.batch(claimedAt, "WORKER_OBSERVATION_REJECTED", workers.size(), 0, true);
            }
        });
        if (claimedAt != 0) {
            var claimedIds = claims.entrySet().stream().filter(e -> e.getValue().status() == TaskItemScoreTransitionStatus.TRANSITIONED
                    && e.getValue().score() != null).map(Map.Entry::getKey).toList();
            DispatchStageEvent.batch(claimedAt, "ITEM_CLAIM", claimScores.size(), claimedIds.size(), false);
            DispatchStageEvent.items(claimedAt, "CLAIMED", task.taskId(), claimedIds, claimedIds.size(), false);
        }

        LinkedHashMap<String, Map<String, DeliveryCommand>> byAdapter =
                new LinkedHashMap<>();
        assigned.forEach(attempt -> {
            RoutedWorkerCandidate worker = attempt.worker();
            String messageId = attempt.item().messageId();
            Long workerLeaseScore = verifiedScores.get(worker.workerId());
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
            long appendedAt = DispatchStageEvent.start();
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
            if (appendedAt != 0) {
                var publishedIds = attemptsByMessageId.entrySet().stream().filter(e -> {
                    var status = results.get(e.getValue().worker().workerId());
                    return status == WorkerCommandAppendStatus.APPENDED || status == WorkerCommandAppendStatus.REPLACED;
                }).map(Map.Entry::getKey).toList();
                DispatchStageEvent.batch(appendedAt, "COMMAND_APPEND", adapter.getValue().size(), publishedIds.size(), false);
                DispatchStageEvent.items(appendedAt, "COMMAND_PUBLISHED", task.taskId(), publishedIds, publishedIds.size(), false);
            }
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
