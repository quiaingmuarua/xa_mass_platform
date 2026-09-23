package com.xa.mass.server.operation;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public final class OperationGuard {

    private static final Object PRESENT = new Object();

    private final ConcurrentHashMap<String, Object> running =
            new ConcurrentHashMap<>();

    public <T> T execute(
            String namespace,
            String resourceId,
            Supplier<T> action
    ) {
        requireNonBlank(namespace, "namespace");
        requireNonBlank(resourceId, "resourceId");
        Objects.requireNonNull(action, "action");
        String key = namespace + ":" + resourceId;
        if (running.putIfAbsent(key, PRESENT) != null) {
            throw new OperationAlreadyRunningException(
                    namespace,
                    resourceId
            );
        }
        try {
            return action.get();
        } finally {
            running.remove(key, PRESENT);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
