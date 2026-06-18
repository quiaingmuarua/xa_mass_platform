package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerClient;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.handler.WorkerEventHandler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

final class WorkerRuntimeContext {
    private static final int DEFAULT_RUNTIME_THREAD_POOL_SIZE = 2;

    private final WorkerClient workerClient;
    private final String workerId;
    private final String workerGroupId;
    private final Map<String, String> attributes;
    private final Map<String, WorkerEventHandler> eventHandlers;
    private final WorkerRuntimeListener listener;
    private final ScheduledExecutorService executor;
    private final WorkerDispatchProcessor dispatchProcessor;
    private final WorkerRuntimeReporter reporter;

    WorkerRuntimeContext(WorkerClient workerClient,
                         WorkerRuntimeDefinition definition,
                         WorkerRuntimeOptions options,
                         String threadNamePrefix) {
        this.workerClient = Objects.requireNonNull(workerClient, "workerClient is required");
        WorkerRuntimeDefinition resolvedDefinition = Objects.requireNonNull(definition, "definition is required");
        WorkerRuntimeOptions resolvedOptions = options == null
                ? new WorkerRuntimeOptions(null, null)
                : options;
        this.workerId = requireText(resolvedDefinition.workerId(), "workerId");
        this.workerGroupId = requireText(resolvedDefinition.workerGroupId(), "workerGroupId");
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedDefinition.attributes()));
        this.eventHandlers = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedDefinition.eventHandlers()));
        this.listener = resolvedOptions.listener();
        this.executor = resolvedOptions.executor() == null
                ? Executors.newScheduledThreadPool(
                        DEFAULT_RUNTIME_THREAD_POOL_SIZE,
                        new RuntimeThreadFactory(requireText(threadNamePrefix, "threadNamePrefix"), workerId))
                : resolvedOptions.executor();
        this.dispatchProcessor = new WorkerDispatchProcessor(workerId, eventHandlers, listener);
        this.reporter = new WorkerRuntimeReporter(workerClient, resolvedDefinition);
    }

    WorkerClient workerClient() {
        return workerClient;
    }

    String workerId() {
        return workerId;
    }

    String workerGroupId() {
        return workerGroupId;
    }

    Map<String, String> attributes() {
        return attributes;
    }

    Map<String, WorkerEventHandler> eventHandlers() {
        return eventHandlers;
    }

    WorkerRuntimeListener listener() {
        return listener;
    }

    ScheduledExecutorService executor() {
        return executor;
    }

    WorkerDispatchProcessor dispatchProcessor() {
        return dispatchProcessor;
    }

    WorkerRuntimeReporter reporter() {
        return reporter;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private static final class RuntimeThreadFactory implements ThreadFactory {
        private final String threadNamePrefix;
        private final String workerId;
        private final AtomicInteger counter = new AtomicInteger();

        private RuntimeThreadFactory(String threadNamePrefix, String workerId) {
            this.threadNamePrefix = threadNamePrefix;
            this.workerId = workerId;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    threadNamePrefix + workerId + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
