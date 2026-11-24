package com.xa.mass.server.assembly.metrics;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.server.assembly.pacer.WorkerObservationMetrics;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.task.call.TaskRpcWaitRegistry;
import com.xa.mass.server.task.observation.TaskLeaseProjectionService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Binds read-only Gauges and FunctionCounters for Server runtime state
 * without injecting Micrometer into any domain owner or business service.
 */
final class RuntimeMetricsBinder {

    private RuntimeMetricsBinder() {}

    static void bindPacer(MeterRegistry registry, KernelPacerRuntime pacerRuntime) {
        Gauge.builder("xa.mass.pacer.running", pacerRuntime, runtime -> runtime.isRunning() ? 1.0 : 0.0)
                .description("Whether KernelPacerRuntime is currently running (1=running, 0=stopped/failed)")
                .register(registry);
    }

    static void bindRpcWaiters(MeterRegistry registry, TaskRpcWaitRegistry rpcWaitRegistry) {
        Gauge.builder("xa.mass.rpc.waiters.active", rpcWaitRegistry, TaskRpcWaitRegistry::activeWaiterCount)
                .description("Currently active managed Task RPC waiters")
                .register(registry);
        Gauge.builder(
                        "xa.mass.rpc.observations.pending",
                        rpcWaitRegistry,
                        TaskRpcWaitRegistry::pendingObservationCountSnapshot
                )
                .description("Currently pending managed Task RPC waiter-item observations")
                .register(registry);
    }

    static void bindDirectCalls(MeterRegistry registry, DirectCallRegistry directCallRegistry) {
        Gauge.builder("xa.mass.direct_call.targets.pending", directCallRegistry, DirectCallRegistry::pendingTargetCount)
                .description("Currently pending Direct Call targets")
                .register(registry);
        Gauge.builder("xa.mass.direct_call.batches.active", directCallRegistry, DirectCallRegistry::activeBatchCount)
                .description("Currently active Direct Call batches")
                .register(registry);
    }

    static void bindWorkerObservations(MeterRegistry registry, WorkerObservationMetrics workerObservations) {
        FunctionCounter.builder(
                        "xa.mass.worker_observation.batches",
                        workerObservations,
                        WorkerObservationMetrics::enqueuedBatches
                )
                .description("Bounded Worker observation batches by outcome")
                .tag("outcome", "enqueued")
                .register(registry);
        FunctionCounter.builder(
                        "xa.mass.worker_observation.batches",
                        workerObservations,
                        WorkerObservationMetrics::droppedBatches
                )
                .description("Bounded Worker observation batches by outcome")
                .tag("outcome", "dropped")
                .register(registry);
        FunctionCounter.builder(
                        "xa.mass.worker_observation.batches",
                        workerObservations,
                        WorkerObservationMetrics::failedBatches
                )
                .description("Bounded Worker observation batches by outcome")
                .tag("outcome", "failed")
                .register(registry);
        Gauge.builder("xa.mass.worker_observation.queue.size", workerObservations, WorkerObservationMetrics::queueSize)
                .description("Current queued batch count in WorkerObservationConsumer")
                .register(registry);
    }

    static void bindLeaseProjections(MeterRegistry registry, TaskLeaseProjectionService leaseProjections) {
        FunctionCounter.builder(
                        "xa.mass.lease_projection.items",
                        leaseProjections,
                        TaskLeaseProjectionService::acceptedCount
                )
                .description("Stored-Result lease projection items by outcome")
                .tag("outcome", "accepted")
                .register(registry);
        FunctionCounter.builder(
                        "xa.mass.lease_projection.items",
                        leaseProjections,
                        TaskLeaseProjectionService::droppedCount
                )
                .description("Stored-Result lease projection items by outcome")
                .tag("outcome", "dropped")
                .register(registry);
        FunctionCounter.builder(
                        "xa.mass.lease_projection.items",
                        leaseProjections,
                        TaskLeaseProjectionService::failureCount
                )
                .description("Stored-Result lease projection items by outcome")
                .tag("outcome", "failures")
                .register(registry);
        FunctionCounter.builder(
                        "xa.mass.lease_projection.items",
                        leaseProjections,
                        TaskLeaseProjectionService::recordedCount
                )
                .description("Stored-Result lease projection items by outcome")
                .tag("outcome", "recorded")
                .register(registry);
        Gauge.builder("xa.mass.lease_projection.queue.size", leaseProjections, TaskLeaseProjectionService::queueBatchCount)
                .description("Current queued batch count in TaskLeaseProjectionService")
                .register(registry);
    }
}
