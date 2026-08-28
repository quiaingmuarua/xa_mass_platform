package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

interface TaskExecutionMechanism {

    List<TaskItemObservation> observeTaskItems(String taskId, int limit);

    int finalizeFailedItems(
            String taskId,
            List<TaskItemObservation> items,
            long observedAtMillis
    );

    int dispatch(
            DueTaskObservation task,
            List<TaskItemWorkerAssignment> assignments,
            long claimUntilMillis
    );

    void onDispatchAttemptFinished(
            DueTaskObservation task,
            long dispatchTimeMillis
    );

    void settleNoClaimableItems(
            DueTaskObservation task,
            IdleAction action,
            long observedAtMillis
    );

    enum IdleAction {
        CLOSE,
        PARK
    }

    record TaskItemObservation(
            String messageId,
            int remainingBudget,
            @Nullable TaskItem item,
            TaskItemReference reference
    ) {
        public TaskItemObservation {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException(
                        "messageId must be non-blank"
                );
            }
            Objects.requireNonNull(reference, "reference");
        }
    }

    record TaskItemWorkerAssignment(
            TaskItemObservation item,
            WorkerCandidateObservation worker
    ) {
        public TaskItemWorkerAssignment {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(worker, "worker");
            if (item.item() == null) {
                throw new IllegalArgumentException(
                        "Assigned TaskItem must be present"
                );
            }
        }
    }
}
