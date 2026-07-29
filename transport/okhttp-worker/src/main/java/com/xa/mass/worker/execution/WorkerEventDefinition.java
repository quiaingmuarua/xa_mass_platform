package com.xa.mass.worker.execution;

import java.util.Map;
import java.util.Objects;

public final class WorkerEventDefinition<P> {

    private final WorkerEventParameterResolver<P> resolver;
    private final WorkerEventHandler<P> handler;

    private WorkerEventDefinition(
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <P> WorkerEventDefinition<P> of(
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P> handler
    ) {
        return new WorkerEventDefinition<>(resolver, handler);
    }

    public static WorkerEventDefinition<Map<String, Object>> map(
            WorkerEventHandler<Map<String, Object>> handler
    ) {
        return of(parameters -> parameters, handler);
    }

    String invoke(Map<String, Object> parameters) throws Exception {
        return handler.execute(resolver.resolve(parameters));
    }
}
