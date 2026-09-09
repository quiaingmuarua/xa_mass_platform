package com.xa.mass.worker.execution;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.Optional;

@FunctionalInterface
public interface WorkerCommandExecutor {

    Optional<WorkerCommandOutcome> execute(DeliveryCommand command);
    default Optional<WorkerCommandOutcome> execute(
            DeliveryCommand command, WorkerOutcomeReporter reporter
    ) {
        return execute(command);
    }
}
