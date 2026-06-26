package com.xa.mass.transport.runtime.lease;

/**
 * Narrow sink for adapter-confirmed current-session availability.
 *
 * <p>This is not a transport presence event channel. It allows assembly to
 * translate accepted endpoint lease evidence into owner-validated worker
 * dispatch recovery without making concrete adapters depend on worker-runtime.</p>
 */
@FunctionalInterface
public interface CurrentSessionConnectSink {

    CurrentSessionConnectSink NOOP = (deliveryBucketId, workerId, reason, observedAtMillis) -> {
    };

    void currentSessionConnected(String deliveryBucketId,
                                 String workerId,
                                 String reason,
                                 long observedAtMillis);
}
