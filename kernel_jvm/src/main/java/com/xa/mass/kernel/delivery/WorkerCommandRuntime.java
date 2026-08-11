package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface WorkerCommandRuntime {

    Map<String, WorkerCommandAppendStatus> appendWorkerCommands(
            String endpointManagerId,
            Map<String, DeliveryCommand> workerCommandsByWorkerId
    );

    @Nullable DeliveryCommand consumeWorkerCommand(
            String endpointManagerId,
            String workerId
    );

    Map<String, DeliveryCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    );

    enum WorkerCommandAppendStatus {
        APPENDED,
        REPLACED
    }
}
