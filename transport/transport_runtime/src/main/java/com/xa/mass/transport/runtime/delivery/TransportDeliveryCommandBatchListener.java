package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.List;

/**
 * Core handoff pump callback for materialized delivery-command batches.
 */
@FunctionalInterface
public interface TransportDeliveryCommandBatchListener {

    List<DispatchOutcome> onDeliveryCommandBatch(DeliveryCommandBatch batch);
}
