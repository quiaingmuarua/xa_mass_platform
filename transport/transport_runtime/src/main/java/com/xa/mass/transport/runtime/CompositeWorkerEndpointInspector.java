package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime-owned diagnostics-only endpoint inspector aggregate.
 */
public final class CompositeWorkerEndpointInspector implements WorkerEndpointInspector {

    private final Set<WorkerEndpointInspector> inspectors = new LinkedHashSet<>();

    public synchronized void register(WorkerEndpointInspector inspector) {
        inspectors.add(Objects.requireNonNull(inspector, "inspector"));
    }

    @Override
    public synchronized List<WorkerEndpointSnapshot> listWorkerEndpoints() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (WorkerEndpointInspector inspector : inspectors) {
            snapshots.addAll(inspector.listWorkerEndpoints());
        }
        return List.copyOf(snapshots);
    }
}
