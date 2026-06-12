package com.xa.mass.transport.runtime.delivery;

/**
 * Delivery failure sink. Implementations may persist the failure or hand it to
 * engine-owned compensation, but transport executor code does not decide task
 * retry or lifecycle state.
 */
@FunctionalInterface
public interface TransportDeliveryFailureHandler {

    boolean handle(TransportDeliveryFailureEvent event);
}
