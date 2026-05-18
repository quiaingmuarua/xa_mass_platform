package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCapabilityReportSnapshot;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSubmitRequest;
import com.xa.mass.sdk.model.WorkerStateProjectionSnapshot;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;

import java.util.List;

/**
 * Owner-backed worker-control SDK surface.
 */
public interface WorkerControlOperations {

    WorkerCapabilityReportSnapshot reportWorkerCapability(WorkerCapabilityReportRequest request);

    WorkerStateReportSnapshot reportWorkerState(WorkerStateReportRequest request);

    WorkerStateProjectionSnapshot getWorkerStateProjection(String workerId);

    List<WorkerStateProjectionSnapshot> listWorkerStateProjections();

    WorkerCommandResultSnapshot requestWorkerCommand(WorkerCommandSubmitRequest request);

    WorkerCommandResultSnapshot acknowledgeWorkerCommand(WorkerCommandAcknowledgementRequest request);

    WorkerCommandSnapshot getWorkerCommand(String commandId);

    List<WorkerCommandSnapshot> listWorkerCommandsForWorker(String workerId);
}
