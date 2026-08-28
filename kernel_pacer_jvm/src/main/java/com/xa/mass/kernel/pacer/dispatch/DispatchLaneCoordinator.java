package com.xa.mass.kernel.pacer.dispatch;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

final class DispatchLaneCoordinator {

    private static final System.Logger LOGGER = System.getLogger(
            DispatchLaneCoordinator.class.getName()
    );

    private final DispatchTaskBatchFanout fanout;

    DispatchLaneCoordinator(DispatchTaskBatchFanout fanout) {
        this.fanout = Objects.requireNonNull(fanout, "fanout");
    }

    void run(
            CountDownLatch stopSignal,
            ExecutorService executor,
            List<DispatchLaneDefinition> lanes
    ) {
        Objects.requireNonNull(stopSignal, "stopSignal");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(lanes, "lanes");
        BlockingQueue<LaneCompletion> completions =
                new LinkedBlockingQueue<>();
        Map<DispatchLaneId, LaneRuntime> runtimes = new EnumMap<>(
                DispatchLaneId.class
        );
        lanes.forEach(lane -> {
            if (runtimes.put(lane.id(), new LaneRuntime(lane)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Dispatch lane: " + lane.id()
                );
            }
        });
        boolean stopping = false;
        try {
            while (stopSignal.getCount() > 0) {
                drainCompletions(runtimes, completions);
                dispatchEligible(
                        runtimes,
                        completions,
                        executor,
                        stopSignal
                );
                if (stopSignal.getCount() == 0) {
                    stopping = true;
                    break;
                }
                LaneCompletion completion = waitForWork(
                        runtimes,
                        completions
                );
                if (completion != null) {
                    applyCompletion(runtimes, completion);
                }
            }
            stopping = true;
        } catch (InterruptedException interrupted) {
            if (stopSignal.getCount() == 0) {
                stopping = true;
            } else {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Dispatch Convergence coordinator was interrupted",
                        interrupted
                );
            }
        } finally {
            if (stopping) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
        }
    }

    private void dispatchEligible(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor,
            CountDownLatch stopSignal
    ) {
        long now = System.nanoTime();
        Set<DispatchLaneId> eligible = EnumSet.noneOf(DispatchLaneId.class);
        runtimes.forEach((laneId, runtime) -> {
            if (runtime.idleAndEligible(now)) {
                eligible.add(laneId);
            }
        });
        if (eligible.isEmpty() || stopSignal.getCount() == 0) {
            return;
        }
        Map<DispatchLaneId, List<DueTaskObservation>> batches;
        try {
            batches = fanout.acquireFor(
                    eligible,
                    AssignmentDispatchConfig.TASK_BATCH_LIMIT
            );
        } catch (RuntimeException failure) {
            eligible.forEach(laneId -> deferLane(runtimes.get(laneId)));
            logFailure("taskSource", null, 0, failure);
            return;
        }
        if (stopSignal.getCount() == 0) {
            return;
        }
        for (DispatchLaneId laneId : eligible) {
            LaneRuntime runtime = Objects.requireNonNull(
                    runtimes.get(laneId),
                    "eligible lane runtime"
            );
            List<DueTaskObservation> batch = batches.get(laneId);
            if (batch == null || batch.isEmpty()) {
                deferLane(runtime);
            } else {
                submit(runtime, batch, completions, executor);
            }
        }
    }

    private static void submit(
            LaneRuntime runtime,
            List<DueTaskObservation> batch,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor
    ) {
        runtime.inflight = true;
        try {
            executor.submit(() -> executeBatch(
                    runtime.lane,
                    batch,
                    completions
            ));
        } catch (RejectedExecutionException failure) {
            runtime.inflight = false;
            throw new IllegalStateException(
                    "Dispatch Convergence executor rejected lane="
                            + runtime.lane.id(),
                    failure
            );
        }
    }

    private static void executeBatch(
            DispatchLaneDefinition lane,
            List<DueTaskObservation> batch,
            BlockingQueue<LaneCompletion> completions
    ) {
        Throwable failure = null;
        try {
            lane.policy().handle(batch);
        } catch (RuntimeException runtimeFailure) {
            failure = runtimeFailure;
        } catch (Error fatalFailure) {
            failure = fatalFailure;
            throw fatalFailure;
        } finally {
            completions.offer(new LaneCompletion(
                    lane.id(),
                    batch.size(),
                    failure
            ));
        }
    }

    private static void drainCompletions(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions
    ) {
        LaneCompletion completion;
        while ((completion = completions.poll()) != null) {
            applyCompletion(runtimes, completion);
        }
    }

    private static void applyCompletion(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            LaneCompletion completion
    ) {
        LaneRuntime runtime = runtimes.get(completion.id());
        if (runtime == null || !runtime.inflight) {
            throw new IllegalStateException(
                    "Unexpected Dispatch lane completion: "
                            + completion.id()
            );
        }
        runtime.inflight = false;
        deferLane(runtime);
        if (completion.failure() == null) {
            return;
        }
        if (completion.failure() instanceof Error fatal) {
            throw fatal;
        }
        logFailure(
                "policy",
                runtime.lane.id(),
                completion.batchSize(),
                (RuntimeException) completion.failure()
        );
    }

    private static LaneCompletion waitForWork(
            Map<DispatchLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions
    ) throws InterruptedException {
        long now = System.nanoTime();
        long waitNanos = Long.MAX_VALUE;
        boolean inflight = false;
        for (LaneRuntime runtime : runtimes.values()) {
            if (runtime.inflight) {
                inflight = true;
                continue;
            }
            waitNanos = Math.min(
                    waitNanos,
                    Math.max(0, runtime.nextEligibleNanos - now)
            );
        }
        if (waitNanos == 0) {
            return null;
        }
        if (waitNanos == Long.MAX_VALUE) {
            return inflight ? completions.take() : null;
        }
        return completions.poll(waitNanos, TimeUnit.NANOSECONDS);
    }

    private static void deferLane(LaneRuntime runtime) {
        runtime.nextEligibleNanos = Math.addExact(
                System.nanoTime(),
                runtime.lane.intervalNanos()
        );
    }

    private static void logFailure(
            String operation,
            DispatchLaneId lane,
            int batchSize,
            RuntimeException failure
    ) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "operation=dispatchConvergence.{0} lane={1} batchSize={2} "
                        + "failureType={3}",
                operation,
                lane,
                batchSize,
                failure.getClass().getName()
        );
    }

    private static final class LaneRuntime {
        private final DispatchLaneDefinition lane;
        private boolean inflight;
        private long nextEligibleNanos;

        private LaneRuntime(DispatchLaneDefinition lane) {
            this.lane = lane;
        }

        private boolean idleAndEligible(long nowNanos) {
            return !inflight && nowNanos >= nextEligibleNanos;
        }
    }

    private record LaneCompletion(
            DispatchLaneId id,
            int batchSize,
            Throwable failure
    ) {
    }
}
