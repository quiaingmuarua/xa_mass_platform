package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.List;
import java.util.Map;

public interface WorkerDeliveryGatewayClient {

    Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    );

    void appendResults(
            String endpointManagerId,
            List<SeedResult> results
    );
}
