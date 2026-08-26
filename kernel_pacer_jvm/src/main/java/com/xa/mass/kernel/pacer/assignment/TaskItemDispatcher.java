package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.kernel.score.TaskItemScoreBandCore
        .TaskItemScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

final class TaskItemDispatcher {

    private final TaskItemScoreBandCore itemScore;
    private final TaskRuntime taskRuntime;
    private final WorkerCandidateAcquirer candidateAcquirer;
    private final CandidateWarmupSchedule warmups;
    private final ResultContextCodec resultContextCodec;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public TaskItemDispatcher(
            TaskItemScoreBandCore itemScore,
            TaskRuntime taskRuntime,
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWarmupSchedule warmups,
            ResultContextCodec resultContextCodec
    ) {
        this.itemScore = java.util.Objects.requireNonNull(
                itemScore,
                "itemScore"
        );
        this.taskRuntime = java.util.Objects.requireNonNull(
                taskRuntime,
                "taskRuntime"
        );
        this.candidateAcquirer = java.util.Objects.requireNonNull(
                candidateAcquirer,
                "candidateAcquirer"
        );
        this.warmups = java.util.Objects.requireNonNull(warmups, "warmups");
        this.resultContextCodec = java.util.Objects.requireNonNull(
                resultContextCodec,
                "resultContextCodec"
        );
    }

    public List<ClaimableTaskItem> observeClaimableTaskItems(
            String taskId,
            int limit,
            long observedAtMillis
    ) {
        Map<String, TaskItemScoreBandCore.TaskItemScoreObservation>
                observations = itemScore.acquireItemScoreCandidates(
                        taskId,
                        limit
                );
        if (observations.isEmpty()) {
            return List.of();
        }
        List<String> exhausted = new ArrayList<>();
        LinkedHashMap<String, Long> claimable = new LinkedHashMap<>();
        observations.forEach((messageId, observation) -> {
            if (observation.remainingBudget() == 0) {
                exhausted.add(messageId);
            } else {
                claimable.put(messageId, observation.score());
            }
        });
        Map<String, TaskItem> items = claimable.isEmpty()
                ? Map.of()
                : taskRuntime.loadTaskItems(
                        taskId,
                        List.copyOf(claimable.keySet())
                );
        List<String> expired = new ArrayList<>();
        claimable.keySet().forEach(messageId -> {
            TaskItem item = items.get(messageId);
            if (item != null
                    && item.expireAtMillis() != null
                    && observedAtMillis >= item.expireAtMillis()) {
                expired.add(messageId);
            }
        });
        List<String> failed = new ArrayList<>(exhausted);
        failed.addAll(expired);
        if (!failed.isEmpty()) {
            itemScore.promoteItemOutcomes(
                    taskId,
                    failed,
                    TaskItemScoreBand.FINAL_FAILED,
                    observedAtMillis
            );
        }
        LinkedHashSet<String> expiredSet = new LinkedHashSet<>(expired);
        List<ClaimableTaskItem> result = new ArrayList<>();
        claimable.forEach((messageId, score) -> {
            TaskItem item = items.get(messageId);
            if (item != null && !expiredSet.contains(messageId)) {
                result.add(new ClaimableTaskItem(item, score));
            }
        });
        return List.copyOf(result);
    }

