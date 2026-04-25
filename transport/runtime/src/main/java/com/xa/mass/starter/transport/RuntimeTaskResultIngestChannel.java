package com.xa.mass.starter.transport;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Canonical runtime task-result ingest channel shared by WebSocket-adapter and pull
 * worker transport paths.
 */
public final class RuntimeTaskResultIngestChannel implements TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeTaskResultIngestChannel.class);

    private final TaskManager taskManager;

    public RuntimeTaskResultIngestChannel(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public boolean ingest(TaskResultReport report) {
        if (report == null) {
            return false;
        }
        String eventCode = resolveEventCode(report.getTaskId());
        logger.debug("Ingest task result via runtime channel: taskId={}, messageId={}, eventCode={}, success={}",
                report.getTaskId(), report.getMessageId(), eventCode, report.isSuccess());
        return taskManager.handleTaskMessageResult(
                report.getTaskId(),
                report.getMessageId(),
                report.isSuccess(),
                report.getDetail(),
                report.getErrorCode(),
                report.getOutput()
        );
    }

    private String resolveEventCode(String taskId) {
        Task task = taskManager.getTask(taskId);
        return TaskSharedConfig.sdkEventCode(task);
    }
}
