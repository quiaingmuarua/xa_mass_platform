package com.xa.mass.transport.starter;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Stable sink for already-translated assigned-delivery batches.
 */
public interface AssignedDeliverySink {

    List<DispatchOutcome> submit(List<AssignedDeliveryBatch> batches);
}
