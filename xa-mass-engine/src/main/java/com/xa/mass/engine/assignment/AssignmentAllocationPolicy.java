package com.xa.mass.engine.assignment;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;

import java.util.List;

public interface AssignmentAllocationPolicy {

    AssignmentAllocationPlan plan(AssignmentAllocationRequest request);

    AssignmentAllocationDecision decide(AssignmentAllocationPlan plan,
                                        TaskStatus currentStatus,
                                        List<SelectedWorkerHandle> selectedWorkers);
}
