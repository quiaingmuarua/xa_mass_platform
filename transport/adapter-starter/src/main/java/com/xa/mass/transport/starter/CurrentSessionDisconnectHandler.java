package com.xa.mass.transport.starter;

/**
 * Callback invoked when transport confirms a current worker session disconnected.
 */
@FunctionalInterface
public interface CurrentSessionDisconnectHandler {

    CurrentSessionDisconnectHandler NOOP = (deliveryBucketId, workerId, reason, observedAtMillis) -> {
    };

    void currentSessionDisconnected(String deliveryBucketId,
                                    String workerId,
                                    String reason,
                                    long observedAtMillis);
}
