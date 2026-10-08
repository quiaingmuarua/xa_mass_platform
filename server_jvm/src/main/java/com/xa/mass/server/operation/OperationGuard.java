package com.xa.mass.server.operation;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public final class OperationGuard {

    private final ConcurrentHashMap<String, Thread> running =
            new ConcurrentHashMap<>();

    public <T> T execute(
            String namespace,
            String resourceId,
            Supplier<T> action
    ) {
        return execute(namespace, resourceId, action, false);
    }

    /** One synchronous Task mutation may compose bounded batches and lifecycle calls on its owning thread. */
    public <T> T taskMutation(String taskId, Supplier<T> action) {
        try {
            return execute("task-mutation", taskId, action, true);
        } catch (OperationAlreadyRunningException busy) {
            throw new com.xa.mass.server.error.ServerException(
                    com.xa.mass.server.error.ServerErrorCode.TASK_STATE_CONFLICT,
                    "taskMutation.execute", "Another Task mutation is in progress", busy);
        }
    }

    private <T> T execute(String namespace, String resourceId, Supplier<T> action, boolean nested) {
        requireNonBlank(namespace, "namespace");
        requireNonBlank(resourceId, "resourceId");
        Objects.requireNonNull(action, "action");
        String key = namespace + ":" + resourceId;
        Thread owner = Thread.currentThread();
        Thread previous = running.putIfAbsent(key, owner);
        if (nested && previous == owner) return action.get();
        if (previous != null) {
            throw new OperationAlreadyRunningException(
                    namespace,
                    resourceId
            );
        }
        try {
            return action.get();
        } finally {
            running.remove(key, owner);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
