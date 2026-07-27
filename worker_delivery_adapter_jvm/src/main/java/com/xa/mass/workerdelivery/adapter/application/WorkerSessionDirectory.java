package com.xa.mass.workerdelivery.adapter.application;

import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;

public interface WorkerSessionDirectory {

    WorkerSessionToken bind(
            String workerId,
            WorkerConnection connection
    );

    void unbind(WorkerSessionToken token);

    boolean isCurrent(WorkerSessionToken token);

    CommandDeliveryAttempt deliver(
            String workerId,
            WorkerCommandEnvelope command
    );

    void close(
            WorkerSessionToken token,
            WorkerConnectionCloseReason reason
    );

    void closeAll(WorkerConnectionCloseReason reason);

    interface WorkerSessionToken {
        String workerId();

        long generation();
    }
}
