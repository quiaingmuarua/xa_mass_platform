package com.xa.mass.transport.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-starter assembly input for embedded transport runtime primitives.
 */
public record EmbeddedTransportAssemblyConfig(
        EmbeddedTransportBackendDeclaration backend,
        List<EmbeddedAdapterDeclaration> adapterDeclarations,
        RuntimeTaskExecutor executor,
        CurrentSessionDisconnectHandler currentSessionDisconnectHandler
) {

    public EmbeddedTransportAssemblyConfig {
        backend = Objects.requireNonNull(backend, "backend");
        adapterDeclarations = List.copyOf(Objects.requireNonNull(adapterDeclarations, "adapterDeclarations"));
        executor = Objects.requireNonNull(executor, "executor");
        currentSessionDisconnectHandler = currentSessionDisconnectHandler == null
                ? CurrentSessionDisconnectHandler.NOOP
                : currentSessionDisconnectHandler;
    }
}
