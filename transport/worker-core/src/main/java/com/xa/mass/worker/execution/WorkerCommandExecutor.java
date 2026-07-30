package com.xa.mass.worker.execution;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import java.util.Optional;

@FunctionalInterface
public interface WorkerCommandExecutor {

    Optional<WorkerResult> execute(String encodedCommand);
}
