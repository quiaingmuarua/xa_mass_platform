package com.xa.mass.kernel.pacer;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReportOutcomeClass;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

final class ResultRoutingPacer {

    private static final List<DeliveryReportOutcomeClass> OUTCOME_CLASSES =
            List.of(
                    DeliveryReportOutcomeClass.SUCCESS,
                    DeliveryReportOutcomeClass.WORKER_FAILURE,
                    DeliveryReportOutcomeClass.ADAPTER_REJECTION
            );

    private final WorkerResultRuntime workerResultRuntime;
    private final ResultRoutingBuiltinPolicies policies;
    private final ResultContextCodec contextCodec;
    private final LongSupplier currentTimeMillis;

    public ResultRoutingPacer(
            WorkerResultRuntime workerResultRuntime,
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore
    ) {
        this(
                workerResultRuntime,
                taskRuntime,
                itemScore,
                workerScore,
                System::currentTimeMillis,
                new ResultContextCodec()
        );
    }

    ResultRoutingPacer(
            WorkerResultRuntime workerResultRuntime,
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore,
            LongSupplier currentTimeMillis,
            ResultContextCodec contextCodec
    ) {
        this.workerResultRuntime = java.util.Objects.requireNonNull(
                workerResultRuntime,
                "workerResultRuntime"
        );
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
        this.contextCodec = java.util.Objects.requireNonNull(
                contextCodec,
                "contextCodec"
        );
        this.policies = new ResultRoutingBuiltinPolicies(
                taskRuntime,
                itemScore,
                workerScore,
                currentTimeMillis
        );
    }

    int routeWorkerResults(ResultRoutingConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        long resultTimeMillis = currentTimeMillis.getAsLong();
        int routedCount = 0;
        for (DeliveryReportOutcomeClass outcomeClass : OUTCOME_CLASSES) {
            DecodedBatch batch = consumeDecoded(
                    outcomeClass,
                    config.perOutcomeBatchLimit()
            );
            if (batch.decodedCount() == 0) {
                continue;
            }
            if (outcomeClass == DeliveryReportOutcomeClass.SUCCESS) {
                policies.handleTaskSuccess(
                        batch.resultsByTask(),
                        resultTimeMillis
                );
            }
            policies.handleWorkerResults(
                    outcomeClass,
                    batch.resultsByWorkerGroup()
            );
            routedCount += batch.decodedCount();
        }
        return routedCount;
    }

    private DecodedBatch consumeDecoded(
            DeliveryReportOutcomeClass outcomeClass,
            int limit
    ) {
        List<DeliveryReport> results =
                workerResultRuntime.consumeWorkerResults(
                        outcomeClass,
                        limit
                );
        int decodedCount = 0;
        LinkedHashMap<String, List<TaskResultEvidence>> resultsByTask =
                new LinkedHashMap<>();
        LinkedHashMap<String, List<WorkerResultEvidence>>
                resultsByWorkerGroup = new LinkedHashMap<>();
        for (DeliveryReport result : results) {
            Optional<ResultContext> decoded = contextCodec.decode(
                    result.forward()
            );
            if (result.dst() != DeliveryEndpoint.TASK
                    || decoded.isEmpty()
                    || classifyDeliveryReportOutcomeCode(
                            result.outcomeCode()
                    ) != outcomeClass) {
                continue;
            }
            ResultContext context = decoded.get();
            decodedCount++;
            if (outcomeClass == DeliveryReportOutcomeClass.SUCCESS) {
                resultsByTask.computeIfAbsent(
                        context.taskId(),
                        ignored -> new java.util.ArrayList<>()
                ).add(new TaskResultEvidence(
                        context.taskId(),
                        context.messageId(),
                        result.payload()
                ));
            }
            resultsByWorkerGroup.computeIfAbsent(
                    context.workerGroupId(),
                    ignored -> new java.util.ArrayList<>()
            ).add(new WorkerResultEvidence(
                    context.workerId(),
                    context.workerLeaseScore()
            ));
        }
        return new DecodedBatch(
                decodedCount,
                resultsByTask,
                resultsByWorkerGroup
        );
    }

    private record DecodedBatch(
            int decodedCount,
            Map<String, List<TaskResultEvidence>> resultsByTask,
            Map<String, List<WorkerResultEvidence>> resultsByWorkerGroup
    ) {
    }
}
