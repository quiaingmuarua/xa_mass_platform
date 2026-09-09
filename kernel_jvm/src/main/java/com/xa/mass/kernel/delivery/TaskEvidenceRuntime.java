package com.xa.mass.kernel.delivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.List;

public interface TaskEvidenceRuntime {

    enum TaskEvidenceType {
        EXECUTION_SUCCESS,
        EXECUTION_FAILURE,
        OUTCOME_OBSERVATION
    }

    int appendTaskEvidence(
            TaskEvidenceType evidenceType,
            List<DeliveryReport> reports
    );

    List<DeliveryReport> consumeTaskEvidence(
            TaskEvidenceType evidenceType,
            int limit
    );
}
