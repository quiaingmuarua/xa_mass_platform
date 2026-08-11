package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Thread-safe Gateway boundary shared by independent command and result loops.
 */
public interface WorkerDeliveryGatewayClient {

    Map<String, DeliveryCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    );

    void appendResults(
            String endpointManagerId,
            List<String> encodedWorkerResults
    );

    CompletionStage<Void> verifyWorkerRoute(
            String endpointManagerId,
            String workerId
    );
}
