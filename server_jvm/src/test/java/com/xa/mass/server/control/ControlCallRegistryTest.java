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
        registry.consumeWorkerCommands("adapter-1", 10, now());
        registry.consumeWorkerCommands("adapter-2", 10, now());
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

        Map<String, DeliveryCommand> consumed = registry.consumeWorkerCommands(
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
    void adapterCommandsAccumulateAndConsumeInFifoOrder() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle first = registry.registerBatch(
                "batch-1",
                List.of(adapterPlan("adapter-1", "call-1"))
        );
        BatchHandle second = registry.registerBatch(
                "batch-2",
                List.of(adapterPlan("adapter-1", "call-2"))
        );

        List<DeliveryCommand> commands = registry.consumeAdapterCommands(
                "adapter-1",
                10,
                now()
        );

        assertThat(commands)
                .extracting(DeliveryCommand::forward)
                .containsExactly(
                        ControlCallRegistry.FORWARD_PREFIX + "call-1",
                        ControlCallRegistry.FORWARD_PREFIX + "call-2"
                );
        assertThat(first.completion().toCompletableFuture()).isNotDone();
        assertThat(second.completion().toCompletableFuture()).isNotDone();

        registry.completeReports(
                "adapter-1",
                List.of(
                        adapterReport("call-1", "adapter-1"),
                        adapterReport("call-2", "adapter-1")
                )
        );

        assertThat(outcome(first).results().get("adapter-1"))
                .isEqualTo(TargetOutcome.observed(
                        "200",
                        "{\"ok\":true}"
                ));
        assertThat(outcome(second).results().get("adapter-1"))
                .isEqualTo(TargetOutcome.observed(
                        "200",
                        "{\"ok\":true}"
                ));
    }

    @Test
    void adapterListAndWorkerHashHaveIndependentCapacity() {
        ControlCallRegistry registry = registry(1, 1, 10);
        registry.registerBatch(
                "adapter-batch",
                List.of(adapterPlan("adapter-1", "adapter-call"))
        );
        registry.registerBatch(
                "worker-batch",
                List.of(workerPlan(
                        "worker-1",
                        "worker-call",
                        "adapter-1"
                ))
        );

        assertThatThrownBy(() -> registry.registerBatch(
                "adapter-overflow",
                List.of(adapterPlan("adapter-1", "adapter-overflow-call"))
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.CONTROL_CALL_CAPACITY_EXCEEDED
                ));
        assertThatThrownBy(() -> registry.registerBatch(
                "worker-overflow",
                List.of(workerPlan(
                        "worker-2",
                        "worker-overflow-call",
                        "adapter-1"
                ))
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.CONTROL_CALL_CAPACITY_EXCEEDED
                ));

        assertThat(registry.consumeAdapterCommands(
                "adapter-1",
                10,
                now()
        )).hasSize(1);
        assertThat(registry.consumeWorkerCommands(
                "adapter-1",
                10,
                now()
        )).containsOnlyKeys("worker-1");
    }

    @Test
    void adapterTimeoutAndCancelRemoveOnlyTheirFifoEntries() {
        ControlCallRegistry registry = registry(10, 10);
        registry.registerBatch(
                "batch-first",
                List.of(adapterPlan("adapter-1", "call-first"))
        );
        BatchHandle cancelled = registry.registerBatch(
                "batch-cancelled",
                List.of(adapterPlan("adapter-1", "call-cancelled"))
        );
        BatchHandle timedOut = registry.registerBatch(
                "batch-timeout",
                List.of(adapterPlan("adapter-1", "call-timeout"))
        );
        registry.registerBatch(
                "batch-last",
                List.of(adapterPlan("adapter-1", "call-last"))
        );

        registry.cancel("batch-cancelled");
        registry.timeout("batch-timeout");

        assertThat(cancelled.completion().toCompletableFuture())
                .isCancelled();
        assertThat(outcome(timedOut).results().get("adapter-1"))
                .isEqualTo(TargetOutcome.unobserved(
                        TargetOutcomeReason.TIMEOUT
                ));
        assertThat(registry.consumeAdapterCommands(
                "adapter-1",
                10,
                now()
        )).extracting(DeliveryCommand::forward).containsExactly(
                ControlCallRegistry.FORWARD_PREFIX + "call-first",
                ControlCallRegistry.FORWARD_PREFIX + "call-last"
        );
    }

    @Test
    void expiredAdapterEntryDoesNotConsumeTheResponseLimit() {
        ControlCallRegistry registry = registry(10, 10);
        BatchHandle expired = registry.registerBatch(
                "batch-expired",
                List.of(adapterPlan(
                        "adapter-1",
                        "call-expired",
                        now() - 1
                ))
        );
        registry.registerBatch(
                "batch-live",
                List.of(adapterPlan("adapter-1", "call-live"))
        );

        assertThat(registry.consumeAdapterCommands(
                "adapter-1",
                1,
                now()
        )).extracting(DeliveryCommand::forward).containsExactly(
                ControlCallRegistry.FORWARD_PREFIX + "call-live"
        );
        assertThat(outcome(expired).results().get("adapter-1"))
                .isEqualTo(TargetOutcome.unobserved(
                        TargetOutcomeReason.TIMEOUT
                ));
    }

    @Test
    void workerHashConsumesABoundedNonRepeatingSubset() {
        ControlCallRegistry registry = registry(10, 10);
        registry.registerBatch(
                "batch-1",
                List.of(
                        workerPlan("worker-1", "call-1", "adapter-1"),
                        workerPlan("worker-2", "call-2", "adapter-1"),
                        workerPlan("worker-3", "call-3", "adapter-1")
                )
        );

        Map<String, DeliveryCommand> first =
                registry.consumeWorkerCommands("adapter-1", 2, now());
        Map<String, DeliveryCommand> second =
                registry.consumeWorkerCommands("adapter-1", 2, now());

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
        assertThat(first.keySet()).doesNotContainAnyElementsOf(second.keySet());
        assertThat(java.util.stream.Stream.concat(
                first.keySet().stream(),
                second.keySet().stream()
        ).toList()).containsExactlyInAnyOrder(
                "worker-1",
                "worker-2",
                "worker-3"
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
        Map<String, DeliveryCommand> consumed =
                registry.consumeWorkerCommands("adapter-1", 1, now());
        assertThat(consumed).hasSize(1);
        Map.Entry<String, DeliveryCommand> consumedEntry =
                consumed.entrySet().iterator().next();

        registry.timeout("batch-1");

        assertThat(outcome(handle).results().values()).containsOnly(
                TargetOutcome.unobserved(TargetOutcomeReason.TIMEOUT)
        );
        assertThat(registry.consumeWorkerCommands(
                "adapter-1",
                10,
                now()
        )).isEmpty();
        assertThat(registry.completeReports(
                "adapter-1",
                List.of(workerReport(
                        consumedEntry.getValue().forward().substring(
                                ControlCallRegistry.FORWARD_PREFIX.length()
                        ),
                        consumedEntry.getKey(),
                        "200"
                ))
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
        Map<String, DeliveryCommand> consumed =
                registry.consumeWorkerCommands("adapter-1", 1, now());
        assertThat(consumed).hasSize(1);
        Map.Entry<String, DeliveryCommand> consumedEntry =
                consumed.entrySet().iterator().next();
        registry.registerBatch(
                "batch-kept",
                List.of(workerPlan(
                        "worker-3",
                        "call-3",
                        "adapter-1"
                ))
        );
        registry.cancel("batch-cancelled");

        assertThat(cancelled.completion().toCompletableFuture())
                .isCancelled();
        assertThat(registry.consumeWorkerCommands("adapter-1", 10, now()))
                .containsOnlyKeys("worker-3");
        assertThat(registry.completeReports(
                "adapter-1",
                List.of(workerReport(
                        consumedEntry.getValue().forward().substring(
                                ControlCallRegistry.FORWARD_PREFIX.length()
                        ),
                        consumedEntry.getKey(),
                        "200"
                ))
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
        registry.consumeWorkerCommands("adapter-1", 10, now());
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

        assertThat(registry.consumeWorkerCommands(
                "adapter-2",
                10,
                now()
        )).isEmpty();
        assertThat(registry.consumeWorkerCommands(
                "adapter-1",
                10,
                now()
        ))
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
        assertThatThrownBy(() -> registry.consumeWorkerCommands(
                "adapter-1",
                10,
                now()
        )).isInstanceOf(ServerException.class);
    }

    private static ControlCallRegistry registry(
            int mailboxCapacity,
            int pendingCapacity
    ) {
        return registry(
                mailboxCapacity,
                mailboxCapacity,
                pendingCapacity
        );
    }

    private static ControlCallRegistry registry(
            int adapterCapacity,
            int workerCapacity,
            int pendingCapacity
    ) {
        return new ControlCallRegistry(new ControlCallProperties(
                3_000,
                10_000,
                adapterCapacity,
                workerCapacity,
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
        return adapterPlan(adapterId, callId, now() + 60_000);
    }

    private static TargetPlan adapterPlan(
            String adapterId,
            String callId,
            long executeBeforeMillis
    ) {
        return TargetPlan.command(
                adapterId,
                callId,
                adapterId,
                ControlTarget.adapter(adapterId),
                DeliveryCommand.create(
                        DeliveryEndpoint.SYSTEM,
                        DeliveryEndpoint.ADAPTER,
                        "event-1",
                        executeBeforeMillis,
                        "{}",
                        ControlCallRegistry.FORWARD_PREFIX + callId
                )
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

    private static DeliveryReport adapterReport(
            String callId,
            String adapterId
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                adapterId,
                DeliveryEndpoint.SYSTEM,
                "event-1",
                "200",
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
