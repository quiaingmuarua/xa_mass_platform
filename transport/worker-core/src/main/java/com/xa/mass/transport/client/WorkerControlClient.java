package com.xa.mass.transport.client;

import com.xa.mass.worker.runtime.PreparedWorker;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Worker-facing control boundary for one explicit run preparation.
 */
public interface WorkerControlClient extends AutoCloseable {

    PreparedWorker prepare(
            String workerGroupId,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties,
            Duration timeout
    ) throws IOException;

    @Override
    void close();
}
