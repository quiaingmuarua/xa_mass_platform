package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.task.TaskCommand;
import com.xa.mass.client.task.TaskCommandRequest;
import com.xa.mass.client.task.TaskContract;
import com.xa.mass.client.task.TaskCreateRequest;
import com.xa.mass.client.task.TaskCreateResult;
import com.xa.mass.client.task.TaskExecutionSpec;
import com.xa.mass.client.task.TaskItemBatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TaskScenarioSeeder {
    private static final int DEFAULT_ITEM_BATCH_SIZE = 500;

    private final ScenarioLauncherOptions options;
    private final ObjectMapper objectMapper;
    private final ScenarioClientFactory clientFactory;

    TaskScenarioSeeder(ScenarioLauncherOptions options,
                       ObjectMapper objectMapper,
                       ScenarioClientFactory clientFactory) {
        this.options = Objects.requireNonNull(options, "options is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
    }

    void seed(List<TaskScenarioSpec> taskSpecs) {
        if (taskSpecs == null || taskSpecs.isEmpty()) {
            System.out.println("[java-scenario-launcher] no tasks configured");
            return;
        }
        for (TaskScenarioSpec taskSpec : taskSpecs) {
            seedTask(taskSpec);
        }
    }

    static List<List<Object>> chunks(List<Object> items, int chunkSize) {
        int normalizedChunkSize = Math.max(1, chunkSize);
        List<List<Object>> result = new ArrayList<>();
        for (int index = 0; index < items.size(); index += normalizedChunkSize) {
            result.add(List.copyOf(items.subList(index, Math.min(items.size(), index + normalizedChunkSize))));
        }
        return result;
    }

    private void seedTask(TaskScenarioSpec taskSpec) {
        Map<String, Object> body = taskSpec.body() == null ? Map.of() : taskSpec.body();
        MassPlatform client = clientFactory.forApiKey(taskApiKey(taskSpec));
        TaskCreateResult createResult = client.tasks().create(toCreateRequest(body));
        String taskId = createResult.taskId();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("task create response did not include taskId");
        }
        System.out.printf("[java-scenario-launcher] created task project=%s event=%s taskId=%s%n",
                body.get("project"), body.get("eventCode"), taskId);
        appendItems(client, taskId, body, taskSpec.itemBatchSize());
        MassPlatform commandClient = clientFactory.forApiKey(options.taskCommandApiKey());
        if (!Boolean.TRUE.equals(body.get("keepIntakeOpen"))) {
            commandClient.tasks().command(taskId, TaskCommandRequest.builder(TaskCommand.SEAL).build());
        }
        if (taskSpec.shouldApprove()) {
            commandClient.tasks().command(taskId, TaskCommandRequest.builder(TaskCommand.APPROVE).build());
            System.out.printf("[java-scenario-launcher] approved task %s%n", taskId);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendItems(MassPlatform client, String taskId, Map<String, Object> body, Integer configuredBatchSize) {
        Object itemsValue = body.get("items");
        if (!(itemsValue instanceof List<?> rawItems) || rawItems.isEmpty()) {
            return;
        }
        List<Object> items = new ArrayList<>((List<Object>) rawItems);
        int batchSize = configuredBatchSize == null || configuredBatchSize <= 0
                ? DEFAULT_ITEM_BATCH_SIZE
                : configuredBatchSize;
        String eventCode = body.get("eventCode") instanceof String value ? value : null;
        for (List<Object> batch : chunks(items, batchSize)) {
            client.tasks().appendItems(taskId, TaskItemBatch.builder()
                    .eventCode(eventCode)
                    .items(batch)
                    .build());
        }
    }

    @SuppressWarnings("unchecked")
    private TaskCreateRequest toCreateRequest(Map<String, Object> body) {
        TaskCreateRequest.Builder builder = TaskCreateRequest.builder()
                .userId(stringValue(body.get("userId")))
                .project(stringValue(body.get("project")))
                .sourceRef(stringValue(body.get("sourceRef")));
        Object contract = body.get("contract");
        if (contract instanceof String value && !value.isBlank()) {
            builder.contract(TaskContract.valueOf(value));
        }
        Object sharedConfig = body.get("sharedConfig");
        if (sharedConfig instanceof Map<?, ?> map) {
            builder.sharedConfig(asObjectMap(map));
        }
        Object executionSpec = body.get("executionSpec");
        if (executionSpec instanceof Map<?, ?> map) {
            builder.executionSpec(objectMapper.convertValue(asObjectMap(map), TaskExecutionSpec.class));
        } else {
            TaskExecutionSpec normalized = normalizeExecutionSpec(body);
            if (normalized != null) {
                builder.executionSpec(normalized);
            }
        }
        return builder.build();
    }

    private TaskExecutionSpec normalizeExecutionSpec(Map<String, Object> body) {
        TaskExecutionSpec.Builder builder = TaskExecutionSpec.builder();
        boolean hasValue = false;
        if (body.get("batchSize") instanceof Number number && number.intValue() > 0) {
            builder.batchSize(number.intValue());
            hasValue = true;
        }
        if (body.get("workloadClass") instanceof String value && !value.isBlank()) {
            builder.workloadClass(value);
            hasValue = true;
        }
        if (body.get("maxRuntimeSeconds") instanceof Number number && number.intValue() >= 0) {
            builder.maxRuntimeSeconds(number.intValue());
            hasValue = true;
        }
        return hasValue ? builder.build() : null;
    }

    private String taskApiKey(TaskScenarioSpec taskSpec) {
        if (taskSpec.apiKey() != null && !taskSpec.apiKey().isBlank()) {
            return taskSpec.apiKey();
        }
        return options.taskApiKey();
    }

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Map<String, Object> asObjectMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
