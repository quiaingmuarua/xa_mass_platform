package com.xa.mass.sdk;

import com.xa.mass.sdk.model.TaskResultArchiveSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;

import java.io.OutputStream;

public interface TaskResultQueryOperations {

    TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit);

    TaskResultArchiveSnapshot getTaskResultArchiveManifest(String taskId);

    void writeTaskResultArchiveContent(String taskId, OutputStream sink);
}
