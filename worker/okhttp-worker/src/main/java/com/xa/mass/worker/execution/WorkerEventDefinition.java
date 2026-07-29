package com.xa.mass.worker.execution;

import java.util.Map;
import java.util.Objects;

public final class WorkerEventDefinition<P, R> {

    private final WorkerEventParameterResolver<P> resolver;
    private final WorkerEventHandler<P, R> handler;

    private WorkerEventDefinition(
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P, R> handler
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <P, R> WorkerEventDefinition<P, R> of(
            WorkerEventParameterResolver<P> resolver,
            WorkerEventHandler<P, R> handler
    ) {
        return new WorkerEventDefinition<>(resolver, handler);
    }

    public static <R>
    WorkerEventDefinition<Map<String, Object>, R> map(
            WorkerEventHandler<Map<String, Object>, R> handler
    ) {
        return of(parameters -> parameters, handler);
    }

    R invoke(Map<String, Object> parameters) throws Exception {
        return handler.execute(resolver.resolve(parameters));
    }
}
