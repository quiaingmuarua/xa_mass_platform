package com.xa.mass.kernel.task;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed TaskItem result event mechanism used by production Kernel Pacers. */
public final class DefaultTaskItemResultEvents
        implements TaskItemResultEvents {

    private final TaskRuntime taskRuntime;
    private final TaskItemScoreBandCore itemScores;

    public DefaultTaskItemResultEvents(
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScores
    ) {
        this.taskRuntime = Objects.requireNonNull(
                taskRuntime,
                "taskRuntime"
        );
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
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
        taskRuntime.storeTaskItemSuccessResults(taskId, payloads);
        itemScores.promoteItemOutcomes(
                taskId,
                List.copyOf(payloads.keySet()),
                TaskItemScoreBand.FINAL_SUCCESS,
                observedAtMillis
        );
    }

    private static LinkedHashMap<String, String> copyPayloads(
            Map<String, String> source
    ) {
        Objects.requireNonNull(source, "payloadsByMessageId");
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
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
