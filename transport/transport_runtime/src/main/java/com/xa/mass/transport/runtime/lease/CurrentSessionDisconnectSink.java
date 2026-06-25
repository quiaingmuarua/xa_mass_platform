package com.xa.mass.transport.runtime.lease;

/**
 * Narrow sink for adapter-confirmed current-session loss.
 *
 * <p>This is not a transport presence event channel. Connected, heartbeat,
 * and refresh evidence stay transport-local. Only a disconnect that the
 * adapter/session evidence owner accepts as current may cross this boundary
 * as negative dispatch evidence.</p>
 */
@FunctionalInterface
public interface CurrentSessionDisconnectSink {

    CurrentSessionDisconnectSink NOOP = (deliveryBucketId, workerId, reason, observedAtMillis) -> {
    };

    void currentSessionDisconnected(String deliveryBucketId,
                                    String workerId,
                                    String reason,
                                    long observedAtMillis);
}
