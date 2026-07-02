package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskAccessSnapshot;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskResultArchiveSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.TaskStateResolutionSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskStateValidationSnapshot;
import com.xa.mass.sdk.model.TaskSummarySnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;

import java.io.OutputStream;
import java.util.List;
import java.util.Optional;

/**
 * Unified external read surface for task shell, result, and diagnostic snapshots.
 */
public interface TaskReadOperations {

    TaskDetailSnapshot getTaskDetail(String taskId);

    List<TaskSummarySnapshot> listTaskSummaries(int offset, int limit);

    List<TaskSummarySnapshot> getTaskSummariesByStatus(String status);

    boolean taskExists(String taskId);

    TaskStateSnapshot getTaskState(String taskId);

    TaskAccessSnapshot getTaskAccess(String taskId);

    TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit);

    Optional<TaskWorkFinalSnapshot> getTaskWorkFinal(String taskId, String messageId);

    TaskResultArchiveSnapshot getTaskResultArchiveManifest(String taskId);

    void writeTaskResultArchiveContent(String taskId, OutputStream sink);

    TaskStateValidationSnapshot validateTaskState(String taskId);

    TaskStateResolutionSnapshot resolveTaskState(String taskId);

    TaskWorkStatsSnapshot getTaskWorkStats(String taskId);

    List<TaskActiveLeaseSnapshot> getActiveLeases(String taskId);
}
