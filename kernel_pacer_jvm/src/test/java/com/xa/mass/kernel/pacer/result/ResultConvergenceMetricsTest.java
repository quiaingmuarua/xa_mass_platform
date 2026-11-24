package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.MetricsSink;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResultConvergenceMetricsTest {

    @Test
    void recordsResultBatchSuccessFailureAndConsumeFailuresAndIgnoresSinkErrors() throws Exception {
        record BatchMetric(String lane, int batchSize, long durationNanos, boolean failed) {}
        var batches = new CopyOnWriteArrayList<BatchMetric>();
        var consumeFailures = new CopyOnWriteArrayList<String>();
        var done = new CountDownLatch(3);

        MetricsSink sink = new MetricsSink() {
            @Override
            public void onResultBatch(String lane, int batchSize, long durationNanos, boolean failed) {
                batches.add(new BatchMetric(lane, batchSize, durationNanos, failed));
                done.countDown();
                throw new RuntimeException("sink boom must be ignored");
            }

            @Override
            public void onResultConsumeFailure(String lane) {
                consumeFailures.add(lane);
                done.countDown();
                throw new RuntimeException("consume sink boom must be ignored");
            }
        };

        var successCalls = new AtomicInteger();
        var failureCalls = new AtomicInteger();
        var consumeFailCalls = new AtomicInteger();
        var report = DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.TASK,
                "platform.worker.command.succeeded",
                "",
                "{}",
                "forward"
        );

        var successLane = new ResultLane(
                ResultLaneId.TASK_SUCCESS, 10, 10, 1, 1,
                limit -> successCalls.getAndIncrement() == 0 ? List.of(report, report) : List.of(),
                batch -> {}
        );
        var failureLane = new ResultLane(
                ResultLaneId.TASK_FAILURE, 10, 10, 1, 1,
                limit -> failureCalls.getAndIncrement() == 0 ? List.of(report) : List.of(),
                batch -> { throw new IllegalStateException("policy boom"); }
        );
        var obsLane = new ResultLane(
                ResultLaneId.TASK_OBSERVATION, 10, 10, 1, 1,
                limit -> {
                    if (consumeFailCalls.getAndIncrement() == 0) {
                        throw new IllegalStateException("redis boom");
                    }
                    return List.of();
                },
                batch -> {}
        );

        var app = new ResultConvergenceApplication(List.of(successLane, failureLane, obsLane), 4);
        app.setMetricsSink(sink);
        app.start();
        try {
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(app.isRunning());
        } finally {
            app.stop(2_000);
        }

        var successMetric = batches.stream().filter(m -> m.lane().equals("TASK_SUCCESS")).findFirst().orElseThrow();
        var failureMetric = batches.stream().filter(m -> m.lane().equals("TASK_FAILURE")).findFirst().orElseThrow();
        assertEquals(2, successMetric.batchSize());
        assertFalse(successMetric.failed());
        assertTrue(successMetric.durationNanos() >= 0L);

        assertEquals(1, failureMetric.batchSize());
        assertTrue(failureMetric.failed());
        assertTrue(consumeFailures.contains("TASK_OBSERVATION"));
    }
}
