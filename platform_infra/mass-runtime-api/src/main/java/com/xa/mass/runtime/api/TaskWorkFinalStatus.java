package com.xa.mass.runtime.api;

/**
 * Minimal runtime-owned final receipt status for recently converged work.
 *
 * <p>This is intentionally smaller than engine compatibility projection
 * semantics. It only exists so hot callback duplicate/late classification can
 * stay runtime-first after queue and lease ownership have already been
 * released.</p>
 */
public enum TaskWorkFinalStatus {
    SUCCESS,
    FAILED,
    EXPIRED
}
