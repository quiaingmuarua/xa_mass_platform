package com.xa.mass.server.task;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;

/** The generated identity survives an unknown Owner commit; callers must not recreate it. */
public final class TaskCreationUnconfirmedException extends ServerException {
    private final String taskId;

    public TaskCreationUnconfirmedException(String taskId, Throwable cause) {
        super(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskCreation.create", null, cause);
        this.taskId = taskId;
    }

    public String taskId() { return taskId; }
}
