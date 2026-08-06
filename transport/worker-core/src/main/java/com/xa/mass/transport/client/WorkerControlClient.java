package com.xa.mass.transport.client;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Worker-facing control boundary for long-lived identity registration and
 * endpoint binding.
 */
public interface WorkerControlClient extends AutoCloseable {

    String register(
            String workerGroupId,
            Map<String, Object> workerProperties,
            Duration timeout
    ) throws IOException;

    URI bind(
            String workerGroupId,
            String workerId,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties,
            Duration timeout
    ) throws IOException;

    @Override
    void close();
}
