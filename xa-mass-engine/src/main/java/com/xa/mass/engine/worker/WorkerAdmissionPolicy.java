package com.xa.mass.engine.worker;

/**
 * Interprets pre-read slot evidence before WorkerRegistry performs atomic reserve.
 */
public interface WorkerAdmissionPolicy {

    ReserveStatus evaluate(WorkerSlot slot, WorkerAdmissionContext context);
}
