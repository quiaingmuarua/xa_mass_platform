package com.xa.mass.sdk;

/**
 * Pluggable runtime bootstrap provider for compatibility and embedding support.
 *
 * <p>This seam exists so demo/mock/bootstrap logic can live outside the SDK
 * core while older convenience APIs remain callable. Implementations should
 * operate only through {@link MassRuntimeControl}, not through starter or
 * engine internals. Legacy WorkerContext fixture loading must require
 * {@link WorkerContextCompatibilityOperations} explicitly instead of treating
 * context registration as part of the runtime-control mainline.
 */
@FunctionalInterface
public interface MassBootstrapDataProvider {

    void loadInto(MassRuntimeControl runtimeControl);
}
