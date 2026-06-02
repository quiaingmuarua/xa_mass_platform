package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskResultArchiveSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;

import java.io.OutputStream;
import java.util.Optional;

public interface TaskResultQueryOperations {

    TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit);

    Optional<TaskWorkFinalSnapshot> getTaskWorkFinal(String taskId, String messageId);

    TaskResultArchiveSnapshot getTaskResultArchiveManifest(String taskId);

    void writeTaskResultArchiveContent(String taskId, OutputStream sink);
}
