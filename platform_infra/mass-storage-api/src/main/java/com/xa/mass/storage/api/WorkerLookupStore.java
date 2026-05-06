package com.xa.mass.storage.api;

import com.xa.mass.base.model.Worker;

/**
 * Read-only lookup seam for registered worker truth.
 *
 * <p>Use this when a caller only needs to resolve worker registration/runtime
 * identity and should not carry the broader worker mutation or lock surface.
 */
public interface WorkerLookupStore {

    Worker findWorker(String workerId);
}
