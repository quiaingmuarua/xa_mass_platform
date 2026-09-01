package com.xa.mass.server.delivery.directcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.server.delivery.directcall.DirectCallRegistry.BatchHandle;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.DirectTarget;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcome;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcomeReason;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetPlan;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirectCallRegistryTest {

    @Test
    void adapterCommandsAreFifoAndBecomeResultEligibleOnConsume() {
        DirectCallRegistry registry = registry(10, 10);
        BatchHandle first = registry.registerBatch(
                "batch-1",
                List.of(adapterPlan("adapter", "call-1", 20_000))
        );
        BatchHandle second = registry.registerBatch(
                "batch-2",
                List.of(adapterPlan("adapter", "call-2", 20_000))
        );

        assertThat(registry.completeReports(
                "adapter",
                List.of(adapterReport("call-1"))
        ).rejectedCount()).isEqualTo(1);
        assertThat(registry.consumeAdapterCommands("adapter", 10, 10_000))
                .extracting(DeliveryCommand::forward)
                .containsExactly(
                        DirectCallRegistry.FORWARD_PREFIX + "call-1",
                        DirectCallRegistry.FORWARD_PREFIX + "call-2"
                );
        assertThat(registry.completeReports(
                "adapter",
                List.of(adapterReport("call-1"), adapterReport("call-2"))
        )).isEqualTo(new DirectCallRegistry.CompletionCounts(2, 0));
        assertThat(first.completion().toCompletableFuture().join().results())
                .containsOnlyKeys("adapter");
        assertThat(second.completion().toCompletableFuture().join().results())
                .containsOnlyKeys("adapter");
    }

    @Test
    void registeredWorkerCanCompleteBeforeOfferCallerProcessesItsStatus() {
        DirectCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch",
                List.of(workerPlan("worker-1", "call-1"))
        );

        assertThat(registry.completeReports(
                "adapter",
                List.of(workerReport("worker-1", "call-1"))
        )).isEqualTo(new DirectCallRegistry.CompletionCounts(1, 0));
        assertThat(handle.completion().toCompletableFuture().join().results()
                .get("worker-1").status())
                .isEqualTo(DirectCallRegistry.TargetOutcomeStatus.OBSERVED);
    }

    @Test
    void submissionOutcomesCompleteOnlyTheMatchingPendingTargets() {
        DirectCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch",
                List.of(
                        workerPlan("worker-1", "call-1"),
                        workerPlan("worker-2", "call-2")
                )
        );

        assertThat(registry.completeTargets(Map.of(
                "call-1",
                TargetOutcome.rejected(
                        TargetOutcomeReason.COMMAND_SLOT_OCCUPIED
                ),
                "unknown",
                TargetOutcome.unobserved(
                        TargetOutcomeReason.SUBMISSION_UNKNOWN
                )
        ))).isEqualTo(1);
        registry.completeReports(
                "adapter",
                List.of(workerReport("worker-2", "call-2"))
        );

        var results = handle.completion().toCompletableFuture().join().results();
        assertThat(results.keySet()).containsExactly("worker-1", "worker-2");
        assertThat(results.get("worker-1").reason())
                .isEqualTo(TargetOutcomeReason.COMMAND_SLOT_OCCUPIED);
        assertThat(results.get("worker-2").status())
                .isEqualTo(DirectCallRegistry.TargetOutcomeStatus.OBSERVED);
    }

    @Test
    void timeoutDoesNotNeedAWorkerMailboxCleanupPath() {
        DirectCallRegistry registry = registry(10, 10);
        BatchHandle handle = registry.registerBatch(
                "batch",
                List.of(workerPlan("worker-1", "call-1"))
        );

        registry.timeout("batch");

        assertThat(handle.completion().toCompletableFuture().join().results()
                .get("worker-1").reason())
                .isEqualTo(TargetOutcomeReason.TIMEOUT);
        assertThat(registry.completeReports(
                "adapter",
                List.of(workerReport("worker-1", "call-1"))
        ).rejectedCount()).isEqualTo(1);
    }

    @Test
    void expiredAdapterEntriesDoNotConsumeResponseCapacity() {
        DirectCallRegistry registry = registry(10, 10);
        BatchHandle expired = registry.registerBatch(
                "expired",
                List.of(adapterPlan("adapter", "old", 10_000))
        );
        registry.registerBatch(
                "live",
                List.of(adapterPlan("adapter", "live", 20_000))
        );

        assertThat(registry.consumeAdapterCommands("adapter", 1, 10_000))
                .extracting(DeliveryCommand::forward)
                .containsExactly(DirectCallRegistry.FORWARD_PREFIX + "live");
        assertThat(expired.completion().toCompletableFuture().join().results()
                .get("adapter").reason())
                .isEqualTo(TargetOutcomeReason.TIMEOUT);
    }

    @Test
    void capacitiesAreAtomicAndShutdownCompletesPendingTargets() {
        DirectCallRegistry registry = registry(1, 2);
        BatchHandle pending = registry.registerBatch(
                "batch-1",
                List.of(adapterPlan("adapter", "call-1", 20_000))
        );

        assertThatThrownBy(() -> registry.registerBatch(
                "batch-2",
                List.of(adapterPlan("adapter", "call-2", 20_000))
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.DIRECT_CALL_CAPACITY_EXCEEDED
                ));

        registry.close();
        assertThat(pending.completion().toCompletableFuture().join().results()
                .get("adapter").reason())
                .isEqualTo(TargetOutcomeReason.SHUTDOWN);
        assertThatThrownBy(() -> registry.registerBatch(
                "batch-3",
                List.of(workerPlan("worker-1", "call-3"))
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.DIRECT_CALL_UNAVAILABLE
                ));
    }

    private static DirectCallRegistry registry(
            int adapterCapacity,
            int pendingCapacity
    ) {
        return new DirectCallRegistry(new DirectCallProperties(
                3_000,
                10_000,
                adapterCapacity,
                pendingCapacity
        ));
    }

    private static TargetPlan adapterPlan(
            String adapterId,
            String correlationId,
            long deadline
    ) {
        return TargetPlan.command(
                adapterId,
                correlationId,
                adapterId,
                DirectTarget.adapter(adapterId),
                command(
                        DeliveryEndpoint.ADAPTER,
                        correlationId,
                        deadline
                )
        );
    }

    private static TargetPlan workerPlan(
            String workerId,
            String correlationId
    ) {
        return TargetPlan.command(
                workerId,
                correlationId,
                "adapter",
                DirectTarget.worker(workerId),
                command(DeliveryEndpoint.WORKER, correlationId, 20_000)
        );
    }

    private static DeliveryCommand command(
            DeliveryEndpoint destination,
            String correlationId,
            long deadline
    ) {
        return DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                destination,
                "event",
                deadline,
                "{}",
                DirectCallRegistry.FORWARD_PREFIX + correlationId
        );
    }

    private static DeliveryReport adapterReport(String correlationId) {
        return DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "adapter",
                DeliveryEndpoint.SYSTEM,
                "event",
                "200",
                "{}",
                DirectCallRegistry.FORWARD_PREFIX + correlationId
        );
    }

    private static DeliveryReport workerReport(
            String workerId,
            String correlationId
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                workerId,
                DeliveryEndpoint.SYSTEM,
                "event",
                "200",
                "{}",
                DirectCallRegistry.FORWARD_PREFIX + correlationId
        );
    }
}
