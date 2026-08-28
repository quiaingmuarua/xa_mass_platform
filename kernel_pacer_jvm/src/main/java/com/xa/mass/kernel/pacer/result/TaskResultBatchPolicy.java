package com.xa.mass.kernel.pacer.result;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.ResultContextCodec.RoutedResultContext;
import com.xa.mass.kernel.task.TaskItemResultEvents;
import com.xa.mass.kernel.worker.WorkerExecutionResultEvents;
import com.xa.mass.kernel.worker.WorkerLeaseReference;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

final class TaskResultBatchPolicy {

    private final TaskItemResultEvents taskItemEvents;
    private final WorkerExecutionResultEvents workerEvents;
    private final LongSupplier currentTimeMillis;
    private final ResultContextCodec contextCodec;

    TaskResultBatchPolicy(
            TaskItemResultEvents taskItemEvents,
            WorkerExecutionResultEvents workerEvents
    ) {
        this(
                taskItemEvents,
                workerEvents,
                System::currentTimeMillis,
                new ResultContextCodec()
        );
    }

    TaskResultBatchPolicy(
            TaskItemResultEvents taskItemEvents,
            WorkerExecutionResultEvents workerEvents,
            LongSupplier currentTimeMillis,
            ResultContextCodec contextCodec
    ) {
        this.taskItemEvents = java.util.Objects.requireNonNull(
                taskItemEvents,
                "taskItemEvents"
        );
        this.workerEvents = java.util.Objects.requireNonNull(
                workerEvents,
                "workerEvents"
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
        DecodedBatch decoded = decode(batch);
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
            taskItemEvents.onItemsSucceeded(
                    taskId,
                    payloads,
                    resultTimeMillis
            );
        });
        publishWorkerEvents(decoded.resultsByWorkerGroup(), true);
    }

    void handleFailure(List<DeliveryReport> batch) {
        DecodedBatch decoded = decode(batch);
        if (decoded.decodedCount() == 0) {
            return;
        }
        long resultTimeMillis = currentTimeMillis.getAsLong();
        decoded.resultsByTask().forEach((taskId, evidence) -> {
            LinkedHashSet<String> messageIds = new LinkedHashSet<>();
            for (TaskResultEvidence result : evidence) {
                messageIds.add(result.messageId());
            }
            taskItemEvents.onItemsFailed(
                    taskId,
                    List.copyOf(messageIds),
                    resultTimeMillis
            );
        });
        publishWorkerEvents(decoded.resultsByWorkerGroup(), false);
    }

    private DecodedBatch decode(List<DeliveryReport> batch) {
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
            Optional<RoutedResultContext> context =
                    contextCodec.decodeForRouting(
                            result.forward()
                    );
            if (context.isEmpty()) {
                continue;
            }
            RoutedResultContext value = context.get();
            decodedCount++;
            resultsByTask.computeIfAbsent(
                    value.taskId(),
                    ignored -> new ArrayList<>()
            ).add(new TaskResultEvidence(
                    value.taskId(),
                    value.messageId(),
                    result.payload()
            ));
            resultsByWorkerGroup.computeIfAbsent(
                    value.workerGroupId(),
                    ignored -> new ArrayList<>()
            ).add(new WorkerResultEvidence(
                    value.workerId(),
                    value.workerLease()
            ));
        }
        return new DecodedBatch(
                decodedCount,
                resultsByTask,
                resultsByWorkerGroup
        );
    }

    private void publishWorkerEvents(
            Map<String, List<WorkerResultEvidence>> resultsByWorkerGroup,
            boolean succeeded
    ) {
        resultsByWorkerGroup.forEach((workerGroupId, evidence) -> {
            LinkedHashMap<String, WorkerLeaseReference> leases =
                    new LinkedHashMap<>();
            for (WorkerResultEvidence result : evidence) {
                leases.put(result.workerId(), result.workerLease());
            }
            long observedAtMillis = currentTimeMillis.getAsLong();
            if (succeeded) {
                workerEvents.onTaskSucceeded(
                        workerGroupId,
                        leases,
                        observedAtMillis
                );
            } else {
                workerEvents.onTaskFailed(
                        workerGroupId,
                        leases,
                        observedAtMillis
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
