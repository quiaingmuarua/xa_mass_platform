package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe Gateway boundary shared by independent command and result loops.
 */
public interface WorkerDeliveryGatewayClient {

    Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    );

    void appendResults(
            String endpointManagerId,
            SeedResultSource source,
            List<String> encodedSeedResults
    );
}
