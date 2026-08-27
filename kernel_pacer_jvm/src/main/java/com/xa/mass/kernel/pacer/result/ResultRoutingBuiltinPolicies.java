package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

final class ResultRoutingBuiltinPolicies {

    private final TaskRuntime taskRuntime;
    private final TaskItemScoreBandCore itemScore;
    private final WorkerScoreCore workerScore;
    private final LongSupplier currentTimeMillis;

    ResultRoutingBuiltinPolicies(
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore,
            LongSupplier currentTimeMillis
    ) {
        this.taskRuntime = java.util.Objects.requireNonNull(
                taskRuntime,
                "taskRuntime"
        );
        this.itemScore = java.util.Objects.requireNonNull(
                itemScore,
                "itemScore"
        );
        this.workerScore = java.util.Objects.requireNonNull(
                workerScore,
                "workerScore"
        );
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    void handleTaskSuccess(
            Map<String, List<TaskResultEvidence>> resultsByTask,
            long resultTimeMillis
    ) {
        resultsByTask.forEach((taskId, evidence) -> {
            LinkedHashMap<String, String> payloads = new LinkedHashMap<>();
            for (TaskResultEvidence result : evidence) {
                payloads.put(
                        result.messageId(),
                        result.opaqueResultPayload()
                );
            }
            taskRuntime.storeTaskItemSuccessResults(taskId, payloads);
            itemScore.promoteItemOutcomes(
                    taskId,
                    List.copyOf(payloads.keySet()),
                    TaskItemScoreBand.FINAL_SUCCESS,
                    resultTimeMillis
            );
        });
    }

    void handleWorkerResults(
            TaskResultClass resultClass,
            Map<String, List<WorkerResultEvidence>> resultsByWorkerGroup
    ) {
        resultsByWorkerGroup.forEach((workerGroupId, evidence) -> {
            LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
            for (WorkerResultEvidence result : evidence) {
                scores.put(result.workerId(), result.workerLeaseScore());
            }
            long releaseTimeMillis = currentTimeMillis.getAsLong();
            if (resultClass == TaskResultClass.SUCCESS) {
                workerScore.releaseCompletedHotScoreHolds(
                        workerGroupId,
                        scores,
                        releaseTimeMillis
                );
            } else {
                workerScore.releaseScoreHolds(
                        workerGroupId,
                        scores,
                        releaseTimeMillis
                );
            }
        });
    }
}
