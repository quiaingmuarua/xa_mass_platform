package com.xa.mass.sdk;

import com.xa.mass.starter.MassApplication;

import java.util.Map;
import java.util.Objects;

/**
 * Default SDK-owned runtime diagnostics backed by the embedded runtime.
 *
 * <p>Adapter endpoint snapshots are intentionally not exposed here. Reachability
 * consumers should use worker runtime evidence, not session diagnostics rows.
 */
public class DefaultRuntimeDiagnosticsOperations implements RuntimeDiagnosticsOperations {

    private final MassApplication delegate;

    public DefaultRuntimeDiagnosticsOperations(MassApplication delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Map<String, Object> getQueueDetail() {
        return delegate.getTransportQueueDetail();
    }

    @Override
    public Map<String, Object> getQueueMetrics() {
        return Map.of();
    }

    @Override
    public boolean isWorkerLocked(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        ensureEngineStarted();
        return delegate.getEngine().getConfig().getWorkerAdmissionRuntime().hasWorkerExclusiveLease(workerId.trim());
    }

    private void ensureEngineStarted() {
        if (delegate.getEngine() == null || !delegate.getEngine().isRunning() || delegate.getEngine().getConfig() == null) {
            throw new IllegalStateException("MassEngine is not started");
        }
    }

    protected final MassApplication runtimeApplication() {
        return delegate;
    }

}
