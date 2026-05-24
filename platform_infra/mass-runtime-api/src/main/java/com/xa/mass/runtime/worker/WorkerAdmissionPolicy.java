package com.xa.mass.runtime.worker;

/**
 * Interprets pre-read slot evidence before WorkerRegistry performs atomic reserve.
 */
public interface WorkerAdmissionPolicy {

    ReserveStatus evaluate(WorkerSlot slot, WorkerAdmissionContext context);
}
