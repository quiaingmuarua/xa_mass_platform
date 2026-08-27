package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;

public interface TaskResultRuntime {

    enum TaskResultClass {
        SUCCESS,
        FAILURE
    }

    int appendTaskResults(
            TaskResultClass resultClass,
            List<DeliveryReport> results
    );

    List<DeliveryReport> consumeTaskResults(
            TaskResultClass resultClass,
            int limit
    );
}
