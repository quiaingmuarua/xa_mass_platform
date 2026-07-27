package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;

public interface WorkerConnectionRegistry {

    void bind(
            String workerId,
            WorkerConnection connection
    );

    void unbind(
            String workerId,
            WorkerConnection connection
    );

    CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    );

    void closeAll(WorkerConnectionCloseReason reason);
}
