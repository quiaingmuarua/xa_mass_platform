package com.xa.mass.server.workerdelivery;

import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandPage;
import java.util.List;

public interface WorkerDeliveryRuntime {

    WorkerCommandEnvelope consumeWorkerCommand(
            String endpointManagerId,
            String workerId
    );

    WorkerCommandPage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    );

    int appendSeedResults(List<SeedResult> results);
}
