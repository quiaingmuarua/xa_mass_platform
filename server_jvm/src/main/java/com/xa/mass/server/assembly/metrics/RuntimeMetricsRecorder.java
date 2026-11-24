package com.xa.mass.server.assembly.metrics;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.server.api.ApiErrorObserver;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcomeStatus;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.task.call.TaskRpcWaitRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Sole Micrometer event recorder for Server front doors, Pacer batch summaries,
 * and in-process wait registries. All tags are strictly bounded enums/constants.
 */
final class RuntimeMetricsRecorder
        implements ApiErrorObserver,
                KernelPacerRuntime.MetricsSink,
                TaskRpcWaitRegistry.WaitMetricsSink,
                DirectCallRegistry.MetricsSink {

    private final MeterRegistry registry;

    RuntimeMetricsRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void onError(ServerErrorCode errorCode, int httpStatus) {
        if (errorCode == null) {
            return;
        }
        Counter.builder("xa.mass.api.errors")
                .description("Server API error responses by ServerErrorCode and HTTP status")
                .tag("code", Integer.toString(errorCode.code()))
                .tag("error", errorCode.name())
                .tag("status", Integer.toString(httpStatus))
                .register(registry)
                .increment();
    }

    @Override
    public void onDispatchRound(
            String producer,
            int outputCount,
            long durationNanos,
            boolean failed
    ) {
        if (producer == null) {
            return;
        }
        Timer.builder("xa.mass.pacer.dispatch.rounds")
                .description("Kernel Pacer Dispatch producer round duration")
                .tag("producer", producer)
                .tag("outcome", failed ? "failure" : "success")
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        if (outputCount > 0) {
            String kind = switch (producer) {
                case "TASK_DISPATCH" -> "published";
                case "ELIGIBILITY_REFILL" -> "refilled";
                default -> null;
            };
            if (kind != null) {
                Counter.builder("xa.mass.pacer.dispatch.items")
                        .description("Kernel Pacer Dispatch producer item outputs")
                        .tag("producer", producer)
                        .tag("kind", kind)
                        .register(registry)
                        .increment(outputCount);
            }
        }
    }

    @Override
    public void onResultBatch(
            String lane,
            int batchSize,
            long durationNanos,
            boolean failed
    ) {
        if (lane == null) {
            return;
        }
        String outcome = failed ? "failure" : "success";
        Timer.builder("xa.mass.pacer.result.batches")
                .description("Kernel Pacer Result lane batch processing duration")
                .tag("lane", lane)
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        if (batchSize > 0) {
            Counter.builder("xa.mass.pacer.result.items")
                    .description("Kernel Pacer Result lane processed reports")
                    .tag("lane", lane)
                    .tag("outcome", outcome)
                    .register(registry)
                    .increment(batchSize);
        }
    }

    @Override
    public void onResultConsumeFailure(String lane) {
        if (lane == null) {
            return;
        }
        Counter.builder("xa.mass.pacer.result.consume.failures")
                .description("Kernel Pacer Result lane consume failures")
                .tag("lane", lane)
                .register(registry)
                .increment();
    }

    @Override
    public void onWaitCompleted(String outcome, long durationNanos) {
        if (outcome == null) {
            return;
        }
        Timer.builder("xa.mass.rpc.wait")
                .description("Managed Task RPC waiter duration by terminal outcome")
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public void onBatchCompleted(String outcome, long durationNanos) {
        if (outcome == null) {
            return;
        }
        Timer.builder("xa.mass.direct_call.batch")
                .description("Direct Call batch wait duration by terminal outcome")
                .tag("outcome", outcome)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public void onTargetOutcome(TargetOutcomeStatus status, int count) {
        if (status == null || count <= 0) {
            return;
        }
        Counter.builder("xa.mass.direct_call.targets")
                .description("Direct Call target outcomes by status")
                .tag("status", status.name().toLowerCase(Locale.ROOT))
                .register(registry)
                .increment(count);
    }

    @Override
    public void onCapacityExceeded() {
        Timer.builder("xa.mass.direct_call.batch")
                .description("Direct Call batch wait duration by terminal outcome")
                .tag("outcome", "capacity_exceeded")
                .register(registry)
                .record(0L, TimeUnit.NANOSECONDS);
    }
}
