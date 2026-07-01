package com.xa.mass.task.runtime;

public interface TaskRuntimeRepairPort {

    ActiveLeaseRepairBatch pollExpiredActiveLeases(PollActiveLeaseRepairCommand command);

    ActiveTaskWorkSnapshot getActiveWorkForTask(ActiveTaskWorkQuery query);

    ActiveWorkSnapshot getActiveWorkForWorker(ActiveWorkQuery query);
}
