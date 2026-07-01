package com.xa.mass.engine;

import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;

import java.time.Instant;
import java.util.List;

/**
 * Runtime lease maintenance surface.
 */
public interface TaskLeaseMaintenancePort {

    List<ActiveLeaseRepairCandidate> getActiveLeaseCandidates(String taskId);

    List<ActiveLeaseRepairCandidate> pollExpiredLeaseCandidates(int limit, Instant now);

    boolean hasActiveWorkForWorker(String taskId, String workerId);

    boolean expireLeasedWork(String taskId, String messageId);
}
