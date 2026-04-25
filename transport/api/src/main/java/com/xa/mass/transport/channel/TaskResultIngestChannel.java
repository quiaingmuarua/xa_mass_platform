package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TaskResultReport;

/**
 * Transport-neutral channel for ingesting task execution results from workers.
 */
public interface TaskResultIngestChannel {

    boolean ingest(TaskResultReport report);
}
