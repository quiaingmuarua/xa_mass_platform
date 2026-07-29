package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass;
import java.util.List;

public interface WorkerResultRuntime {

    int appendWorkerResults(List<WorkerResult> results);

    List<WorkerResult> consumeWorkerResults(
            WorkerResultOutcomeClass outcomeClass,
            int limit
    );
}
