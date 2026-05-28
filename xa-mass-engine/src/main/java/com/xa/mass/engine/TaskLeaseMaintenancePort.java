package com.xa.mass.engine;

import com.xa.mass.runtime.api.ActiveLeaseRecord;

import java.time.Instant;
import java.util.List;

/**
 * Runtime lease maintenance surface.
 */
public interface TaskLeaseMaintenancePort {

    List<ActiveLeaseRecord> getActiveLeases(String taskId);

    List<ActiveLeaseRecord> pollExpiredLeases(int limit, Instant now);

    boolean hasActiveWorkForWorker(String taskId, String workerId);

    boolean expireLeasedWork(String taskId, String messageId);
}
