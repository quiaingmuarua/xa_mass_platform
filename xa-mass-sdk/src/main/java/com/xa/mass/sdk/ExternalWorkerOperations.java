package com.xa.mass.sdk;

/**
 * Transitional runtime facade for third-party workers that live outside the JVM.
 *
 * <p>Worker registration stays transport-neutral. Polling-specific session
 * operations remain explicit so realtime workers are not silently routed
 * through pull-session machinery.</p>
 */
public interface ExternalWorkerOperations extends WorkerRegistryOperations,
        WorkerClientOperations {
}
