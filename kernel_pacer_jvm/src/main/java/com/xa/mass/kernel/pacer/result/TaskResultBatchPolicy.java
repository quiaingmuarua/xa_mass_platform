package com.xa.mass.kernel.pacer.result;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

final class TaskResultBatchPolicy {

    private final TaskRuntime taskRuntime;
    private final TaskItemScoreBandCore itemScore;
    private final WorkerScoreCore workerScore;
    private final LongSupplier currentTimeMillis;
    private final ResultContextCodec contextCodec;

    TaskResultBatchPolicy(
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore
    ) {
        this(
                taskRuntime,
                itemScore,
                workerScore,
                System::currentTimeMillis,
                new ResultContextCodec()
        );
    }

    TaskResultBatchPolicy(
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore,
            LongSupplier currentTimeMillis,
            ResultContextCodec contextCodec
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
        this.contextCodec = java.util.Objects.requireNonNull(
                contextCodec,
                "contextCodec"
        );
    }

    void handleSuccess(List<DeliveryReport> batch) {
        DecodedBatch decoded = decode(batch, true);
        if (decoded.decodedCount() == 0) {
            return;
        }
        long resultTimeMillis = currentTimeMillis.getAsLong();
        decoded.resultsByTask().forEach((taskId, evidence) -> {
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
        releaseWorkers(decoded.resultsByWorkerGroup(), true);
    }

    void handleFailure(List<DeliveryReport> batch) {
        DecodedBatch decoded = decode(batch, false);
        if (decoded.decodedCount() == 0) {
            return;
        }
        releaseWorkers(decoded.resultsByWorkerGroup(), false);
    }

    private DecodedBatch decode(
            List<DeliveryReport> batch,
            boolean includeTaskEvidence
    ) {
        java.util.Objects.requireNonNull(batch, "batch");
        int decodedCount = 0;
        LinkedHashMap<String, List<TaskResultEvidence>> resultsByTask =
                new LinkedHashMap<>();
        LinkedHashMap<String, List<WorkerResultEvidence>>
                resultsByWorkerGroup = new LinkedHashMap<>();
        for (DeliveryReport result : batch) {
            if (result == null || result.dst() != DeliveryEndpoint.TASK) {
                continue;
            }
            Optional<ResultContext> context = contextCodec.decode(
                    result.forward()
            );
            if (context.isEmpty()) {
                continue;
            }
            ResultContext value = context.get();
            decodedCount++;
            if (includeTaskEvidence) {
                resultsByTask.computeIfAbsent(
                        value.taskId(),
                        ignored -> new ArrayList<>()
                ).add(new TaskResultEvidence(
                        value.taskId(),
                        value.messageId(),
                        result.payload()
                ));
            }
            resultsByWorkerGroup.computeIfAbsent(
                    value.workerGroupId(),
                    ignored -> new ArrayList<>()
            ).add(new WorkerResultEvidence(
                    value.workerId(),
                    value.workerLeaseScore()
            ));
        }
        return new DecodedBatch(
                decodedCount,
                resultsByTask,
                resultsByWorkerGroup
        );
    }

    private void releaseWorkers(
            Map<String, List<WorkerResultEvidence>> resultsByWorkerGroup,
            boolean completed
    ) {
        resultsByWorkerGroup.forEach((workerGroupId, evidence) -> {
            LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
            for (WorkerResultEvidence result : evidence) {
                scores.put(result.workerId(), result.workerLeaseScore());
            }
            long releaseTimeMillis = currentTimeMillis.getAsLong();
            if (completed) {
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

    private record DecodedBatch(
            int decodedCount,
            Map<String, List<TaskResultEvidence>> resultsByTask,
            Map<String, List<WorkerResultEvidence>> resultsByWorkerGroup
    ) {
    }
}
