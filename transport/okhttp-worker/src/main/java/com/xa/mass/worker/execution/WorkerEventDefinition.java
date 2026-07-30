package com.xa.mass.worker.execution;

import java.util.Map;
import java.util.Objects;

public final class WorkerEventDefinition<P> {

    private final String src;
    private final String eventCode;
    private final WorkerEventParameterResolver<P> resolver;
    private final WorkerEventHandler<P> handler;

    private WorkerEventDefinition(
            String src,
            String eventCode,
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        this.src = requireNonBlank(src, "src");
        this.eventCode = requireNonBlank(eventCode, "eventCode");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <P> WorkerEventDefinition<P> of(
            String src,
            String eventCode,
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        return new WorkerEventDefinition<>(
                src,
                eventCode,
                resolver,
                handler
        );
    }

    public static WorkerEventDefinition<Map<String, Object>> map(
            String src,
            String eventCode,
            WorkerEventHandler<Map<String, Object>> handler
    ) {
        return of(
                src,
                eventCode,
                parameters -> parameters,
                handler
        );
    }

    String src() {
        return src;
    }

    String eventCode() {
        return eventCode;
    }

    String invoke(Map<String, Object> parameters) throws Exception {
        return handler.execute(resolver.resolve(parameters));
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
        return value;
    }
}
