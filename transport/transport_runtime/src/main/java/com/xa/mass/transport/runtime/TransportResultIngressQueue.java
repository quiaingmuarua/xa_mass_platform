package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressEntry;

/**
 * Direct keyed queue for worker result ingress messages.
 */
public interface TransportResultIngressQueue {

    String DEFAULT_RESULT_QUEUE_KEY = "default";

    boolean offer(String resultQueueKey, ResultIngressEntry entry);

    ResultIngressEntry poll(String resultQueueKey, long timeoutMillis) throws InterruptedException;
}
