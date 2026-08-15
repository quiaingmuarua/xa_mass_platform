package com.xa.mass.server.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.xa.mass.server.control.ControlCallRegistry.BatchHandle;
import com.xa.mass.server.control.ControlCallRegistry.BatchOutcome;
import com.xa.mass.server.control.ControlCallRegistry.ControlTarget;
import com.xa.mass.server.control.ControlCallRegistry.TargetOutcome;
import com.xa.mass.server.control.ControlCallRegistry.TargetOutcomeReason;
import com.xa.mass.server.control.ControlCallRegistry.TargetPlan;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ControlCallRegistryTest {

    @Test
    void batchWaitsForEveryTargetAndPreservesInputOrder() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch-1",
                List.of(
                        workerPlan("worker-1", "call-1", "adapter-1"),
                        workerPlan("worker-2", "call-2", "adapter-2"),
                        TargetPlan.rejected(
                                "worker-3",
                                TargetOutcomeReason.NOT_BOUND
                        )
                )
        );

        assertThat(handle.completion().toCompletableFuture()).isNotDone();
        registry.consume("adapter-1", 10, now());
        registry.consume("adapter-2", 10, now());
        assertThat(registry.completeReports(
                "adapter-2",
                List.of(workerReport(
                        "call-2",
                        "worker-2",
                        "200"
                ))
        )).isEqualTo(new ControlCallRegistry.CompletionCounts(1, 0));
        assertThat(handle.completion().toCompletableFuture()).isNotDone();

        registry.completeReports(
                "adapter-1",
                List.of(workerReport(
                        "call-1",
                        "worker-1",
                        "3302"
                ))
        );

        assertThat(outcome(handle).results()).containsExactly(
                entry(
                        "worker-1",
                        TargetOutcome.observed("3302", "{\"ok\":true}")
                ),
                entry(
                        "worker-2",
                        TargetOutcome.observed("200", "{\"ok\":true}")
                ),
                entry(
                        "worker-3",
                        TargetOutcome.rejected(
                                TargetOutcomeReason.NOT_BOUND
                        )
                )
        );
    }

    @Test
    void overlappingBatchReplacesOnlyTheUnconsumedTarget() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle first = registry.registerBatch(
                "batch-1",
                List.of(
                        workerPlan("worker-1", "call-1", "adapter-1"),
                        workerPlan("worker-2", "call-2", "adapter-1")
                )
        );
        BatchHandle second = registry.registerBatch(
                "batch-2",
                List.of(workerPlan(
                        "worker-1",
                        "call-3",
                        "adapter-1"
                ))
        );

        Map<String, DeliveryCommand> consumed = registry.consume(
                "adapter-1",
                10,
                now()
        );
        assertThat(consumed).containsOnlyKeys("worker-2", "worker-1");
        registry.completeReports(
                "adapter-1",
                List.of(
                        workerReport("call-2", "worker-2", "200"),
                        workerReport("call-3", "worker-1", "200")
                )
        );

        assertThat(outcome(first).results()).containsExactly(
                entry(
                        "worker-1",
                        TargetOutcome.unobserved(
                                TargetOutcomeReason.REPLACED
                        )
                ),
                entry(
                        "worker-2",
                        TargetOutcome.observed("200", "{\"ok\":true}")
                )
        );
        assertThat(outcome(second).results()).containsExactly(
                entry(
                        "worker-1",
                        TargetOutcome.observed("200", "{\"ok\":true}")
                )
        );
    }

    @Test
    void timeoutCompletesConsumedAndUnconsumedTargetsAndRejectsLateResult() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch-1",
                List.of(
                        workerPlan("worker-1", "call-1", "adapter-1"),
                        workerPlan("worker-2", "call-2", "adapter-1")
                )
        );
        assertThat(registry.consume("adapter-1", 1, now()))
                .containsOnlyKeys("worker-1");

        registry.timeout("batch-1");

        assertThat(outcome(handle).results().values()).containsOnly(
                TargetOutcome.unobserved(TargetOutcomeReason.TIMEOUT)
        );
        assertThat(registry.consume("adapter-1", 10, now())).isEmpty();
        assertThat(registry.completeReports(
                "adapter-1",
                List.of(workerReport("call-1", "worker-1", "200"))
        )).isEqualTo(new ControlCallRegistry.CompletionCounts(0, 1));
    }

    @Test
    void cancelRemovesOnlyThatBatchSlotsAndRejectsItsLateResult() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle cancelled = registry.registerBatch(
                "batch-cancelled",
                List.of(
                        workerPlan("worker-1", "call-1", "adapter-1"),
                        workerPlan("worker-2", "call-2", "adapter-1")
                )
        );
        registry.registerBatch(
                "batch-kept",
                List.of(workerPlan(
                        "worker-3",
                        "call-3",
                        "adapter-1"
                ))
        );
        assertThat(registry.consume("adapter-1", 1, now()))
                .containsOnlyKeys("worker-1");

        registry.cancel("batch-cancelled");

        assertThat(cancelled.completion().toCompletableFuture())
                .isCancelled();
        assertThat(registry.consume("adapter-1", 10, now()))
                .containsOnlyKeys("worker-3");
        assertThat(registry.completeReports(
                "adapter-1",
                List.of(workerReport("call-1", "worker-1", "200"))
        )).isEqualTo(new ControlCallRegistry.CompletionCounts(0, 1));
    }

    @Test
    void resultAndTimeoutHaveOneTargetCompletionWinner() throws Exception {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch-1",
                List.of(workerPlan(
                        "worker-1",
                        "call-1",
                        "adapter-1"
                ))
        );
        registry.consume("adapter-1", 10, now());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var timeout = executor.submit(() -> {
                start.await();
                registry.timeout("batch-1");
                return null;
            });
            var result = executor.submit(() -> {
                start.await();
                return registry.completeReports(
                        "adapter-1",
                        List.of(workerReport(
                                "call-1",
                                "worker-1",
                                "200"
                        ))
                );
            });
            start.countDown();
            timeout.get();
            result.get();
        }

        TargetOutcome winner = outcome(handle).results().get("worker-1");
        assertThat(winner).isIn(
                TargetOutcome.observed("200", "{\"ok\":true}"),
                TargetOutcome.unobserved(TargetOutcomeReason.TIMEOUT)
        );
    }

    @Test
    void capacityPrecheckRejectsTheWholeCrossAdapterBatch() {
        ControlCallRegistry registry = registry(1, 10);
        registry.registerBatch(
                "existing",
                List.of(workerPlan(
                        "worker-1",
                        "call-1",
                        "adapter-1"
                ))
        );

        assertThatThrownBy(() -> registry.registerBatch(
                "rejected",
                List.of(
                        workerPlan("worker-2", "call-2", "adapter-1"),
                        workerPlan("worker-3", "call-3", "adapter-2")
                )
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.CONTROL_CALL_CAPACITY_EXCEEDED
                ));

        assertThat(registry.consume("adapter-2", 10, now())).isEmpty();
        assertThat(registry.consume("adapter-1", 10, now()))
                .containsOnlyKeys("worker-1");
    }

    @Test
    void rejectedOnlyBatchUsesNoPendingCapacityAndCompletesImmediately() {
        ControlCallRegistry registry = registry(1, 1);
        BatchHandle rejected = registry.registerBatch(
                "batch-rejected",
                List.of(TargetPlan.rejected(
                        "worker-1",
                        TargetOutcomeReason.CONTROL_ONLY_REQUIRED
                ))
        );
        registry.registerBatch(
                "batch-pending",
                List.of(workerPlan(
                        "worker-2",
                        "call-2",
                        "adapter-1"
                ))
        );

        assertThat(outcome(rejected).results()).containsExactly(
                entry(
                        "worker-1",
                        TargetOutcome.rejected(
                                TargetOutcomeReason.CONTROL_ONLY_REQUIRED
                        )
                )
        );
    }

    @Test
    void closeReturnsShutdownAndClearsEveryAdapterMailbox() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch-1",
                List.of(
                        workerPlan("worker-1", "call-1", "adapter-1"),
                        adapterPlan("adapter-2", "call-2")
                )
        );

        registry.close();

        assertThat(outcome(handle).results().values()).containsOnly(
                TargetOutcome.unobserved(TargetOutcomeReason.SHUTDOWN)
        );
        assertThatThrownBy(() -> registry.consume(
                "adapter-1",
                10,
                now()
        )).isInstanceOf(ServerException.class);
    }

    private static ControlCallRegistry registry(
            int mailboxCapacity,
            int pendingCapacity
    ) {
        return new ControlCallRegistry(new ControlCallProperties(
                3_000,
                10_000,
                mailboxCapacity,
                pendingCapacity
        ));
    }

    private static TargetPlan workerPlan(
            String workerId,
            String callId,
            String adapterId
    ) {
        return TargetPlan.command(
                workerId,
                callId,
                adapterId,
                ControlTarget.worker(workerId),
                command(callId, DeliveryEndpoint.WORKER)
        );
    }

    private static TargetPlan adapterPlan(
            String adapterId,
            String callId
    ) {
        return TargetPlan.command(
                adapterId,
                callId,
                adapterId,
                ControlTarget.adapter(adapterId),
                command(callId, DeliveryEndpoint.ADAPTER)
        );
    }

    private static DeliveryCommand command(
            String callId,
            DeliveryEndpoint destination
    ) {
        return DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                destination,
                "event-1",
                now() + 60_000,
                "{}",
                ControlCallRegistry.FORWARD_PREFIX + callId
        );
    }

    private static DeliveryReport workerReport(
            String callId,
            String workerId,
            String outcomeCode
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                workerId,
                DeliveryEndpoint.SYSTEM,
                "event-1",
                outcomeCode,
                "{\"ok\":true}",
                ControlCallRegistry.FORWARD_PREFIX + callId
        );
    }

    private static BatchOutcome outcome(BatchHandle handle) {
        return handle.completion().toCompletableFuture().join();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
