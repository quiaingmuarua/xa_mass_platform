package com.xa.mass.transport.runtime.embedded;

/**
 * Embedded pull-session evidence projection seam.
 *
 * <p>SDK pull sessions own user-facing session actions, but transport runtime
 * owns endpoint lease and consumer evidence construction.</p>
 */
public interface PullSessionEvidenceDriver {

    boolean connect(String workerId, String deliveryBucketId, String sessionToken, String reason);

    boolean heartbeat(String workerId, String deliveryBucketId, String sessionToken, String reason);

    boolean disconnect(String workerId, String deliveryBucketId, String sessionToken, String reason);
}
