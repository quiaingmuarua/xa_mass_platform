package com.xa.mass.kernel.pacer;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReport;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class ResultConvergenceApplication {

    private static final System.Logger LOGGER = System.getLogger(
            ResultConvergenceApplication.class.getName()
    );

    private final Object lifecycleLock = new Object();
    private final List<ResultLane> lanes;
    private final int globalMaxConcurrency;
    private Thread dispatcher;
    private ExecutorService batchExecutor;
    private CountDownLatch stopSignal;
    private State state = State.STOPPED;

    ResultConvergenceApplication(
            List<ResultLane> lanes,
            int globalMaxConcurrency
    ) {
        if (lanes == null || lanes.isEmpty()) {
            throw new IllegalArgumentException("lanes must not be empty");
        }
        if (globalMaxConcurrency < 1) {
            throw new IllegalArgumentException(
                    "globalMaxConcurrency must be positive"
            );
        }
        EnumMap<ResultLaneId, ResultLane> unique =
                new EnumMap<>(ResultLaneId.class);
        for (ResultLane lane : lanes) {
            Objects.requireNonNull(lane, "lanes must not contain null");
            if (lane.maxConcurrency() > globalMaxConcurrency) {
                throw new IllegalArgumentException(
                        "lane maxConcurrency exceeds global capacity: "
                                + lane.id()
                );
            }
            if (unique.putIfAbsent(lane.id(), lane) != null) {
                throw new IllegalArgumentException(
                        "duplicate Result lane: " + lane.id()
                );
            }
        }
        this.lanes = unique.values().stream()
                .sorted(Comparator.comparingInt(
                        lane -> lane.id().priority()
                ))
                .toList();
        this.globalMaxConcurrency = globalMaxConcurrency;
    }

    void start() {
        synchronized (lifecycleLock) {
            if (dispatcher != null || state != State.STOPPED) {
                throw new IllegalStateException(
                        "Result Convergence application is already started"
                );
            }
            CountDownLatch signal = new CountDownLatch(1);
            BlockingQueue<LaneCompletion> completions =
                    new LinkedBlockingQueue<>();
            ThreadFactory batchThreads = Thread.ofVirtual()
                    .name("result-convergence-batch-", 0)
                    .factory();
            ExecutorService executor = Executors.newThreadPerTaskExecutor(
                    batchThreads
            );
            Thread started = new Thread(
                    () -> runLoop(signal, completions, executor),
                    "result-convergence-dispatcher"
            );
            started.setDaemon(false);
            stopSignal = signal;
            batchExecutor = executor;
            dispatcher = started;
            state = State.RUNNING;
            started.start();
        }
    }

    void stop(long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive"
            );
        }
        Thread current;
        ExecutorService executor;
        CountDownLatch signal;
        synchronized (lifecycleLock) {
            current = dispatcher;
            executor = batchExecutor;
            signal = stopSignal;
            if (current == null || executor == null || signal == null) {
                return;
            }
            state = State.STOPPING;
            signal.countDown();
            current.interrupt();
        }

        long deadline = System.nanoTime()
                + Duration.ofMillis(timeoutMillis).toNanos();
        join(current, remainingMillis(deadline));
        if (current.isAlive()) {
            executor.shutdownNow();
            failStoppedState();
            throw new IllegalStateException(
                    "Result Convergence dispatcher did not stop within "
                            + "its budget"
            );
        }
        if (!awaitTermination(executor, remainingMillis(deadline))) {
            executor.shutdownNow();
            failStoppedState();
            throw new IllegalStateException(
                    "Result Convergence batches did not stop within "
                            + "their budget"
            );
        }
        synchronized (lifecycleLock) {
            if (dispatcher == current) {
                dispatcher = null;
                batchExecutor = null;
                stopSignal = null;
                state = State.STOPPED;
            }
        }
    }

    boolean isRunning() {
        synchronized (lifecycleLock) {
            refreshDeadDispatcher();
            return state == State.RUNNING
                    && dispatcher != null
                    && dispatcher.isAlive()
                    && batchExecutor != null
                    && !batchExecutor.isShutdown();
        }
    }

    String state() {
        synchronized (lifecycleLock) {
            refreshDeadDispatcher();
            return state.name();
        }
    }

    private void runLoop(
            CountDownLatch signal,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor
    ) {
        Map<ResultLaneId, LaneRuntime> runtimes = new EnumMap<>(
                ResultLaneId.class
        );
        for (ResultLane lane : lanes) {
            runtimes.put(lane.id(), new LaneRuntime(lane));
        }
        int globalInFlight = 0;
        boolean stopping = false;
        try {
            while (signal.getCount() > 0) {
                globalInFlight = drainCompletions(
                        runtimes,
                        completions,
                        globalInFlight
                );
                globalInFlight = dispatchAvailable(
                        runtimes,
                        completions,
                        executor,
                        signal,
                        globalInFlight
                );
                if (signal.getCount() == 0) {
                    stopping = true;
                    break;
                }
                LaneCompletion completion = waitForWork(
                        runtimes,
                        completions,
                        globalInFlight
                );
                if (completion != null) {
                    globalInFlight = applyCompletion(
                            runtimes,
                            completion,
                            globalInFlight
                    );
                }
            }
            stopping = true;
        } catch (InterruptedException interrupted) {
            if (signal.getCount() == 0) {
                stopping = true;
            } else {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Result Convergence dispatcher was interrupted",
                        interrupted
                );
            }
        } finally {
            if (stopping) {
                executor.shutdown();
            } else {
                executor.shutdownNow();
            }
            synchronized (lifecycleLock) {
                if (dispatcher == Thread.currentThread()
                        && state != State.STOPPING) {
                    state = State.FAILED;
                }
            }
        }
    }

    private int dispatchAvailable(
            Map<ResultLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            ExecutorService executor,
            CountDownLatch signal,
            int globalInFlight
    ) {
        while (signal.getCount() > 0
                && globalInFlight < globalMaxConcurrency) {
            ResultLane lane = selectEligibleLane(
                    runtimes,
                    System.nanoTime()
            );
            if (lane == null) {
                break;
            }
            LaneRuntime runtime = runtimes.get(lane.id());
            List<DeliveryReport> batch;
            try {
                batch = lane.consumer().consume(lane.batchLimit());
                if (batch == null) {
                    throw new IllegalStateException(
                            "Result lane consumer returned null"
                    );
                }
            } catch (RuntimeException failure) {
                deferLane(runtime);
                logFailure(lane, "consume", 0, failure);
                continue;
            }
            if (batch.isEmpty()) {
                deferLane(runtime);
                continue;
            }
            List<DeliveryReport> immutableBatch = List.copyOf(batch);
            runtime.inflight++;
            globalInFlight++;
            try {
                executor.submit(() -> executeBatch(
                        lane,
                        immutableBatch,
                        completions
                ));
            } catch (RejectedExecutionException failure) {
                runtime.inflight--;
                globalInFlight--;
                throw new IllegalStateException(
                        "Result Convergence executor rejected lane="
                                + lane.id(),
                        failure
                );
            }
        }
        return globalInFlight;
    }

    private ResultLane selectEligibleLane(
            Map<ResultLaneId, LaneRuntime> runtimes,
            long nowNanos
    ) {
        ResultLane selected = null;
        for (ResultLane candidate : lanes) {
            LaneRuntime candidateRuntime = runtimes.get(candidate.id());
            if (candidateRuntime.inflight >= candidate.maxConcurrency()
                    || nowNanos < candidateRuntime.nextEligibleNanos) {
                continue;
            }
            if (selected == null || preferred(
                    candidate,
                    candidateRuntime,
                    selected,
                    runtimes.get(selected.id())
            )) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static boolean preferred(
            ResultLane candidate,
            LaneRuntime candidateRuntime,
            ResultLane selected,
            LaneRuntime selectedRuntime
    ) {
        long candidateRatio = (long) candidateRuntime.inflight
                * selected.targetConcurrency();
        long selectedRatio = (long) selectedRuntime.inflight
                * candidate.targetConcurrency();
        if (candidateRatio != selectedRatio) {
            return candidateRatio < selectedRatio;
        }
        return candidate.id().priority() < selected.id().priority();
    }

    private static void executeBatch(
            ResultLane lane,
            List<DeliveryReport> batch,
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

    private static int drainCompletions(
            Map<ResultLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            int globalInFlight
    ) {
        LaneCompletion completion;
        while ((completion = completions.poll()) != null) {
            globalInFlight = applyCompletion(
                    runtimes,
                    completion,
                    globalInFlight
            );
        }
        return globalInFlight;
    }

    private static int applyCompletion(
            Map<ResultLaneId, LaneRuntime> runtimes,
            LaneCompletion completion,
            int globalInFlight
    ) {
        LaneRuntime runtime = runtimes.get(completion.id());
        if (runtime == null || runtime.inflight < 1
                || globalInFlight < 1) {
            throw new IllegalStateException(
                    "Unexpected Result lane completion: "
                            + completion.id()
            );
        }
        runtime.inflight--;
        globalInFlight--;
        if (completion.failure() == null) {
            return globalInFlight;
        }
        if (completion.failure() instanceof Error fatal) {
            throw fatal;
        }
        deferLane(runtime);
        logFailure(
                runtime.lane,
                "policy",
                completion.batchSize(),
                (RuntimeException) completion.failure()
        );
        return globalInFlight;
    }

    private LaneCompletion waitForWork(
            Map<ResultLaneId, LaneRuntime> runtimes,
            BlockingQueue<LaneCompletion> completions,
            int globalInFlight
    ) throws InterruptedException {
        if (globalInFlight >= globalMaxConcurrency) {
            return completions.take();
        }
        long now = System.nanoTime();
        long waitNanos = Long.MAX_VALUE;
        for (LaneRuntime runtime : runtimes.values()) {
            if (runtime.inflight < runtime.lane.maxConcurrency()) {
                waitNanos = Math.min(
                        waitNanos,
                        Math.max(0, runtime.nextEligibleNanos - now)
                );
            }
        }
        if (waitNanos == 0) {
            return null;
        }
        if (waitNanos == Long.MAX_VALUE) {
            return completions.take();
        }
        return completions.poll(waitNanos, TimeUnit.NANOSECONDS);
    }

    private static void deferLane(LaneRuntime runtime) {
        runtime.nextEligibleNanos = Math.max(
                runtime.nextEligibleNanos,
                nextEligible(runtime.lane)
        );
    }

    private static long nextEligible(ResultLane lane) {
        return Math.addExact(
                System.nanoTime(),
                lane.idlePollIntervalNanos()
        );
    }

    private static void logFailure(
            ResultLane lane,
            String operation,
            int batchSize,
            RuntimeException failure
    ) {
        LOGGER.log(
                System.Logger.Level.ERROR,
                "operation=resultConvergence.{0} lane={1} batchSize={2} "
                        + "failureType={3}",
                operation,
                lane.id(),
                batchSize,
                failure.getClass().getName()
        );
    }

    private static void join(Thread thread, long timeoutMillis) {
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Result Convergence shutdown was interrupted",
                    interrupted
            );
        }
    }

    private static boolean awaitTermination(
            ExecutorService executor,
            long timeoutMillis
    ) {
        try {
            return executor.awaitTermination(
                    timeoutMillis,
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Result Convergence batch shutdown was interrupted",
                    interrupted
            );
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(
                1,
                Duration.ofNanos(Math.max(
                        1,
                        deadlineNanos - System.nanoTime()
                )).toMillis()
        );
    }

    private void failStoppedState() {
        synchronized (lifecycleLock) {
            state = State.FAILED;
        }
    }

    private void refreshDeadDispatcher() {
        if (state == State.RUNNING
                && dispatcher != null
                && !dispatcher.isAlive()) {
            state = State.FAILED;
        }
    }

    private enum State {
        STOPPED,
        RUNNING,
        STOPPING,
        FAILED
    }

    private static final class LaneRuntime {

        private final ResultLane lane;
        private int inflight;
        private long nextEligibleNanos;

        private LaneRuntime(ResultLane lane) {
            this.lane = lane;
        }
    }

    private record LaneCompletion(
            ResultLaneId id,
            int batchSize,
            Throwable failure
    ) {
    }
}
