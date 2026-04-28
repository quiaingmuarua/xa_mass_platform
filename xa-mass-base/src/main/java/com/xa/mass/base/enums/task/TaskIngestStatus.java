package com.xa.mass.base.enums.task;

/**
 * Declares whether a task source still needs ingest work before the task can
 * produce its full runnable workload.
 */
public enum TaskIngestStatus {
    PENDING,
    INGESTING,
    READY,
    FAILED,
    SEALED
}
