package com.xa.mass.command.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local service registry for command runtime dependencies.
 *
 * <p>This keeps app service wiring separate from {@code CommandContext}'s
 * compatibility context and class-loading responsibilities.
 */
public class CommandServices {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    public <T> void register(Class<T> type, T service) {
        if (type == null || service == null) {
            throw new IllegalArgumentException("type or service is null");
        }
        services.put(type, service);
    }

    public <T> T require(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("service not registered: " + type.getName());
        }
        return type.cast(service);
    }

    public <T> T get(Class<T> type) {
        Object service = services.get(type);
        return service == null ? null : type.cast(service);
    }
}
