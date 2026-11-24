package com.xa.mass.server.assembly.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.assembly.pacer.WorkerObservationMetrics;
import com.xa.mass.server.delivery.directcall.DirectCallProperties;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.DirectTarget;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcome;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcomeReason;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetPlan;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.call.TaskRpcProperties;
import com.xa.mass.server.task.call.TaskRpcWaitRegistry;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;

class RuntimeMetricsBinderTest {

    @Test
    void recordsApiErrorsPacerBatchesRpcWaitsDirectCallsAndReadOnlyGauges() {
        var registry = new SimpleMeterRegistry();
        var recorder = new RuntimeMetricsRecorder(registry);

        // 1. API error front door
        var handler = new ApiExceptionHandler();
        handler.setErrorObserver(recorder);
        var request = mock(HttpServletRequest.class);
        handler.serverFailure(
                new ServerException(ServerErrorCode.TASK_NOT_FOUND, "task.get", "missing", null),
                request
        );
        handler.malformedRequest(new IllegalArgumentException("bad"), request);
        recorder.onError(ServerErrorCode.UNAUTHENTICATED, 401);

        assertThat(registry.get("xa.mass.api.errors")
                .tag("code", Integer.toString(ServerErrorCode.TASK_NOT_FOUND.code()))
                .tag("error", "TASK_NOT_FOUND")
                .tag("status", "400")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("xa.mass.api.errors")
                .tag("code", Integer.toString(ServerErrorCode.MALFORMED_REQUEST.code()))
                .tag("status", "400")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("xa.mass.api.errors")
                .tag("code", Integer.toString(ServerErrorCode.UNAUTHENTICATED.code()))
                .tag("status", "401")
                .counter().count()).isEqualTo(1.0);

        // 2. Pacer dispatch & result batch summaries
        recorder.onDispatchRound("TASK_DISPATCH", 11, TimeUnit.MILLISECONDS.toNanos(12), false);
        recorder.onDispatchRound("ELIGIBILITY_REFILL", 5, TimeUnit.MILLISECONDS.toNanos(4), false);
        recorder.onDispatchRound("TASK_DISPATCH", 0, TimeUnit.MILLISECONDS.toNanos(3), true);
        recorder.onResultBatch("TASK_SUCCESS", 8, TimeUnit.MILLISECONDS.toNanos(6), false);
        recorder.onResultBatch("TASK_FAILURE", 2, TimeUnit.MILLISECONDS.toNanos(2), true);
        recorder.onResultConsumeFailure("TASK_OBSERVATION");

        assertThat(registry.get("xa.mass.pacer.dispatch.rounds")
                .tag("producer", "TASK_DISPATCH").tag("outcome", "success").timer().count()).isEqualTo(1L);
        assertThat(registry.get("xa.mass.pacer.dispatch.rounds")
                .tag("producer", "TASK_DISPATCH").tag("outcome", "failure").timer().count()).isEqualTo(1L);
        assertThat(registry.get("xa.mass.pacer.dispatch.items")
                .tag("producer", "TASK_DISPATCH").tag("kind", "published").counter().count()).isEqualTo(11.0);
        assertThat(registry.get("xa.mass.pacer.dispatch.items")
                .tag("producer", "ELIGIBILITY_REFILL").tag("kind", "refilled").counter().count()).isEqualTo(5.0);
        assertThat(registry.get("xa.mass.pacer.result.batches")
                .tag("lane", "TASK_SUCCESS").tag("outcome", "success").timer().count()).isEqualTo(1L);
        assertThat(registry.get("xa.mass.pacer.result.items")
                .tag("lane", "TASK_SUCCESS").tag("outcome", "success").counter().count()).isEqualTo(8.0);
        assertThat(registry.get("xa.mass.pacer.result.items")
                .tag("lane", "TASK_FAILURE").tag("outcome", "failure").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("xa.mass.pacer.result.consume.failures")
                .tag("lane", "TASK_OBSERVATION").counter().count()).isEqualTo(1.0);

        // 3. Task RPC & Direct Call registries
        var rpcProps = new TaskRpcProperties(
                Duration.ofDays(1), 5_000L, 10_000L, 1, 10, 16, 50L, 100L, 250L, Map.of()
        );
        var rpcRegistry = new TaskRpcWaitRegistry(rpcProps);
        rpcRegistry.setMetricsSink(recorder);

        var directProps = new DirectCallProperties(3_000L, 10_000L, 10, 1);
        var directRegistry = new DirectCallRegistry(directProps);
        directRegistry.setMetricsSink(recorder);

        // 4. Read-only bindings
        var pacer = mock(KernelPacerRuntime.class);
        when(pacer.isRunning()).thenReturn(true);
        WorkerObservationMetrics obsMetrics = new WorkerObservationMetrics() {
            @Override public long enqueuedBatches() { return 14L; }
            @Override public long droppedBatches() { return 2L; }
            @Override public long failedBatches() { return 1L; }
            @Override public int queueSize() { return 3; }
        };
        RuntimeMetricsBinder.bindPacer(registry, pacer);
        RuntimeMetricsBinder.bindRpcWaiters(registry, rpcRegistry);
        RuntimeMetricsBinder.bindDirectCalls(registry, directRegistry);
        RuntimeMetricsBinder.bindWorkerObservations(registry, obsMetrics);

        assertThat(registry.get("xa.mass.pacer.running").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("xa.mass.worker_observation.batches").tag("outcome", "enqueued").functionCounter().count())
                .isEqualTo(14.0);
        assertThat(registry.get("xa.mass.worker_observation.batches").tag("outcome", "dropped").functionCounter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("xa.mass.worker_observation.batches").tag("outcome", "failed").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("xa.mass.worker_observation.queue.size").gauge().value()).isEqualTo(3.0);

        // Exercise RPC waiter admission, capacity rejection, immediate hit, and observed completion
        assertThat(rpcRegistry.tryRegister("t1", List.of("m1"), Map.of(), new DeferredResult<>())).isTrue();
        assertThat(registry.get("xa.mass.rpc.waiters.active").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("xa.mass.rpc.observations.pending").gauge().value()).isEqualTo(1.0);

        assertThat(rpcRegistry.tryRegister("t1", List.of("m2"), Map.of(), new DeferredResult<>())).isFalse();
        assertThat(registry.get("xa.mass.rpc.wait").tag("outcome", "rejected").timer().count()).isEqualTo(1L);

        var immediateResult = TaskItemResult.succeeded("{}");
        assertThat(rpcRegistry.tryRegister("t1", List.of("m-fast"), Map.of("m-fast", immediateResult), new DeferredResult<>()))
                .isTrue();
        assertThat(registry.get("xa.mass.rpc.wait").tag("outcome", "immediate").timer().count()).isEqualTo(1L);

        rpcRegistry.completeResult("t1", "m1", TaskItemResult.succeeded("{}"));
        assertThat(registry.get("xa.mass.rpc.waiters.active").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("xa.mass.rpc.observations.pending").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("xa.mass.rpc.wait").tag("outcome", "observed").timer().count()).isEqualTo(1L);

        // Exercise Direct Call batch admission, capacity exceeded, and completion
        var cmd = DeliveryCommand.create(
                DeliveryEndpoint.SERVER, DeliveryEndpoint.WORKER, "ping", System.currentTimeMillis() + 5_000L, "{}", "fwd"
        );
        directRegistry.registerBatch("dc-1", List.of(
                TargetPlan.command("w1", "corr-1", "adapter-1", DirectTarget.worker("w1"), cmd),
                TargetPlan.rejected("w2", TargetOutcomeReason.NOT_FOUND)
        ));
        assertThat(registry.get("xa.mass.direct_call.targets.pending").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("xa.mass.direct_call.batches.active").gauge().value()).isEqualTo(1.0);

        assertThatThrownBy(() -> directRegistry.registerBatch("dc-2", List.of(
                TargetPlan.command("w3", "corr-2", "adapter-1", DirectTarget.worker("w3"), cmd)
        ))).isInstanceOf(ServerException.class);
        assertThat(registry.get("xa.mass.direct_call.batch").tag("outcome", "capacity_exceeded").timer().count())
                .isEqualTo(1L);

        directRegistry.completeTargets(Map.of(
                "corr-1", TargetOutcome.observed("worker.command.succeeded", "ok", "{}")
        ));
        assertThat(registry.get("xa.mass.direct_call.targets.pending").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("xa.mass.direct_call.batches.active").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("xa.mass.direct_call.batch").tag("outcome", "completed").timer().count()).isEqualTo(1L);
        assertThat(registry.get("xa.mass.direct_call.targets").tag("status", "observed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("xa.mass.direct_call.targets").tag("status", "rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void sinkExceptionsNeverDisruptRpcOrDirectCallCompletion() {
        var rpcProps = new TaskRpcProperties(
                Duration.ofDays(1), 5_000L, 10_000L, 10, 100, 16, 50L, 100L, 250L, Map.of()
        );
        var rpcRegistry = new TaskRpcWaitRegistry(rpcProps);
        rpcRegistry.setMetricsSink((outcome, durationNanos) -> {
            throw new RuntimeException("rpc metrics boom");
        });
        var deferred = new DeferredResult<Map<String, com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse>>();
        assertThat(rpcRegistry.tryRegister("t1", List.of("m1"), Map.of(), deferred)).isTrue();
        rpcRegistry.completeResult("t1", "m1", TaskItemResult.succeeded("{}"));
        assertThat(deferred.hasResult()).isTrue();

        var directProps = new DirectCallProperties(3_000L, 10_000L, 10, 10);
        var directRegistry = new DirectCallRegistry(directProps);
        directRegistry.setMetricsSink(new DirectCallRegistry.MetricsSink() {
            @Override
            public void onBatchCompleted(String outcome, long durationNanos) {
                throw new RuntimeException("direct metrics boom");
            }
        });
        var handle = directRegistry.registerBatch("dc-1", List.of(
                TargetPlan.rejected("w1", TargetOutcomeReason.NOT_FOUND)
        ));
        assertThat(handle.completion().toCompletableFuture()).isCompleted();
    }
}
