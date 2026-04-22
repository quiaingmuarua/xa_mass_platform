package com.xa.mass.transport.channel;

import java.util.Map;

/**
 * Transport-neutral channel for ingesting task execution results from workers.
 */
public interface TaskResultIngestChannel {

    boolean ingestTaskResult(
            String taskId,
            String msgId,
            boolean success,
            String detail,
            String errorCode,
            Map<String, Object> output
    );
}
