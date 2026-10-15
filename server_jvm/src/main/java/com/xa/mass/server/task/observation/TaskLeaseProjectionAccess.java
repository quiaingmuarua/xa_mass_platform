package com.xa.mass.server.task.observation;

import java.util.Map;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;

/** Bounded application facts and metrics; no Matching write or consumer lifecycle handle. */
public interface TaskLeaseProjectionAccess {
    void acceptResults(TaskCaller caller, String taskId, Map<String, TaskItemResultResponse> results);
    Map<String, Object> metrics();
}
