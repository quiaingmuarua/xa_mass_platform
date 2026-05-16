package com.xa.mass.sdk;

/**
 * Pluggable runtime bootstrap provider for embedding support.
 *
 * <p>This seam exists so demo/mock/bootstrap logic can live outside the SDK
 * core. Implementations should operate only through {@link MassRuntimeControl},
 * not through starter or engine internals.
 */
@FunctionalInterface
public interface MassBootstrapDataProvider {

    void loadInto(MassRuntimeControl runtimeControl);
}
