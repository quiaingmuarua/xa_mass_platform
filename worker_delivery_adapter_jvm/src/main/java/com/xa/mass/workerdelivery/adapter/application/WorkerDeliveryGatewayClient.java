package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import java.util.List;

public interface WorkerDeliveryGatewayClient {

    WorkerCommandPage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    );

    void appendResults(
            String endpointManagerId,
            List<SeedResult> results
    );
}
