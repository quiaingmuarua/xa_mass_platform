package com.xa.mass.workerdelivery.adapter.message;

import java.util.Objects;

public final class AdapterMessageDefinition<P, R> {

    private final Resolver<P> resolver;
    private final Handler<P, R> handler;

    private AdapterMessageDefinition(
            Resolver<P> resolver,
            Handler<P, R> handler
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    public static <P, R> AdapterMessageDefinition<P, R> of(
            Resolver<P> resolver,
            Handler<P, R> handler
    ) {
        return new AdapterMessageDefinition<>(resolver, handler);
    }

    R invoke(
            String workerId,
            String payload
    ) {
        P message = Objects.requireNonNull(
                resolver.resolve(payload),
                "resolved message"
        );
        return Objects.requireNonNull(
                handler.handle(workerId, message),
                "handling result"
        );
    }

    @FunctionalInterface
    public interface Resolver<P> {

        P resolve(String payload);
    }

    @FunctionalInterface
    public interface Handler<P, R> {

        R handle(String workerId, P message);
    }
}
