package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.AdapterNodeSpec;
import com.xa.mass.client.worker.NodeGroupBindingSpec;
import com.xa.mass.client.worker.WorkerCapabilityReport;
import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerDispatchItem;
import com.xa.mass.client.worker.WorkerPollRequest;
import com.xa.mass.client.worker.WorkerPollResult;
import com.xa.mass.client.worker.WorkerRegistrationResult;
import com.xa.mass.client.worker.WorkerResultSubmitRequest;
import com.xa.mass.client.worker.WorkerSpec;
import com.xa.mass.client.worker.WorkerStateReport;
import com.xa.mass.client.worker.handler.DispatchContext;
import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerEventHandlerRuntime;
import com.xa.mass.client.worker.handler.WorkerEventHandlers;
import com.xa.mass.client.worker.handler.WorkerEventInvocation;
import com.xa.mass.client.worker.handler.WorkerResult;
import com.xa.mass.client.worker.handler.WorkerResultSink;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class PollingWorkerSession implements AutoCloseable {
    private final WorkerClient workerClient;
    private final String workerId;
    private final String workerGroupId;
    private final String adapterNodeId;
    private final String adapterType;
    private final String adapterVersion;
    private final String endpointId;
    private final String pluginVersion;
    private final String deploymentVersion;
    private final String sessionToken;
    private final Map<String, String> attributes;
    private final int maxMessages;
    private final long pollTimeoutMs;
    private final Duration pollInterval;
    private final Duration heartbeatInterval;
    private final Duration maxPollBackoff;
    private final WorkerSessionListener listener;
    private final WorkerEventHandlers eventHandlers;
    private final WorkerEventHandlerRuntime handlerRuntime;
    private final WorkerResultSink resultSink;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consecutiveHeartbeatFailures = new AtomicInteger();
    private volatile boolean offlineOnClose;

    private PollingWorkerSession(Builder builder) {
        this.workerClient = builder.workerClient;
        this.workerId = requireText(builder.workerId, "workerId");
        this.workerGroupId = requireText(builder.workerGroupId, "workerGroupId");
        this.adapterNodeId = optionalText(builder.adapterNodeId);
        this.adapterType = firstNonBlank(builder.adapterType, "polling");
        this.adapterVersion = builder.adapterVersion;
        this.endpointId = firstNonBlank(builder.endpointId, adapterNodeId != null ? adapterNodeId : workerId);
        this.pluginVersion = builder.pluginVersion;
        this.deploymentVersion = builder.deploymentVersion;
        this.sessionToken = UUID.randomUUID().toString();
        this.attributes = Map.copyOf(builder.attributes);
        this.maxMessages = builder.maxMessages;
        this.pollTimeoutMs = builder.pollTimeoutMs;
        this.pollInterval = builder.pollInterval;
        this.heartbeatInterval = builder.heartbeatInterval;
        this.maxPollBackoff = builder.maxPollBackoff;
        this.listener = builder.listener;
        this.eventHandlers = builder.eventHandlers.build();
        this.handlerRuntime = new WorkerEventHandlerRuntime(eventHandlers);
        this.resultSink = builder.resultSink == null ? this::submitResultToWorkerApi : builder.resultSink;
        this.executor = builder.executor == null
                ? Executors.newScheduledThreadPool(2, new SessionThreadFactory(workerId))
                : builder.executor;
    }

    public static Builder builder(WorkerClient workerClient) {
        return new Builder(workerClient);
    }

    public PollingWorkerSession start() {
        WorkerSessionStartupStep lastSuccessful = null;
        try {
            if (adapterNodeId != null) {
                workerClient.registerAdapterNode(AdapterNodeSpec.builder()
                        .adapterNodeId(adapterNodeId)
                        .adapterType(adapterType)
                        .adapterVersion(adapterVersion)
                        .endpointId(endpointId)
                        .attributes(attributes)
                        .build());
                lastSuccessful = WorkerSessionStartupStep.REGISTER_ADAPTER_NODE;

                workerClient.bindNodeGroup(NodeGroupBindingSpec.builder()
                        .adapterNodeId(adapterNodeId)
                        .workerGroupId(workerGroupId)
                        .pluginVersion(pluginVersion)
                        .deploymentVersion(deploymentVersion)
                        .attributes(attributes)
                        .build());
                lastSuccessful = WorkerSessionStartupStep.BIND_NODE_GROUP;
            }

            WorkerRegistrationResult registration = workerClient.registerWorker(WorkerSpec.builder()
                    .workerId(workerId)
                    .workerGroupId(workerGroupId)
                    .polling()
                    .attributes(attributes)
                    .build());
            lastSuccessful = WorkerSessionStartupStep.REGISTER_WORKER;

            workerClient.online(registration.workerId(), sessionToken, "polling-session-start");
            offlineOnClose = true;
            lastSuccessful = WorkerSessionStartupStep.ONLINE;

            workerClient.reportCapability(workerId, WorkerCapabilityReport.builder()
                    .workerId(workerId)
                    .availableEventCodes(eventHandlers.eventCodes().stream().toList())
                    .schedulingAttributes(attributes)
                    .build());
            lastSuccessful = WorkerSessionStartupStep.REPORT_CAPABILITY;

            workerClient.reportState(workerId, WorkerStateReport.builder()
                    .workerId(workerId)
                    .available()
                    .reason("polling-session-start")
                    .attributes(attributes)
                    .build());
            lastSuccessful = WorkerSessionStartupStep.REPORT_STATE;

            running.set(true);
            executor.scheduleWithFixedDelay(this::heartbeatOnce,
                    heartbeatInterval.toMillis(),
                    heartbeatInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
            lastSuccessful = WorkerSessionStartupStep.START_HEARTBEAT;

            executor.execute(this::pollLoop);
            lastSuccessful = WorkerSessionStartupStep.START_POLL;
            return this;
        } catch (Throwable failure) {
            running.set(false);
            executor.shutdownNow();
            WorkerSessionStartupStep failedStep = nextStepAfter(lastSuccessful, adapterNodeId != null);
            WorkerSessionStartupFailure startupFailure =
                    new WorkerSessionStartupFailure(workerId, failedStep, lastSuccessful, failure);
            listener.onStartupFailure(startupFailure);
            throw new WorkerSessionStartupException(startupFailure);
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public String sessionToken() {
        return sessionToken;
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
                workerClient.offline(workerId, sessionToken, "polling-session-close");
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
            workerClient.heartbeat(workerId, sessionToken, "polling-session-heartbeat");
            consecutiveHeartbeatFailures.set(0);
        } catch (Throwable failure) {
            int failures = consecutiveHeartbeatFailures.incrementAndGet();
            listener.onHeartbeatFailure(new WorkerSessionHeartbeatFailure(workerId, failures, failure));
        }
    }

    private void pollLoop() {
        int consecutiveFailures = 0;
        while (running.get()) {
            try {
                WorkerPollResult result = workerClient.poll(workerId, WorkerPollRequest.builder()
                        .maxMessages(maxMessages)
                        .timeoutMs(pollTimeoutMs)
                        .build());
                consecutiveFailures = 0;
                for (WorkerDispatchItem item : nullSafeItems(result.items())) {
                    handleItem(item);
                }
                sleep(pollInterval);
            } catch (Throwable failure) {
                consecutiveFailures++;
                listener.onPollFailure(new WorkerSessionPollFailure(workerId, consecutiveFailures, failure));
                sleep(backoff(consecutiveFailures));
            }
        }
    }

    private void handleItem(WorkerDispatchItem item) {
        DispatchContext dispatch = DispatchContext.from(item);
        WorkerEventInvocation invocation = handlerRuntime.invoke(dispatch);
        if (invocation.handlerFailed()) {
            listener.onHandlerFailure(new WorkerSessionDispatchFailure(dispatch, invocation.failure()));
        }
        submitResult(dispatch, invocation.result());
    }

    private void submitResult(DispatchContext dispatch, WorkerResult result) {
        try {
            resultSink.submit(dispatch, result);
        } catch (Throwable failure) {
            listener.onSubmitFailure(new WorkerSessionDispatchFailure(dispatch, failure));
        }
    }

    private void submitResultToWorkerApi(DispatchContext dispatch, WorkerResult result) {
        workerClient.submitResult(workerId, new WorkerResultSubmitRequest(
                dispatch.taskId(),
                dispatch.messageId(),
                result.success(),
                result.detail(),
                result.errorCode(),
                result.output()
        ));
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

    private static List<WorkerDispatchItem> nullSafeItems(List<WorkerDispatchItem> items) {
        return items == null ? List.of() : items;
    }

    private static WorkerSessionStartupStep nextStepAfter(WorkerSessionStartupStep lastSuccessful,
                                                          boolean topologyBootstrapEnabled) {
        if (lastSuccessful == null) {
            return topologyBootstrapEnabled
                    ? WorkerSessionStartupStep.REGISTER_ADAPTER_NODE
                    : WorkerSessionStartupStep.REGISTER_WORKER;
        }
        WorkerSessionStartupStep[] steps = WorkerSessionStartupStep.values();
        int next = lastSuccessful.ordinal() + 1;
        return next < steps.length ? steps[next] : lastSuccessful;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary.trim();
    }

    public static final class Builder {
        private final WorkerClient workerClient;
        private String workerId;
        private String workerGroupId;
        private String adapterNodeId;
        private String adapterType = "polling";
        private String adapterVersion;
        private String endpointId;
        private String pluginVersion;
        private String deploymentVersion;
        private Map<String, String> attributes = new LinkedHashMap<>();
        private WorkerEventHandlers.Builder eventHandlers = WorkerEventHandlers.builder();
        private int maxMessages = 1;
        private long pollTimeoutMs = 0L;
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration heartbeatInterval = Duration.ofSeconds(10);
        private Duration maxPollBackoff = Duration.ofSeconds(30);
        private WorkerSessionListener listener = WorkerSessionListener.NOOP;
        private WorkerResultSink resultSink;
        private ScheduledExecutorService executor;

        private Builder(WorkerClient workerClient) {
            this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        }

        public Builder workerId(String workerId) {
            this.workerId = workerId;
            return this;
        }

        public Builder workerGroupId(String workerGroupId) {
            this.workerGroupId = workerGroupId;
            return this;
        }

        public Builder adapterNodeId(String adapterNodeId) {
            this.adapterNodeId = adapterNodeId;
            return this;
        }

        public Builder adapterType(String adapterType) {
            this.adapterType = adapterType;
            return this;
        }

        public Builder adapterVersion(String adapterVersion) {
            this.adapterVersion = adapterVersion;
            return this;
        }

        public Builder endpointId(String endpointId) {
            this.endpointId = endpointId;
            return this;
        }

        public Builder pluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
            return this;
        }

        public Builder deploymentVersion(String deploymentVersion) {
            this.deploymentVersion = deploymentVersion;
            return this;
        }

        public Builder attributes(Map<String, String> attributes) {
            this.attributes = attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
            return this;
        }

        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder event(String eventCode, WorkerDispatchHandler handler) {
            Objects.requireNonNull(handler, "handler is required");
            return eventHandler(eventCode, handler::handle);
        }

        public Builder eventHandler(String eventCode, WorkerEventHandler handler) {
            this.eventHandlers.event(requireText(eventCode, "eventCode"),
                    Objects.requireNonNull(handler, "handler is required"));
            return this;
        }

        public Builder eventHandlers(WorkerEventHandlers eventHandlers) {
            this.eventHandlers.events(eventHandlers);
            return this;
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

        public Builder listener(WorkerSessionListener listener) {
            this.listener = listener == null ? WorkerSessionListener.NOOP : listener;
            return this;
        }

        public Builder resultSink(WorkerResultSink resultSink) {
            this.resultSink = Objects.requireNonNull(resultSink, "resultSink is required");
            return this;
        }

        public Builder executor(ScheduledExecutorService executor) {
            this.executor = Objects.requireNonNull(executor, "executor is required");
            return this;
        }

        public PollingWorkerSession start() {
            return new PollingWorkerSession(this).start();
        }

        public PollingWorkerSession buildUnstarted() {
            return new PollingWorkerSession(this);
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
