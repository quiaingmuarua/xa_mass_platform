package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.task.TaskItemResultEvents;
import com.xa.mass.kernel.worker.WorkerExecutionResultEvents;
import com.xa.mass.kernel.worker.WorkerLeaseReference;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TaskResultBatchPolicyTest {

    @Test
    void publishesGroupedNamedEventsInTheFixedOwnerOrder() {
        List<String> calls = new ArrayList<>();
        List<Map<String, WorkerLeaseReference>> workerBatches =
                new ArrayList<>();
        TaskItemResultEvents taskEvents = new TaskItemResultEvents() {
            @Override
            public void onItemsSucceeded(
                    String taskId,
                    Map<String, String> payloadsByMessageId,
                    long observedAtMillis
            ) {
                calls.add("items-succeeded:" + taskId + ":"
                        + payloadsByMessageId + ":" + observedAtMillis);
            }

            @Override
            public void onItemsFailed(
                    String taskId,
                    List<String> messageIds,
                    long observedAtMillis
            ) {
                calls.add("items-failed:" + taskId + ":" + messageIds
                        + ":" + observedAtMillis);
            }
        };
        WorkerExecutionResultEvents workerEvents =
                new WorkerExecutionResultEvents() {
                    @Override
                    public void onTaskSucceeded(
                            String workerGroupId,
                            Map<String, WorkerLeaseReference> leases,
                            long observedAtMillis
                    ) {
                        calls.add("workers-succeeded:" + workerGroupId
                                + ":" + observedAtMillis);
                        workerBatches.add(new LinkedHashMap<>(leases));
                    }

                    @Override
                    public void onTaskFailed(
                            String workerGroupId,
                            Map<String, WorkerLeaseReference> leases,
                            long observedAtMillis
                    ) {
                        calls.add("workers-failed:" + workerGroupId
                                + ":" + observedAtMillis);
                        workerBatches.add(new LinkedHashMap<>(leases));
                    }
                };
        AtomicLong clock = new AtomicLong(1_000);
        TaskResultBatchPolicy policy = new TaskResultBatchPolicy(
                taskEvents,
                workerEvents,
                clock::getAndIncrement,
                new ResultContextCodec()
        );

        policy.handleSuccess(List.of(
                report("200", "first", 11),
                report("3303", "last", 12)
        ));
        policy.handleFailure(List.of(
                report("200", "worker-failure", 13),
                report("23002", "adapter-rejection", 14)
        ));

        assertEquals(List.of(
                "items-succeeded:task-1:{message-1=last}:1000",
                "workers-succeeded:group-1:1001",
                "items-failed:task-1:[message-1]:1002",
                "workers-failed:group-1:1003"
        ), calls);
        assertEquals(List.of(
                Map.of(
                        "worker-1",
                        WorkerLeaseReference.fromEncodedScore(12)
                ),
                Map.of(
                        "worker-1",
                        WorkerLeaseReference.fromEncodedScore(14)
                )
        ), workerBatches);
    }

    @Test
    void dropsMalformedContextAndWrongDestinationWithoutEvents() {
        List<String> calls = new ArrayList<>();
        TaskResultBatchPolicy policy = new TaskResultBatchPolicy(
                recordingTaskEvents(calls),
                recordingWorkerEvents(calls)
        );
        List<DeliveryReport> invalid = List.of(
                DeliveryReport.create(
                        DeliveryEndpoint.WORKER,
                        "worker-1",
                        DeliveryEndpoint.SYSTEM,
                        "extension.worker.test",
                        "200",
                        "payload",
                        context(1)
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.WORKER,
                        "worker-1",
                        DeliveryEndpoint.TASK,
                        "extension.worker.test",
                        "200",
                        "payload",
                        "not-json"
                )
        );

        policy.handleSuccess(invalid);
        policy.handleFailure(invalid);

        assertEquals(List.of(), calls);
    }

    private static TaskItemResultEvents recordingTaskEvents(
            List<String> calls
    ) {
        return new TaskItemResultEvents() {
            @Override
            public void onItemsSucceeded(
                    String taskId,
                    Map<String, String> payloadsByMessageId,
                    long observedAtMillis
            ) {
                calls.add("unexpected");
            }

            @Override
            public void onItemsFailed(
                    String taskId,
                    List<String> messageIds,
                    long observedAtMillis
            ) {
                calls.add("unexpected");
            }
        };
    }

    private static WorkerExecutionResultEvents recordingWorkerEvents(
            List<String> calls
    ) {
        return new WorkerExecutionResultEvents() {
            @Override
            public void onTaskSucceeded(
                    String workerGroupId,
                    Map<String, WorkerLeaseReference> leasesByWorkerId,
                    long observedAtMillis
            ) {
                calls.add("unexpected");
            }

            @Override
            public void onTaskFailed(
                    String workerGroupId,
                    Map<String, WorkerLeaseReference> leasesByWorkerId,
                    long observedAtMillis
            ) {
                calls.add("unexpected");
            }
        };
    }

    private static DeliveryReport report(
            String outcomeCode,
            String payload,
            long score
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.TASK,
                "extension.worker.test",
                outcomeCode,
                payload,
                context(score)
        );
    }

    private static String context(long score) {
        return """
                {"taskId":"task-1","messageId":"message-1",
                 "workerId":"worker-1","workerGroupId":"group-1",
                 "workerLeaseScore":%d}
                """.formatted(score);
    }
}
