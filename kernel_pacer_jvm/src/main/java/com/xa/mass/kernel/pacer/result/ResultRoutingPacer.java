package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.ResultContext;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

final class ResultRoutingPacer {

    private static final List<TaskResultClass> RESULT_CLASSES = List.of(
            TaskResultClass.SUCCESS,
            TaskResultClass.FAILURE
    );

    private final TaskResultRuntime taskResultRuntime;
    private final ResultRoutingBuiltinPolicies policies;
    private final ResultContextCodec contextCodec;
    private final LongSupplier currentTimeMillis;

    public ResultRoutingPacer(
            TaskResultRuntime taskResultRuntime,
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore
    ) {
        this(
                taskResultRuntime,
                taskRuntime,
                itemScore,
                workerScore,
                System::currentTimeMillis,
                new ResultContextCodec()
        );
    }

    ResultRoutingPacer(
            TaskResultRuntime taskResultRuntime,
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore,
            LongSupplier currentTimeMillis,
            ResultContextCodec contextCodec
    ) {
        this.taskResultRuntime = java.util.Objects.requireNonNull(
                taskResultRuntime,
                "taskResultRuntime"
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
        for (TaskResultClass resultClass : RESULT_CLASSES) {
            DecodedBatch batch = consumeDecoded(
                    resultClass,
                    config.perResultClassBatchLimit()
            );
            if (batch.decodedCount() == 0) {
                continue;
            }
            if (resultClass == TaskResultClass.SUCCESS) {
                policies.handleTaskSuccess(
                        batch.resultsByTask(),
                        resultTimeMillis
                );
            }
            policies.handleWorkerResults(
                    resultClass,
                    batch.resultsByWorkerGroup()
            );
            routedCount += batch.decodedCount();
        }
        return routedCount;
    }

    private DecodedBatch consumeDecoded(
            TaskResultClass resultClass,
            int limit
    ) {
        List<DeliveryReport> results =
                taskResultRuntime.consumeTaskResults(
                        resultClass,
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
                    || decoded.isEmpty()) {
                continue;
            }
            ResultContext context = decoded.get();
            decodedCount++;
            if (resultClass == TaskResultClass.SUCCESS) {
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
