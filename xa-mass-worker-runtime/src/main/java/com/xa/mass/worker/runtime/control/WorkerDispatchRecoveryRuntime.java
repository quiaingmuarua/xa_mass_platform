package com.xa.mass.worker.runtime.control;

import com.xa.mass.runtime.worker.DispatchAvailabilitySource;

/**
 * Worker-runtime owner for validated dispatch recovery.
 *
 * <p>Callers request a re-open for one source. The implementation validates
 * worker slot, group, block-source, and recovery policy before clearing the
 * dispatch gate source.</p>
 */
public interface WorkerDispatchRecoveryRuntime {

    boolean recoverWorkerDispatch(String workerId, DispatchAvailabilitySource source, String reason);
}
