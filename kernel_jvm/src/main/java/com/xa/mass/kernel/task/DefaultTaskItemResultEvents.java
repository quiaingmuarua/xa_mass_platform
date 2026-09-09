package com.xa.mass.kernel.task;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemOutcomeTarget;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemSuccessResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed TaskItem result event mechanism used by production Kernel Pacers. */
public final class DefaultTaskItemResultEvents
        implements TaskItemResultEvents {

    private final TaskRuntime taskRuntime;
    private final TaskItemScoreBandCore itemScores;
    private final int successOutcomeTag;

    public DefaultTaskItemResultEvents(
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScores,
            int successOutcomeTag
    ) {
        this.taskRuntime = Objects.requireNonNull(
                taskRuntime,
                "taskRuntime"
        );
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        if (successOutcomeTag < TaskItemScoreBandCore.MIN_TERMINAL_TAG
                || successOutcomeTag > TaskItemScoreBandCore.MAX_TERMINAL_TAG) {
            throw new IllegalArgumentException("successOutcomeTag must be in 2..9");
        }
        this.successOutcomeTag = successOutcomeTag;
    }

    @Override
    public void onItemsSucceeded(
            String taskId,
            Map<String, String> payloadsByMessageId,
            long observedAtMillis
    ) {
        requireTaskId(taskId);
        requireObservedAt(observedAtMillis);
        LinkedHashMap<String, String> payloads = copyPayloads(
                payloadsByMessageId
        );
        if (payloads.isEmpty()) {
            return;
        }
        Map<String, TaskItemSuccessResult> results = new LinkedHashMap<>();
        Map<String, TaskItemOutcomeTarget> targets = new LinkedHashMap<>();
        payloads.forEach((id, payload) -> {
            results.put(id, new TaskItemSuccessResult(successOutcomeTag, observedAtMillis, payload));
            targets.put(id, new TaskItemOutcomeTarget(successOutcomeTag, observedAtMillis));
        });
        long started = TaskStageEvent.start();
        boolean stored = false;
        try {
            taskRuntime.storeTaskItemSuccessResults(taskId, results);
            stored = true;
        } finally {
            TaskStageEvent.items(started, "RESULT_STORED", taskId, payloads.keySet(), stored ? payloads.size() : 0, !stored);
        }
        itemScores.promoteItemOutcomes(taskId, targets);
    }

    @Override
    public void onItemOutcomesObserved(String taskId, List<TaskItemOutcomeObservation> observations) {
        requireTaskId(taskId);
        Objects.requireNonNull(observations, "observations");
        if (observations.size() > TaskItemScoreBandCore.MAX_ITEM_BATCH_SIZE) {
            throw new IllegalArgumentException("At most 100 observations are accepted");
        }
        Map<String, TaskItemOutcomeTarget> targets = new LinkedHashMap<>();
        Map<String, TaskItemSuccessResult> contents = new LinkedHashMap<>();
        for (TaskItemOutcomeObservation observation : observations) {
            Objects.requireNonNull(observation, "observation");
            var target = new TaskItemOutcomeTarget(observation.tag(), observation.observedAtMillis());
            targets.merge(observation.messageId(), target, (old, next) ->
                    newer(next.tag(), next.timeMillis(), old.tag(), old.timeMillis()) ? next : old);
            if (observation.opaqueResultPayload() != null) {
                var content = new TaskItemSuccessResult(observation.tag(),
                        observation.observedAtMillis(), observation.opaqueResultPayload());
                contents.merge(observation.messageId(), content, (old, next) ->
                        newer(next.tag(), next.observedAtMillis(), old.tag(), old.observedAtMillis()) ? next : old);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        var promoted = itemScores.promoteItemOutcomes(taskId, targets);
        contents.entrySet().removeIf(entry -> {
            var result = promoted.get(entry.getKey());
            return result == null || switch (result.status()) {
                case TRANSITIONED, NOOP -> false;
                default -> true;
            };
        });
        if (!contents.isEmpty()) {
            taskRuntime.storeTaskItemSuccessResults(taskId, contents);
        }
    }

    private static boolean newer(int tag, long time, int oldTag, long oldTime) {
        return tag > oldTag || tag == oldTag && time > oldTime;
    }

    private static LinkedHashMap<String, String> copyPayloads(
            Map<String, String> source
    ) {
        Objects.requireNonNull(source, "payloadsByMessageId");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        if (source.size() > TaskItemScoreBandCore.MAX_ITEM_BATCH_SIZE) {
            throw new IllegalArgumentException("At most 100 results are accepted");
        }
        source.forEach((messageId, payload) -> {
            requireNonBlank(messageId, "messageId");
            copied.put(messageId, Objects.requireNonNull(payload, "payload"));
        });
        return copied;
    }

    private static void requireTaskId(String taskId) {
        requireNonBlank(taskId, "taskId");
    }

    private static void requireObservedAt(long observedAtMillis) {
        if (observedAtMillis <= 0) {
            throw new IllegalArgumentException(
                    "observedAtMillis must be positive"
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
