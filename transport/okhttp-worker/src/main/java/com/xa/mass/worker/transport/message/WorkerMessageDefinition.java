package com.xa.mass.worker.transport.message;

import java.util.Objects;

public final class WorkerMessageDefinition<P, R> {

    private final Resolver<P> resolver;
    private final Handler<P, R> handler;

    private WorkerMessageDefinition(
            Resolver<P> resolver,
            Handler<P, R> handler
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <P, R> WorkerMessageDefinition<P, R> of(
            Resolver<P> resolver,
            Handler<P, R> handler
    ) {
        return new WorkerMessageDefinition<>(resolver, handler);
    }

    R invoke(String payload) {
        P message = Objects.requireNonNull(
                resolver.resolve(payload),
                "resolved message"
        );
        return Objects.requireNonNull(
                handler.handle(message),
                "handling result"
        );
    }

    @FunctionalInterface
    public interface Resolver<P> {

        P resolve(String payload);
    }

    @FunctionalInterface
    public interface Handler<P, R> {

        R handle(P message);
    }
}
