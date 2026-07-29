package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface WorkerCommandRuntime {

    Map<String, WorkerCommandAppendStatus> appendWorkerCommands(
            String endpointManagerId,
            Map<String, WorkerCommandEnvelope> workerCommandsByWorkerId
    );

    @Nullable WorkerCommandEnvelope consumeWorkerCommand(
            String endpointManagerId,
            String workerId
    );

    Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    );

    enum WorkerCommandAppendStatus {
        APPENDED,
        REPLACED
    }
}
