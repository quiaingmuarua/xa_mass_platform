package com.xa.mass.integration.workerlab;

import com.xa.mass.integration.workerlab.RuntimeApiClient.CallStatus;
import com.xa.mass.integration.workerlab.RuntimeApiClient.TaskItem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ConvergenceWorkload {

    static final int ITEMS_PER_GROUP_PER_WAVE = 50;
    static final int INVALID_ITEMS_PER_GROUP_PER_WAVE = 5;
    static final long BACKGROUND_DELAY_MILLIS = 10_000L;
    private static final long CALL_WAIT_MILLIS = 250L;

    private static final List<GroupWorkload> GROUPS = List.of(
            new GroupWorkload(
                    WorkerLabConvergenceSupport.PHONE_GROUP,
                    "phone",
                    WorkerLabConvergenceSupport.PHONE_EVENT,
                    "rawNumber",
                    List.of("+14155552671", "+442071838750")
            ),
            new GroupWorkload(
                    WorkerLabConvergenceSupport.STRING_GROUP,
                    "string",
                    WorkerLabConvergenceSupport.STRING_EVENT,
                    "value",
                    List.of("worker-lab-a", "worker-lab-b")
            )
    );

    private final RuntimeApiClient runtime;
    private final String messagePrefix;
    private final List<Batch> batches;
    private final Map<String, CallStatus> immediateWitnessStatuses =
            new LinkedHashMap<>();

    ConvergenceWorkload(RuntimeApiClient runtime, String messagePrefix) {
        this(runtime, messagePrefix, List.of());
    }

    ConvergenceWorkload(
            RuntimeApiClient runtime,
            String messagePrefix,
            List<Batch> existing
    ) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
        if (messagePrefix == null || messagePrefix.isBlank()) {
            throw new IllegalArgumentException("messagePrefix must be non-blank");
        }
        this.messagePrefix = messagePrefix;
        batches = new ArrayList<>(existing);
        requireUniqueMessageIds(batches);
    }

    List<Batch> submitWave(
            String wave,
            Map<String, Map<String, Object>> rulesByGroup,
            Checkpoint checkpoint
    ) {
        return submitWave(wave, rulesByGroup, checkpoint, false);
    }

    List<Batch> submitCheckpointWave(
            String wave,
            Map<String, Map<String, Object>> rulesByGroup,
            Checkpoint checkpoint
    ) {
        java.util.Objects.requireNonNull(checkpoint, "checkpoint");
        return submitWave(wave, rulesByGroup, checkpoint, true);
    }

    private List<Batch> submitWave(
            String wave,
            Map<String, Map<String, Object>> rulesByGroup,
            Checkpoint checkpoint,
            boolean applyRuleToWholeBatch
    ) {
        if (wave == null || wave.isBlank()
                || batches.stream().anyMatch(batch -> batch.wave().equals(wave))) {
            throw new IllegalArgumentException("wave must be new and non-blank");
        }
        List<Batch> submitted = new ArrayList<>();
        for (GroupWorkload group : GROUPS) {
            String taskId = RuntimeApiClient.managedTaskId(group.groupId());
            List<TaskItem> items = items(
                    wave,
                    group,
                    rulesByGroup.getOrDefault(group.groupId(), Map.of()),
                    checkpoint,
                    applyRuleToWholeBatch
            );
            Map<String, CallStatus> statuses = runtime.callItems(
                    taskId,
                    items,
                    CALL_WAIT_MILLIS
            );
            String witnessMessageId = items.get(0).messageId();
            Batch batch = new Batch(
                    wave,
                    group.groupId(),
                    taskId,
                    ids(items),
                    witnessMessageId,
                    INVALID_ITEMS_PER_GROUP_PER_WAVE
            );
            batches.add(batch);
            submitted.add(batch);
            immediateWitnessStatuses.put(
                    witnessMessageId,
                    statuses.get(witnessMessageId)
            );
        }
        requireUniqueMessageIds(batches);
        return List.copyOf(submitted);
    }

    void awaitWitness(Batch batch, Duration maximumWait) {
        WorkerLabConvergenceSupport.await(
                "witness-" + batch.wave() + "-" + batch.workerGroupId(),
                maximumWait,
                () -> runtime.loadResultStatuses(
                        batch.taskId(),
                        List.of(batch.witnessMessageId())
                ),
                observed -> observed.get(batch.witnessMessageId())
                        == CallStatus.SUCCEEDED,
                observed -> batch.witnessMessageId() + "="
                        + observed.get(batch.witnessMessageId())
        );
    }

    boolean witnessObserved(Batch batch) {
        return runtime.loadResultStatuses(
                batch.taskId(),
                List.of(batch.witnessMessageId())
        ).get(batch.witnessMessageId()) == CallStatus.SUCCEEDED;
    }

    CallStatus immediateWitnessStatus(Batch batch) {
        CallStatus status = immediateWitnessStatuses.get(
                batch.witnessMessageId()
        );
        if (status == null) {
            throw new IllegalArgumentException(
                    "Batch was not submitted by this workload instance"
            );
        }
        return status;
    }

    Batch requireBatch(List<Batch> wave, String workerGroupId) {
        return wave.stream()
                .filter(batch -> batch.workerGroupId().equals(workerGroupId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Wave has no batch for WorkerGroup"
                ));
    }

    Batch requireBatch(
            List<Batch> candidates,
            String workerGroupId,
            String wave
    ) {
        return candidates.stream()
                .filter(batch -> batch.workerGroupId().equals(workerGroupId)
                        && batch.wave().equals(wave))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Workload has no matching witness batch"
                ));
    }

    List<Batch> batches() {
        return List.copyOf(batches);
    }

    int offeredItemCount() {
        return batches.size() * ITEMS_PER_GROUP_PER_WAVE;
    }

    int invalidInputCount() {
        return batches.stream().mapToInt(Batch::invalidInputCount).sum();
    }

    int offeredDelayItemCount() {
        return stringBatchCount();
    }

    int offeredFailItemCount() {
        return stringBatchCount();
    }

    Set<String> witnessMessageIds() {
        return batches.stream()
                .map(Batch::witnessMessageId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private List<TaskItem> items(
            String wave,
            GroupWorkload group,
            Map<String, Object> allocationRule,
            Checkpoint checkpoint,
            boolean applyRuleToWholeBatch
    ) {
        List<TaskItem> items = new ArrayList<>();
        for (int index = 1; index <= ITEMS_PER_GROUP_PER_WAVE; index++) {
            String eventCode = group.eventCode();
            Map<String, Object> payload;
            if (WorkerLabConvergenceSupport.STRING_GROUP.equals(group.groupId())
                    && index == 2) {
                eventCode = WorkerLabConvergenceSupport.DELAY_EVENT;
                payload = Map.of(
                        "delayMillis",
                        BACKGROUND_DELAY_MILLIS
                );
            } else if (WorkerLabConvergenceSupport.STRING_GROUP.equals(
                    group.groupId()) && index == 3) {
                eventCode = WorkerLabConvergenceSupport.FAIL_EVENT;
                payload = Map.of();
            } else if (index % 10 == 0) {
                payload = invalidPayload(group);
            } else {
                payload = Map.of(
                        group.payloadName(),
                        group.inputs().get((index - 1) % group.inputs().size())
                );
            }
            if (checkpoint != null
                    && group.groupId().equals(
                    WorkerLabConvergenceSupport.STRING_GROUP)
                    && index == 1) {
                eventCode = WorkerLabConvergenceSupport.CHECKPOINT_EVENT;
                payload = Map.of("checkpointToken", checkpoint.token());
            }
            items.add(new TaskItem(
                    messagePrefix + "-" + wave + "-" + group.shortName()
                            + "-" + String.format("%03d", index),
                    eventCode,
                    payload,
                    applyRuleToWholeBatch || index == 1
                            ? allocationRule : Map.of()
            ));
        }
        return List.copyOf(items);
    }

    private int stringBatchCount() {
        return Math.toIntExact(batches.stream()
                .filter(batch -> WorkerLabConvergenceSupport.STRING_GROUP
                        .equals(batch.workerGroupId()))
                .count());
    }

    private static Map<String, Object> invalidPayload(GroupWorkload group) {
        if (WorkerLabConvergenceSupport.PHONE_GROUP.equals(group.groupId())) {
            return Map.of(group.payloadName(), "not-a-phone-number");
        }
        return Map.of("unexpected", "invalid");
    }

    private static Set<String> ids(List<TaskItem> items) {
        return items.stream().map(TaskItem::messageId).collect(
                java.util.stream.Collectors.toUnmodifiableSet()
        );
    }

    private static void requireUniqueMessageIds(List<Batch> batches) {
        Set<String> unique = new LinkedHashSet<>();
        for (Batch batch : batches) {
            for (String messageId : batch.messageIds()) {
                if (!unique.add(messageId)) {
                    throw new IllegalArgumentException(
                            "Workload contains duplicate messageId"
                    );
                }
            }
        }
    }

    record Batch(
            String wave,
            String workerGroupId,
            String taskId,
            Set<String> messageIds,
            String witnessMessageId,
            int invalidInputCount
    ) {
        Batch {
            messageIds = Set.copyOf(new LinkedHashSet<>(messageIds));
            if (wave == null || wave.isBlank()
                    || workerGroupId == null || workerGroupId.isBlank()
                    || taskId == null || taskId.isBlank()
                    || witnessMessageId == null || witnessMessageId.isBlank()
                    || messageIds.size() != ITEMS_PER_GROUP_PER_WAVE
                    || !messageIds.contains(witnessMessageId)
                    || invalidInputCount != INVALID_ITEMS_PER_GROUP_PER_WAVE) {
                throw new IllegalArgumentException("Workload batch is invalid");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("wave", wave);
            value.put("workerGroupId", workerGroupId);
            value.put("taskId", taskId);
            value.put("messageIds", messageIds.stream().sorted().toList());
            value.put("witnessMessageId", witnessMessageId);
            value.put("invalidInputCount", invalidInputCount);
            return Collections.unmodifiableMap(value);
        }

        static Batch fromMap(Map<String, Object> value) {
            List<String> messageIds = JsonValues.array(
                    value.get("messageIds"),
                    "messageIds"
            ).stream().map(raw -> {
                if (!(raw instanceof String id) || id.isBlank()) {
                    throw JsonValues.invalid("messageId must be non-blank");
                }
                return id;
            }).toList();
            return new Batch(
                    JsonValues.requiredString(value, "wave"),
                    JsonValues.requiredString(value, "workerGroupId"),
                    JsonValues.requiredString(value, "taskId"),
                    Set.copyOf(messageIds),
                    JsonValues.requiredString(value, "witnessMessageId"),
                    Math.toIntExact(JsonValues.requiredLong(
                            value,
                            "invalidInputCount"
                    ))
            );
        }
    }

    record Checkpoint(String token) {
        Checkpoint {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("checkpoint token is required");
            }
        }
    }

    private record GroupWorkload(
            String groupId,
            String shortName,
            String eventCode,
            String payloadName,
            List<String> inputs
    ) {
        GroupWorkload {
            inputs = List.copyOf(inputs);
        }
    }
}
