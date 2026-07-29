package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface WorkerCommandRuntime {

    Map<String, WorkerCommandAppendStatus> appendWorkerCommands(
            String endpointManagerId,
            Map<String, WorkerCommand> workerCommandsByWorkerId
    );

    @Nullable WorkerCommand consumeWorkerCommand(
            String endpointManagerId,
            String workerId
    );

    Map<String, WorkerCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    );

    enum WorkerCommandAppendStatus {
        APPENDED,
        REPLACED
    }
}
