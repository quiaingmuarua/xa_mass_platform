package com.xa.mass.transport.runtime.worker;

import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;

import java.util.Optional;

/**
 * Resolves the transport delivery address for an already matched worker.
 */
@FunctionalInterface
public interface WorkerRouteKeyResolver {

    Optional<String> resolveRouteKey(WorkerResourceRecord worker);
}