    public Map<String, Map<String, DeliveryCommand>> dispatchTaskItems(
            String taskId,
            TaskDescriptor descriptor,
            List<ClaimableTaskItem> claimableItems,
            long claimUntilMillis,
            long warmupDueTimeMillis
    ) {
        Map<String, CandidateWorkerEntry> candidates = acquireCandidates(
                taskId,
                descriptor,
                claimableItems,
                claimUntilMillis,
                warmupDueTimeMillis
        );
        if (candidates.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Long> candidateBackedScores =
                new LinkedHashMap<>();
        claimableItems.forEach(item -> {
            if (candidates.containsKey(item.item().messageId())) {
                candidateBackedScores.put(
                        item.item().messageId(),
                        item.observedScore()
                );
            }
        });
        Map<String, TaskItemScoreBandCore.TaskItemScoreTransitionResult>
                claims = itemScore.rewriteObservedItemScores(
                        taskId,
                        candidateBackedScores,
                        claimUntilMillis,
                        -1
                );
        LinkedHashMap<String, Map<String, DeliveryCommand>> commands =
                new LinkedHashMap<>();
        for (ClaimableTaskItem claimable : claimableItems) {
            String messageId = claimable.item().messageId();
            var result = claims.get(messageId);
            CandidateWorkerEntry candidate = candidates.get(messageId);
            if (candidate == null
                    || result == null
                    || result.status()
                    != TaskItemScoreTransitionStatus.TRANSITIONED
                    || result.score() == null) {
                continue;
            }
            DeliveryCommand command = DeliveryCommand.create(
                    DeliveryEndpoint.TASK,
                    DeliveryEndpoint.WORKER,
                    claimable.item().eventCode(),
                    claimUntilMillis,
                    encodePayload(claimable.item().payload()),
                    resultContextCodec.encode(new ResultContext(
                            taskId,
                            messageId,
                            candidate.workerId(),
                            candidate.workerGroupId(),
                            candidate.workerLeaseScore()
                    ))
            );
            commands.computeIfAbsent(
                    candidate.endpointManagerId(),
                    ignored -> new LinkedHashMap<>()
            ).put(candidate.workerId(), command);
        }
        return commands;
    }

    private Map<String, CandidateWorkerEntry> acquireCandidates(
            String taskId,
            TaskDescriptor descriptor,
            List<ClaimableTaskItem> claimableItems,
            long leaseUntilMillis,
            long warmupDueTimeMillis
    ) {
        int priority = Integer.parseInt(
                descriptor.config().get("priority")
        );
        if (descriptor.workerAllocationMechanism()
                == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE) {
            Map<String, List<CandidateWorkerEntry>> acquired =
                    candidateAcquirer.acquireWorkerCandidates(
                            WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
                            descriptor.workerGroupId(),
                            Map.of(taskId, new WorkerCandidateRequest(
                                    priority,
                                    claimableItems.size(),
                                    java.util.Objects.requireNonNull(
                                            descriptor.allocationRule()
                                    )
                            )),
                            leaseUntilMillis
                    );
            warmups.scheduleCandidateWarmups(
                    List.of(taskId),
                    warmupDueTimeMillis
            );
            List<CandidateWorkerEntry> entries = acquired.getOrDefault(
                    taskId,
                    List.of()
            );
            LinkedHashMap<String, CandidateWorkerEntry> result =
                    new LinkedHashMap<>();
            for (int index = 0;
                    index < Math.min(entries.size(), claimableItems.size());
                    index++) {
                result.put(
                        claimableItems.get(index).item().messageId(),
                        entries.get(index)
                );
            }
            return result;
        }

        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        claimableItems.forEach(claimable -> requests.put(
                claimable.item().messageId(),
                new WorkerCandidateRequest(
                        priority,
                        1,
                        java.util.Objects.requireNonNull(
                                claimable.item().allocationRule()
                        )
                )
        ));
        Map<String, List<CandidateWorkerEntry>> acquired =
                candidateAcquirer.acquireWorkerCandidates(
                        WorkerCandidateAcquisitionStrategy.DIRECT,
                        descriptor.workerGroupId(),
                        requests,
                        leaseUntilMillis
                );
        LinkedHashMap<String, CandidateWorkerEntry> result =
                new LinkedHashMap<>();
        claimableItems.forEach(claimable -> {
            List<CandidateWorkerEntry> entries = acquired.getOrDefault(
                    claimable.item().messageId(),
                    List.of()
            );
            if (!entries.isEmpty()) {
                result.put(claimable.item().messageId(), entries.get(0));
            }
        });
        return result;
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

    record ClaimableTaskItem(TaskItem item, long observedScore) {
        public ClaimableTaskItem {
            java.util.Objects.requireNonNull(item, "item");
        }
    }
}
