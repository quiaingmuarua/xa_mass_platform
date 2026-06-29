package com.xa.mass.transport.starter;

/**
 * Stable pull-session evidence port for embedded SDK worker sessions.
 */
public interface PullSessionEvidencePort {

    boolean connect(String workerId, String workerGroupId, String sessionToken, String reason);

    boolean heartbeat(String workerId, String workerGroupId, String sessionToken, String reason);

    boolean disconnect(String workerId, String workerGroupId, String sessionToken, String reason);
}
