package com.xa.mass.client.task;

import com.xa.mass.client.http.MassHttpClient;
import com.xa.mass.client.http.MassHttpStreamResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TaskClient {
    private final MassHttpClient httpClient;

    public TaskClient(MassHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
    }

    public TaskListResult list(TaskListRequest request) {
        TaskListRequest resolved = request == null ? TaskListRequest.builder().build() : request;
        return httpClient.get("/api/v1/tasks" + resolved.toQueryString(), TaskListResult.class);
    }

    public TaskCreateResult create(TaskCreateRequest request) {
        return httpClient.post("/api/v1/tasks", Objects.requireNonNull(request, "request is required"),
                TaskCreateResult.class);
    }

    public TaskHandle forTask(String taskId) {
        return new TaskHandle(this, taskId);
    }

    public TaskGetResult get(String taskId) {
        return httpClient.get("/api/v1/tasks/" + encode(taskId), TaskGetResult.class);
    }

    public TaskUpdateResult update(String taskId, TaskUpdateRequest request) {
        return httpClient.patch("/api/v1/tasks/" + encode(taskId), Objects.requireNonNull(request, "request is required"),
                TaskUpdateResult.class);
    }

    public TaskAppendResult appendItems(String taskId, TaskItemBatch request) {
        return httpClient.post("/api/v1/tasks/" + encode(taskId) + "/items",
                Objects.requireNonNull(request, "request is required"), TaskAppendResult.class);
    }

    public TaskSyncAppendResult appendItemSync(String taskId, TaskItemSyncRequest request) {
        return httpClient.post("/api/v1/tasks/" + encode(taskId) + "/items:sync",
                Objects.requireNonNull(request, "request is required"), TaskSyncAppendResult.class);
    }

    public TaskCommandResult command(String taskId, TaskCommandRequest request) {
        return httpClient.post("/api/v1/tasks/" + encode(taskId) + "/commands",
                Objects.requireNonNull(request, "request is required"), TaskCommandResult.class);
    }

    public TaskCommandResult seal(String taskId) {
        return command(taskId, TaskCommandRequest.seal());
    }

    public TaskResultWindow results(String taskId, TaskResultReadRequest request) {
        TaskResultReadRequest resolved = request == null ? TaskResultReadRequest.builder().build() : request;
        return httpClient.get("/api/v1/tasks/" + encode(taskId) + "/results" + resolved.toQueryString(),
                TaskResultWindow.class);
    }

    public TaskResultArchive archive(String taskId) {
        return httpClient.get("/api/v1/tasks/" + encode(taskId) + "/results/archive", TaskResultArchive.class);
    }

    public MassHttpStreamResponse downloadArchive(String taskId) {
        return httpClient.getStream("/api/v1/tasks/" + encode(taskId) + "/results/archive/content");
    }

    static String encode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("path value is required");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String query(List<QueryParam> params) {
        List<String> encoded = new ArrayList<>();
        for (QueryParam param : params) {
            if (param.value() != null && !param.value().isBlank()) {
                encoded.add(encode(param.name()) + "=" + encode(param.value()));
            }
        }
        return encoded.isEmpty() ? "" : "?" + String.join("&", encoded);
    }

    record QueryParam(String name, String value) {
    }
}
