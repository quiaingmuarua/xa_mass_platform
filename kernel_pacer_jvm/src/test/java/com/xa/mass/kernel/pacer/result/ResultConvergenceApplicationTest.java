package com.xa.mass.kernel.pacer.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResultConvergenceApplicationTest {

    @Test
    void allocatesInitialCapacityByWeightedFairShare() throws Exception {
        BlockingPolicies policies = new BlockingPolicies(10);
        ResultConvergenceApplication application = application(
                10,
                endlessLane(
                        ResultLaneId.TASK_SUCCESS,
                        4,
                        10,
                        policies
                ),
                endlessLane(
                        ResultLaneId.TASK_FAILURE,
                        3,
                        10,
                        policies
                ),
                endlessLane(
                        ResultLaneId.ADAPTER_EVIDENCE,
                        3,
                        10,
                        policies
                )
        );

        application.start();
        try {
            assertTrue(policies.started.await(1, TimeUnit.SECONDS));
            assertEquals(10, policies.globalActive.get());
            assertEquals(4, policies.active(ResultLaneId.TASK_SUCCESS));
            assertEquals(3, policies.active(ResultLaneId.TASK_FAILURE));
            assertEquals(3, policies.active(
                    ResultLaneId.ADAPTER_EVIDENCE
            ));
            assertEquals(10, policies.maximumGlobalActive.get());
            assertTrue(policies.allVirtual.get());
        } finally {
            policies.release.countDown();
            application.stop(1_000);
        }
    }

    @Test
    void productionQuotasFavorSuccessAndKeepEvidenceSingleFlight()
            throws Exception {
        BlockingPolicies policies = new BlockingPolicies(10);
        ResultConvergenceApplication application = application(
                ResultConvergenceConfig.GLOBAL_MAX_CONCURRENCY,
                endlessLane(
                        ResultLaneId.TASK_SUCCESS,
                        ResultConvergenceConfig
                                .TASK_SUCCESS_TARGET_CONCURRENCY,
                        ResultConvergenceConfig
                                .TASK_SUCCESS_MAX_CONCURRENCY,
                        policies
                ),
                endlessLane(
                        ResultLaneId.TASK_FAILURE,
                        ResultConvergenceConfig
                                .TASK_FAILURE_TARGET_CONCURRENCY,
                        ResultConvergenceConfig
                                .TASK_FAILURE_MAX_CONCURRENCY,
                        policies
                ),
                endlessLane(
                        ResultLaneId.ADAPTER_EVIDENCE,
                        ResultConvergenceConfig
                                .ADAPTER_EVIDENCE_TARGET_CONCURRENCY,
                        ResultConvergenceConfig
                                .ADAPTER_EVIDENCE_MAX_CONCURRENCY,
                        policies
                )
        );

        application.start();
        try {
            assertTrue(policies.started.await(1, TimeUnit.SECONDS));
            assertEquals(6, policies.active(ResultLaneId.TASK_SUCCESS));
            assertEquals(3, policies.active(ResultLaneId.TASK_FAILURE));
            assertEquals(1, policies.active(
                    ResultLaneId.ADAPTER_EVIDENCE
            ));
            assertEquals(10, policies.maximumGlobalActive.get());
        } finally {
            policies.release.countDown();
            application.stop(1_000);
        }
    }

    @Test
    void runtimePolicyFailureBacksOffOnlyItsLaneAndThenContinues()
            throws Exception {
        AtomicInteger consumes = new AtomicInteger();
        AtomicInteger policies = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(1);
        ResultLane lane = new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                100,
                20,
                1,
                1,
                ignored -> consumes.incrementAndGet() <= 2
                        ? List.of(report())
                        : List.of(),
                ignored -> {
                    if (policies.incrementAndGet() == 1) {
                        throw new IllegalStateException("discard this batch");
                    }
                    recovered.countDown();
                }
        );
        ResultConvergenceApplication application = application(1, lane);

        application.start();
        try {
            assertTrue(recovered.await(1, TimeUnit.SECONDS));
            assertTrue(application.isRunning());
            assertEquals(2, policies.get());
        } finally {
            application.stop(1_000);
        }
    }

    @Test
    void consumeFailureBacksOffOnlyItsLaneAndThenContinues()
            throws Exception {
        AtomicInteger failedLaneConsumes = new AtomicInteger();
        CountDownLatch otherLaneFinished = new CountDownLatch(1);
        CountDownLatch failedLaneRecovered = new CountDownLatch(1);
        ResultConvergenceApplication application = application(
                2,
                new ResultLane(
                        ResultLaneId.TASK_SUCCESS,
                        100,
                        20,
                        1,
                        1,
                        ignored -> {
                            int attempt = failedLaneConsumes.incrementAndGet();
                            if (attempt == 1) {
                                throw new IllegalStateException(
                                        "temporary consume failure"
                                );
                            }
                            return attempt == 2
                                    ? List.of(report())
                                    : List.of();
                        },
                        ignored -> failedLaneRecovered.countDown()
                ),
                new ResultLane(
                        ResultLaneId.TASK_FAILURE,
                        100,
                        20,
                        1,
                        1,
                        new OneBatchConsumer(),
                        ignored -> otherLaneFinished.countDown()
                )
        );

        application.start();
        try {
            assertTrue(otherLaneFinished.await(1, TimeUnit.SECONDS));
            assertTrue(failedLaneRecovered.await(1, TimeUnit.SECONDS));
            assertTrue(application.isRunning());
            assertTrue(failedLaneConsumes.get() >= 2);
        } finally {
            application.stop(1_000);
        }
    }

    @Test
    void emptyLaneDoesNotOccupyCapacityOrBlockAnotherLane()
            throws Exception {
        AtomicInteger emptyConsumes = new AtomicInteger();
        CountDownLatch otherLaneFinished = new CountDownLatch(1);
        ResultConvergenceApplication application = application(
                1,
                new ResultLane(
                        ResultLaneId.TASK_SUCCESS,
                        100,
                        50,
                        1,
                        1,
                        ignored -> {
                            emptyConsumes.incrementAndGet();
                            return List.of();
                        },
                        ignored -> {
                        }
                ),
                new ResultLane(
                        ResultLaneId.TASK_FAILURE,
                        100,
                        50,
                        1,
                        1,
                        new OneBatchConsumer(),
                        ignored -> otherLaneFinished.countDown()
                )
        );

        application.start();
        try {
            assertTrue(otherLaneFinished.await(1, TimeUnit.SECONDS));
            assertTrue(emptyConsumes.get() >= 1);
            assertTrue(application.isRunning());
        } finally {
            application.stop(1_000);
        }
    }

    @Test
    void executorRejectionFailsApplicationAfterDestructiveConsume()
            throws Exception {
        CountDownLatch consumeStarted = new CountDownLatch(1);
        CountDownLatch releaseConsume = new CountDownLatch(1);
        ResultLane lane = new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                100,
                10,
                1,
                1,
                ignored -> {
                    consumeStarted.countDown();
                    await(releaseConsume);
                    return List.of(report());
                },
                ignored -> {
                }
        );
        ResultConvergenceApplication application = application(1, lane);
        application.start();
        assertTrue(consumeStarted.await(1, TimeUnit.SECONDS));

        batchExecutor(application).shutdown();
        releaseConsume.countDown();
        awaitCondition(() -> "FAILED".equals(application.state()));
        assertFalse(application.isRunning());
        application.stop(1_000);
        assertEquals("STOPPED", application.state());
    }

    @Test
    void stopPreventsNewConsumptionWhileWaitingForInflightBatch()
            throws Exception {
        AtomicInteger consumes = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ResultLane lane = new ResultLane(
                ResultLaneId.TASK_FAILURE,
                100,
                10,
                1,
                1,
                ignored -> {
                    consumes.incrementAndGet();
                    return List.of(report());
                },
                ignored -> {
                    started.countDown();
                    await(release);
                }
        );
        ResultConvergenceApplication application = application(1, lane);
        application.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));

        AtomicBoolean stopped = new AtomicBoolean();
        Thread stopper = Thread.ofPlatform().start(() -> {
            application.stop(1_000);
            stopped.set(true);
        });
        awaitCondition(() -> "STOPPING".equals(application.state()));
        Thread.sleep(30);
        assertEquals(1, consumes.get());
        release.countDown();
        stopper.join(1_000);
        assertFalse(stopper.isAlive());
        assertTrue(stopped.get());
        assertEquals("STOPPED", application.state());
    }

    @Test
    void fatalPolicyErrorFailsTheApplication() throws Exception {
        ResultLane lane = new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                100,
                10,
                1,
                1,
                new OneBatchConsumer(),
                ignored -> {
                    throw new AssertionError("fatal");
                }
        );
        ResultConvergenceApplication application = application(1, lane);

        application.start();
        awaitCondition(() -> "FAILED".equals(application.state()));
        assertFalse(application.isRunning());
        application.stop(1_000);
        assertEquals("STOPPED", application.state());
    }

    @Test
    void validatesCapacityAndLifecycle() {
        assertThrows(IllegalArgumentException.class, () -> new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                100,
                10,
                2,
                1,
                ignored -> List.of(),
                ignored -> {
                }
        ));
        ResultLane lane = new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                100,
                100,
                1,
                2,
                ignored -> List.of(),
                ignored -> {
                }
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> application(1, lane)
        );

        ResultConvergenceApplication application = application(2, lane);
        application.start();
        assertThrows(IllegalStateException.class, application::start);
        application.stop(1_000);
        application.stop(1_000);
        assertEquals("STOPPED", application.state());
    }

    private static ResultConvergenceApplication application(
            int globalMaxConcurrency,
            ResultLane... lanes
    ) {
        return new ResultConvergenceApplication(
                List.of(lanes),
                globalMaxConcurrency
        );
    }

    private static ExecutorService batchExecutor(
            ResultConvergenceApplication application
    ) throws ReflectiveOperationException {
        Field field = ResultConvergenceApplication.class.getDeclaredField(
                "batchExecutor"
        );
        field.setAccessible(true);
        return (ExecutorService) field.get(application);
    }

    private static ResultLane endlessLane(
            ResultLaneId id,
            int targetConcurrency,
            int maxConcurrency,
            BlockingPolicies policies
    ) {
        return new ResultLane(
                id,
                100,
                20,
                targetConcurrency,
                maxConcurrency,
                ignored -> List.of(report()),
                ignored -> policies.block(id)
        );
    }

    private static DeliveryReport report() {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.TASK,
                "extension.worker.test",
                "200",
                "payload",
                "forward"
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitCondition(java.util.function.BooleanSupplier ready)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(ready.getAsBoolean());
    }

    private static final class OneBatchConsumer
            implements ResultBatchConsumer {

        private final AtomicBoolean first = new AtomicBoolean(true);

        @Override
        public List<DeliveryReport> consume(int limit) {
            return first.compareAndSet(true, false)
                    ? List.of(report())
                    : List.of();
        }
    }

    private static final class BlockingPolicies {

        private final CountDownLatch started;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger globalActive = new AtomicInteger();
        private final AtomicInteger maximumGlobalActive = new AtomicInteger();
        private final AtomicBoolean allVirtual = new AtomicBoolean(true);
        private final Map<ResultLaneId, AtomicInteger> activeByLane =
                new EnumMap<>(ResultLaneId.class);

        private BlockingPolicies(int expectedStarts) {
            started = new CountDownLatch(expectedStarts);
            for (ResultLaneId id : ResultLaneId.values()) {
                activeByLane.put(id, new AtomicInteger());
            }
        }

        private void block(ResultLaneId id) {
            int global = globalActive.incrementAndGet();
            maximumGlobalActive.accumulateAndGet(global, Math::max);
            activeByLane.get(id).incrementAndGet();
            allVirtual.compareAndSet(
                    true,
                    Thread.currentThread().isVirtual()
            );
            started.countDown();
            try {
                await(release);
            } finally {
                activeByLane.get(id).decrementAndGet();
                globalActive.decrementAndGet();
            }
        }

        private int active(ResultLaneId id) {
            return activeByLane.get(id).get();
        }
    }
}
