package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerInvocation;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.handler.WorkerResult;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PollingWorkerRuntime implements WorkerRuntime {
    private final String workerId;
    private final String workerGroupId;
    private final Map<String, String> attributes;
    private final int maxMessages;
    private final long pollTimeoutMs;
    private final Duration pollInterval;
    private final Duration heartbeatInterval;
    private final Duration maxPollBackoff;
    private final WorkerRuntimeListener listener;
    private final WorkerDispatchProcessor dispatchProcessor;
    private final PollingWorkerProtocolDriver protocolDriver;
    private final WorkerRuntimeReporter reporter;
    private final ScheduledExecutorService executor;
    private final WorkerRuntimeMaintenanceLoop maintenanceLoop;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consecutiveHeartbeatFailures = new AtomicInteger();
    private volatile boolean offlineOnClose;

    private PollingWorkerRuntime(Builder builder) {
        this.workerId = requireText(builder.workerId, "workerId");
        this.workerGroupId = requireText(builder.workerGroupId, "workerGroupId");
        this.attributes = Map.copyOf(builder.attributes);
        this.maxMessages = builder.maxMessages;
        this.pollTimeoutMs = builder.pollTimeoutMs;
        this.pollInterval = builder.pollInterval;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.maxPollBackoff = builder.maxPollBackoff;
        this.listener = builder.listener;
        this.dispatchProcessor = new WorkerDispatchProcessor(workerId, builder.eventHandlers, listener);
        this.protocolDriver = new PollingWorkerProtocolDriver(
                builder.workerClient,
                workerId,
                maxMessages,
                pollTimeoutMs);
        this.reporter = new WorkerRuntimeReporter(builder.workerClient, builder.definition);
        this.executor = builder.executor == null
                ? Executors.newScheduledThreadPool(2, new SessionThreadFactory(workerId))
                : builder.executor;
        this.maintenanceLoop = new WorkerRuntimeMaintenanceLoop(executor);
        this.maintenanceLoop.addFixedDelayTask("heartbeat",
                heartbeatInterval,
                heartbeatInterval,
                this::heartbeatOnce);
    }

    public static Builder builder(WorkerClient workerClient, WorkerRuntimeDefinition definition) {
        return new Builder(workerClient, definition);
    }

    @Override
    public PollingWorkerRuntime start() {
        WorkerRuntimeStartupStep lastSuccessful = null;
        try {
            protocolDriver.open();
            offlineOnClose = true;
            lastSuccessful = WorkerRuntimeStartupStep.ONLINE;

            running.set(true);
            maintenanceLoop.start();
            lastSuccessful = WorkerRuntimeStartupStep.START_HEARTBEAT;

            executor.execute(this::pollLoop);
            lastSuccessful = WorkerRuntimeStartupStep.START_POLL;
            return this;
        } catch (Throwable failure) {
            running.set(false);
            executor.shutdownNow();
            WorkerRuntimeStartupStep failedStep = nextStepAfter(lastSuccessful);
            WorkerRuntimeStartupFailure startupFailure =
                    new WorkerRuntimeStartupFailure(workerId, failedStep, lastSuccessful, failure);
            listener.onStartupFailure(startupFailure);
            throw new WorkerRuntimeStartupException(startupFailure);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public String workerId() {
        return workerId;
    }

    @Override
    public String workerGroupId() {
        return workerGroupId;
    }

    @Override
    public String transportHint() {
        return "polling";
    }

    @Override
    public WorkerRuntimeReporter reporter() {
        return reporter;
    }

    public String sessionToken() {
        return protocolDriver.sessionToken();
    }

    @Override
    public void close() {
        boolean wasRunning = running.getAndSet(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(100L, pollTimeoutMs + 500L), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        if (offlineOnClose && wasRunning) {
            try {
                protocolDriver.close();
            } catch (Throwable failure) {
                listener.onShutdownFailure(workerId, failure);
            }
        }
    }

    private void heartbeatOnce() {
        if (!running.get()) {
            return;
        }
        try {
            protocolDriver.heartbeat();
            consecutiveHeartbeatFailures.set(0);
        } catch (Throwable failure) {
            int failures = consecutiveHeartbeatFailures.incrementAndGet();
            listener.onHeartbeatFailure(new WorkerRuntimeHeartbeatFailure(workerId, failures, failure));
        }
    }

    private void pollLoop() {
        int consecutiveFailures = 0;
        while (running.get()) {
            try {
                consecutiveFailures = 0;
                for (WorkerInvocation item : protocolDriver.poll()) {
                    handleItem(item);
                }
                sleep(pollInterval);
            } catch (Throwable failure) {
                consecutiveFailures++;
                listener.onPollFailure(new WorkerRuntimePollFailure(workerId, consecutiveFailures, failure));
                sleep(backoff(consecutiveFailures));
            }
        }
    }

    private void handleItem(WorkerInvocation item) {
        WorkerDispatchProcessor.ProcessedDispatch processed = dispatchProcessor.process(item);
        submitResult(processed.resultCorrelationRef(), processed.invocation(), processed.result());
    }

    private void submitResult(String resultCorrelationRef, WorkerInvocation invocation, WorkerResult result) {
        try {
            submitResultToWorkerApi(resultCorrelationRef, result);
        } catch (Throwable failure) {
            listener.onSubmitFailure(new WorkerRuntimeDispatchFailure(workerId, resultCorrelationRef, invocation, failure));
        }
    }

    private void submitResultToWorkerApi(String resultCorrelationRef, WorkerResult result) {
        protocolDriver.submitResult(resultCorrelationRef, result);
    }

    private Duration backoff(int consecutiveFailures) {
        long multiplier = Math.max(1L, Math.min(10L, consecutiveFailures));
        long millis = Math.min(maxPollBackoff.toMillis(), pollInterval.toMillis() * multiplier);
        return Duration.ofMillis(Math.max(1L, millis));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private static WorkerRuntimeStartupStep nextStepAfter(WorkerRuntimeStartupStep lastSuccessful) {
        if (lastSuccessful == null) {
            return WorkerRuntimeStartupStep.ONLINE;
        }
        WorkerRuntimeStartupStep[] steps = WorkerRuntimeStartupStep.values();
        int next = lastSuccessful.ordinal() + 1;
        return next < steps.length ? steps[next] : lastSuccessful;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static final class Builder {
        private final WorkerClient workerClient;
        private final WorkerRuntimeDefinition definition;
        private final String workerId;
        private final String workerGroupId;
        private final Map<String, String> attributes;
        private final Map<String, com.xa.mass.client.worker.handler.WorkerEventHandler> eventHandlers;
        private int maxMessages = 1;
        private long pollTimeoutMs = 0L;
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration maxPollBackoff = Duration.ofSeconds(30);
        private WorkerRuntimeListener listener = WorkerRuntimeListener.NOOP;
        private ScheduledExecutorService executor;

        private Builder(WorkerClient workerClient, WorkerRuntimeDefinition definition) {
            this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
            WorkerRuntimeDefinition resolved = Objects.requireNonNull(definition, "definition is required");
            this.definition = resolved;
            this.workerId = resolved.workerId();
            this.workerGroupId = resolved.workerGroupId();
            this.attributes = new LinkedHashMap<>(resolved.attributes());
            this.eventHandlers = new LinkedHashMap<>(resolved.eventHandlers());
        }

        public Builder maxMessages(int maxMessages) {
            if (maxMessages <= 0) {
                throw new IllegalArgumentException("maxMessages must be positive");
            }
            this.maxMessages = maxMessages;
            return this;
        }

        public Builder pollTimeout(Duration pollTimeout) {
            Objects.requireNonNull(pollTimeout, "pollTimeout is required");
            if (pollTimeout.isNegative()) {
                throw new IllegalArgumentException("pollTimeout must be non-negative");
            }
            this.pollTimeoutMs = pollTimeout.toMillis();
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = requirePositive(pollInterval, "pollInterval");
            return this;
        }

        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
            return this;
        }

        public Builder maxPollBackoff(Duration maxPollBackoff) {
            this.maxPollBackoff = requirePositive(maxPollBackoff, "maxPollBackoff");
            return this;
        }

        public Builder listener(WorkerRuntimeListener listener) {
            this.listener = listener == null ? WorkerRuntimeListener.NOOP : listener;
            return this;
        }

        public Builder executor(ScheduledExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor is required");
            return this;
        }

        public PollingWorkerRuntime start() {
            return new PollingWorkerRuntime(this).start();
        }

        public PollingWorkerRuntime buildUnstarted() {
            return new PollingWorkerRuntime(this);
        }

        private static Duration requirePositive(Duration value, String fieldName) {
            Objects.requireNonNull(value, fieldName + " is required");
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(fieldName + " must be positive");
            }
            return value;
        }
    }

    private static final class SessionThreadFactory implements ThreadFactory {
        private final String workerId;
        private final AtomicInteger counter = new AtomicInteger();

        private SessionThreadFactory(String workerId) {
            this.workerId = workerId;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "xa-mass-polling-worker-" + workerId + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
