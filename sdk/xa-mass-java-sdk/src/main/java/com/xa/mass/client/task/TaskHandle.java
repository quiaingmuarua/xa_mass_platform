package com.xa.mass.client.task;

import com.xa.mass.client.http.MassHttpStreamResponse;
import com.xa.mass.contract.task.TaskCommandRequest;
import com.xa.mass.contract.task.TaskItemBatch;
import com.xa.mass.contract.task.TaskItemSyncRequest;

import java.util.Objects;

public final class TaskHandle {
    private final TaskClient client;
    private final String taskId;

    TaskHandle(TaskClient client, String taskId) {
        this.client = Objects.requireNonNull(client, "client is required");
        TaskClient.encode(taskId);
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }

    public TaskAppendResult appendItems(TaskItemBatch request) {
        return client.appendItems(taskId, request);
    }

    public TaskSyncAppendResult appendItemSync(TaskItemSyncRequest request) {
        return client.appendItemSync(taskId, request);
    }

    public TaskCommandResult command(TaskCommandRequest request) {
        return client.command(taskId, request);
    }

    public TaskCommandResult approve() {
        return command(TaskCommandRequest.approve());
    }

    public TaskCommandResult seal() {
        return client.seal(taskId);
    }

    public TaskResultWindow results(TaskResultReadRequest request) {
        return client.results(taskId, request);
    }

    public TaskResultArchive archive() {
        return client.archive(taskId);
    }

    public MassHttpStreamResponse downloadArchive() {
        return client.downloadArchive(taskId);
    }
}
