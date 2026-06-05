package com.xa.mass.api.worker.registration;

import java.util.List;

public interface WorkerRegistrationObservationStore {

    WorkerRegistrationObservationRecord append(WorkerRegistrationObservationRecord record);

    List<WorkerRegistrationObservationRecord> listByResource(String resourceType, String resourceId);
}
