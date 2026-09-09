package com.xa.mass.kernel.task;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Semantic Mechanism port for bounded TaskItem result events. */
public interface TaskItemResultEvents {

    void onItemsSucceeded(
            String taskId,
            Map<String, String> payloadsByMessageId,
            long observedAtMillis
    );

    void onItemOutcomesObserved(
            String taskId,
            List<TaskItemOutcomeObservation> observations
    );

    record TaskItemOutcomeObservation(
            String messageId,
            int tag,
            long observedAtMillis,
            @Nullable String opaqueResultPayload
    ) {
        public TaskItemOutcomeObservation {
            if (messageId == null || messageId.isBlank()
                    || tag < TaskItemScoreBandCore.MIN_TERMINAL_TAG
                    || tag > TaskItemScoreBandCore.MAX_TERMINAL_TAG
                    || observedAtMillis <= 0
                    || observedAtMillis > TaskItemScoreBandCore.MAX_TIME_MILLIS
                    || opaqueResultPayload != null && opaqueResultPayload.isBlank()) {
                throw new IllegalArgumentException("TaskItem outcome observation is invalid");
            }
        }
    }
}
