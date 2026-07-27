package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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

    WorkerCommandConsumePage consumeWorkerCommands(
            String endpointManagerId,
            @Nullable String cursor,
            int scanCount
    );

    enum WorkerCommandAppendStatus {
        APPENDED,
        REPLACED
    }

    record WorkerCommandConsumePage(
            Map<String, WorkerCommandEnvelope> workerCommandsByWorkerId,
            @Nullable String nextCursor
    ) {
        public WorkerCommandConsumePage {
            Objects.requireNonNull(
                    workerCommandsByWorkerId,
                    "workerCommandsByWorkerId"
            );
            workerCommandsByWorkerId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(workerCommandsByWorkerId)
            );
        }
    }
}
