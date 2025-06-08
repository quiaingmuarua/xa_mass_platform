package com.xa.mass.server;

import io.netty.channel.Channel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.concurrent.*;

public class TaskResultHandler {

    private static final Map<String, ScheduledFuture<?>> timeoutFutures = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final Gson gson = new Gson();

    public static void startTaskTimeoutCheck(String taskId, Channel channel) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            // 超时未回应，调用超时处理逻辑
            System.out.println("Task timeout: " + taskId);
            onTimeout(taskId);
        }, 5, TimeUnit.SECONDS);

        timeoutFutures.put(taskId, future);
    }

    public static void onClientResponse(String json) {
        TaskResult result = parseResult(json);
        String taskId = result.getTaskId();

        ScheduledFuture<?> future = timeoutFutures.remove(taskId);
        if (future != null) future.cancel(true);

        if (result.isSuccess()) {
            System.out.println("Task success: " + taskId);
        } else {
            System.out.println("Task failed: " + taskId);
        }
    }

    public static void onTimeout(String taskId) {
        // 通知上层：任务超时处理
        System.out.println("Handle timeout for task: " + taskId);
    }

    private static TaskResult parseResult(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String taskId = obj.get("taskId").getAsString();
        boolean success = obj.get("success").getAsBoolean();
        return new TaskResult(taskId, success);
    }

    // 简单模拟 TaskResult 类
    private static class TaskResult {
        private final String taskId;
        private final boolean success;

        public TaskResult(String taskId, boolean success) {
            this.taskId = taskId;
            this.success = success;
        }

        public String getTaskId() {
            return taskId;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}