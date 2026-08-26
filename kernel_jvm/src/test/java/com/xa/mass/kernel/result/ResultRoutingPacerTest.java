package com.xa.mass.kernel.result;

import com.xa.mass.kernel.delivery.ResultContextCodec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReportOutcomeClass;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ResultRoutingPacerTest {

    @Test
    void routesFixedLanesAndPreservesOwnerOrderingAndLastSemantics() {
        List<String> calls = new ArrayList<>();
        Map<DeliveryReportOutcomeClass, List<DeliveryReport>> batches =
                new EnumMap<>(DeliveryReportOutcomeClass.class);
        batches.put(DeliveryReportOutcomeClass.SUCCESS, List.of(
                report("200", "first", 11),
                report("200", "last", 12)
        ));
        batches.put(DeliveryReportOutcomeClass.WORKER_FAILURE, List.of(
                report("3303", "worker-failure", 13)
        ));
        batches.put(DeliveryReportOutcomeClass.ADAPTER_REJECTION, List.of(
                report("23002", "adapter-rejection", 14)
        ));
        WorkerResultRuntime runtime = new WorkerResultRuntime() {
            @Override
            public int appendWorkerResults(List<DeliveryReport> results) {
                throw new AssertionError("append is not used");
            }

            @Override
            public List<DeliveryReport> consumeWorkerResults(
                    DeliveryReportOutcomeClass outcomeClass,
                    int limit
            ) {
                calls.add("consume:" + outcomeClass + ":" + limit);
                return batches.get(outcomeClass);
            }
        };
        TaskRuntime taskRuntime = proxy(TaskRuntime.class, (_proxy, method, args) -> {
            if (method.getName().equals("storeTaskItemSuccessResults")) {
                calls.add("store:" + args[0] + ":" + args[1]);
                return null;
            }
            throw new AssertionError("Unexpected TaskRuntime operation");
        });
        TaskItemScoreBandCore itemScore = proxy(
                TaskItemScoreBandCore.class,
                (_proxy, method, args) -> {
                    if (method.getName().equals("promoteItemOutcomes")) {
                        calls.add("promote:" + args[0] + ":" + args[1]
                                + ":" + args[3]);
                        return Map.of();
                    }
                    throw new AssertionError(
                            "Unexpected TaskItem score operation"
                    );
                }
        );
        WorkerScoreCore workerScore = proxy(
                WorkerScoreCore.class,
                (_proxy, method, args) -> {
                    if (method.getName().equals(
                            "releaseCompletedHotScoreHolds"
                    )) {
                        calls.add("completed:" + args[0] + ":" + args[1]
                                + ":" + args[2]);
                        return Map.of();
                    }
                    if (method.getName().equals("releaseScoreHolds")) {
                        calls.add("release:" + args[0] + ":" + args[1]
                                + ":" + args[2]);
                        return Map.of();
                    }
                    throw new AssertionError(
                            "Unexpected Worker score operation"
                    );
                }
        );
        AtomicLong clock = new AtomicLong(1_000);
        ResultRoutingPacer pacer = new ResultRoutingPacer(
                runtime,
                taskRuntime,
                itemScore,
                workerScore,
                clock::getAndIncrement,
                new ResultContextCodec()
        );

        assertEquals(4, pacer.routeWorkerResults(
                new ResultRoutingConfig(100)
        ));

        assertEquals("consume:SUCCESS:100", calls.get(0));
        assertTrue(calls.get(1).contains("{message-1=last}"));
        assertEquals("promote:task-1:[message-1]:1000", calls.get(2));
        assertTrue(calls.get(3).contains("{worker-1=12}"));
        assertTrue(calls.get(3).endsWith(":1001"));
        assertEquals("consume:WORKER_FAILURE:100", calls.get(4));
        assertTrue(calls.get(5).contains("{worker-1=13}"));
        assertTrue(calls.get(5).endsWith(":1002"));
        assertEquals("consume:ADAPTER_REJECTION:100", calls.get(6));
        assertTrue(calls.get(7).contains("{worker-1=14}"));
        assertTrue(calls.get(7).endsWith(":1003"));
    }

    @Test
    void consumesButDropsMalformedWrongDestinationAndWrongLane() {
        WorkerResultRuntime runtime = new WorkerResultRuntime() {
            @Override
            public int appendWorkerResults(List<DeliveryReport> results) {
                return 0;
            }

            @Override
            public List<DeliveryReport> consumeWorkerResults(
                    DeliveryReportOutcomeClass outcomeClass,
                    int limit
            ) {
                return outcomeClass == DeliveryReportOutcomeClass.SUCCESS
                        ? List.of(
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
                                ),
                                report("3303", "wrong-lane", 15)
                        )
                        : List.of();
            }
        };
        Object[] owners = noCallOwners();
        ResultRoutingPacer pacer = new ResultRoutingPacer(
                runtime,
                (TaskRuntime) owners[0],
                (TaskItemScoreBandCore) owners[1],
                (WorkerScoreCore) owners[2]
        );

        assertEquals(0, pacer.routeWorkerResults(
                new ResultRoutingConfig(100)
        ));
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

    private static Object[] noCallOwners() {
        return new Object[]{
                proxy(TaskRuntime.class, (_proxy, method, args) -> {
                    throw new AssertionError("Task owner must not be called");
                }),
                proxy(TaskItemScoreBandCore.class, (_proxy, method, args) -> {
                    throw new AssertionError("Item owner must not be called");
                }),
                proxy(WorkerScoreCore.class, (_proxy, method, args) -> {
                    throw new AssertionError("Worker owner must not be called");
                })
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> contract,
            java.lang.reflect.InvocationHandler handler
    ) {
        return (T) Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                handler
        );
    }
}
