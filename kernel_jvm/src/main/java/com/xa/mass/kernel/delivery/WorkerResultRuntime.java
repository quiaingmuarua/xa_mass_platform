package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass;
import java.util.List;

public interface WorkerResultRuntime {

    int appendWorkerResults(List<DeliveryReport> results);

    List<DeliveryReport> consumeWorkerResults(
            DeliveryReportOutcomeClass outcomeClass,
            int limit
    );
}
