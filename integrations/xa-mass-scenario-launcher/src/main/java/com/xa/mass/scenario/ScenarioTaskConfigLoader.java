package com.xa.mass.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ScenarioTaskConfigLoader {
    private static final TypeReference<List<Object>> OBJECT_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    private ScenarioTaskConfigLoader() {
    }

    static List<TaskScenarioSpec> load(Path configPath, ObjectMapper objectMapper) throws IOException {
        ScenarioLauncherConfig.Loaded loaded = ScenarioLauncherConfig.load(configPath, objectMapper);
        return toTaskSpecs(loaded, objectMapper);
    }

    static List<TaskScenarioSpec> toTaskSpecs(ScenarioLauncherConfig.Loaded loaded, ObjectMapper objectMapper)
            throws IOException {
        Objects.requireNonNull(loaded, "loaded config is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        ScenarioLauncherConfig config = loaded.config();
        List<ScenarioLauncherConfig.TaskConfig> tasks = config.tasks();
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks is required in task launcher config");
        }
        List<TaskScenarioSpec> result = new ArrayList<>();
        for (int index = 0; index < tasks.size(); index++) {
            result.add(toTaskSpec(loaded, objectMapper, tasks.get(index), index));
        }
        return List.copyOf(result);
    }

    private static TaskScenarioSpec toTaskSpec(ScenarioLauncherConfig.Loaded loaded,
                                               ObjectMapper objectMapper,
                                               ScenarioLauncherConfig.TaskConfig task,
                                               int index) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(task.body() == null ? Map.of() : task.body());
        putField(body, "project", task.project(), taskField(index, "project"));
        putField(body, "userId", task.userId(), taskField(index, "userId"));
        putField(body, "sourceRef", task.sourceRef(), taskField(index, "sourceRef"));
        putField(body, "contract", task.contract(), taskField(index, "contract"));
        putField(body, "sharedConfig", task.sharedConfig(), taskField(index, "sharedConfig"));
        putField(body, "executionSpec", task.executionSpec(), taskField(index, "executionSpec"));

        ScenarioLauncherConfig.ActionConfig action = resolveAction(loaded.config(), task, index);
        String resolvedEventCode = resolveEventCode(task, action, index);
        putField(body, "eventCode", resolvedEventCode, taskField(index, "eventCode"));

        Object items = task.items();
        if (items != null) {
            body.put("items", resolveItems(loaded, objectMapper, items, action, index));
        }

        Integer batchSize = task.itemBatchSize() != null
                ? task.itemBatchSize()
                : loaded.config().runtime() == null ? null : loaded.config().runtime().taskItemBatchSize();
        return new TaskScenarioSpec(task.apiKey(), batchSize, Map.copyOf(body));
    }

    private static ScenarioLauncherConfig.ActionConfig resolveAction(ScenarioLauncherConfig config,
                                                                     ScenarioLauncherConfig.TaskConfig task,
                                                                     int taskIndex) {
        String actionName = normalize(task.action());
        if (actionName == null) {
            return null;
        }
        Map<String, ScenarioLauncherConfig.ActionConfig> actions = config.actions();
        ScenarioLauncherConfig.ActionConfig action = actions == null ? null : actions.get(actionName);
        if (action == null) {
            throw new IllegalArgumentException(taskField(taskIndex, "action") + " is unknown: " + actionName);
        }
        return action;
    }

    private static String resolveEventCode(ScenarioLauncherConfig.TaskConfig task,
                                           ScenarioLauncherConfig.ActionConfig action,
                                           int taskIndex) {
        String taskEventCode = normalize(task.eventCode());
        String actionEventCode = action == null ? null : normalize(action.eventCode());
        if (action != null && actionEventCode == null) {
            throw new IllegalArgumentException(taskField(taskIndex, "action") + " does not define eventCode");
        }
        if (taskEventCode != null && actionEventCode != null && !taskEventCode.equals(actionEventCode)) {
            throw new IllegalArgumentException(taskField(taskIndex, "eventCode")
                    + " conflicts with action eventCode: " + taskEventCode + " != " + actionEventCode);
        }
        return taskEventCode != null ? taskEventCode : actionEventCode;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> resolveItems(ScenarioLauncherConfig.Loaded loaded,
                                             ObjectMapper objectMapper,
                                             Object items,
                                             ScenarioLauncherConfig.ActionConfig action,
                                             int taskIndex) throws IOException {
        List<Object> rawItems;
        Map<String, String> itemParamMap = Map.of();
        List<String> itemJsonFields = List.of();
        if (items instanceof List<?> list) {
            rawItems = new ArrayList<>((List<Object>) list);
        } else if (items instanceof Map<?, ?> map) {
            ItemSource source = itemSource(map, taskIndex);
            rawItems = source.read(loaded, objectMapper, taskIndex);
            itemParamMap = source.paramMap();
            itemJsonFields = source.jsonFields();
        } else {
            throw new IllegalArgumentException(taskField(taskIndex, "items") + " must be an array or item source object");
        }
        Map<String, String> paramMap = mergedParamMap(action == null ? null : action.paramMap(), itemParamMap, taskIndex);
        List<String> jsonFields = mergedJsonFields(action == null ? null : action.jsonFields(), itemJsonFields);
        return rawItems.stream()
                .map(item -> normalizeItem(objectMapper, item, paramMap, jsonFields, taskIndex))
                .toList();
    }

    private static ItemSource itemSource(Map<?, ?> map, int taskIndex) {
        String type = stringValue(map.get("type"));
        String path = stringValue(map.get("path"));
        String field = stringValue(map.get("field"));
        Map<String, String> paramMap = stringMap(map.get("paramMap"), taskField(taskIndex, "items.paramMap"));
        List<String> jsonFields = stringList(map.get("jsonFields"), taskField(taskIndex, "items.jsonFields"));
        if (type == null) {
            throw new IllegalArgumentException(taskField(taskIndex, "items.type") + " is required");
        }
        if (path == null) {
            throw new IllegalArgumentException(taskField(taskIndex, "items.path") + " is required");
        }
        return new ItemSource(type, path, field, paramMap, jsonFields);
    }

    private static Object normalizeItem(ObjectMapper objectMapper,
                                        Object item,
                                        Map<String, String> paramMap,
                                        List<String> jsonFields,
                                        int taskIndex) {
        Object mapped = paramMap.isEmpty() ? item : applyParamMap(item, paramMap, taskIndex);
        if (jsonFields.isEmpty()) {
            return mapped;
        }
        if (!(mapped instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(taskField(taskIndex, "items") + " jsonFields require object items");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        for (String jsonField : jsonFields) {
            Object value = result.get(jsonField);
            if (value instanceof String text && !text.isBlank()) {
                try {
                    result.put(jsonField, objectMapper.readValue(text, Object.class));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(taskField(taskIndex, "items")
                            + " contains invalid JSON field " + jsonField, e);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Object applyParamMap(Object item, Map<String, String> paramMap, int taskIndex) {
        if (!(item instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(taskField(taskIndex, "items") + " paramMap requires object items");
        }
        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            source.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : paramMap.entrySet()) {
            String sourceField = entry.getKey();
            String targetField = entry.getValue();
            if (!source.containsKey(sourceField)) {
                throw new IllegalArgumentException(taskField(taskIndex, "items")
                        + " missing paramMap source field: " + sourceField);
            }
            result.put(targetField, source.get(sourceField));
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> mergedParamMap(Map<String, String> actionMap,
                                                      Map<String, String> itemMap,
                                                      int taskIndex) {
        Map<String, String> action = actionMap == null ? Map.of() : actionMap;
        Map<String, String> item = itemMap == null ? Map.of() : itemMap;
        if (!action.isEmpty() && !item.isEmpty()) {
            throw new IllegalArgumentException(taskField(taskIndex, "items.paramMap")
                    + " cannot be combined with action paramMap");
        }
        return !item.isEmpty() ? item : action;
    }

    private static List<String> mergedJsonFields(List<String> actionFields, List<String> itemFields) {
        List<String> result = new ArrayList<>();
        if (actionFields != null) {
            result.addAll(actionFields);
        }
        if (itemFields != null) {
            result.addAll(itemFields);
        }
        return List.copyOf(result);
    }

    private static void putField(Map<String, Object> body, String key, Object value, String fieldName) {
        if (value == null) {
            return;
        }
        Object existing = body.get(key);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException(fieldName + " conflicts with body." + key);
        }
        body.put(key, value);
    }

    private static String taskField(int index, String field) {
        return "tasks[" + index + "]." + field;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Map<String, String> stringMap(Object value, String fieldName) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(fieldName + " values must be nonblank strings");
            }
            result.put(String.valueOf(entry.getKey()), text);
        }
        return Map.copyOf(result);
    }

    private static List<String> stringList(Object value, String fieldName) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(fieldName + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(fieldName + " values must be nonblank strings");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private record ItemSource(
            String type,
            String path,
            String field,
            Map<String, String> paramMap,
            List<String> jsonFields
    ) {
        List<Object> read(ScenarioLauncherConfig.Loaded loaded,
                          ObjectMapper objectMapper,
                          int taskIndex) throws IOException {
            Path resolvedPath = loaded.resolvePath(path, taskField(taskIndex, "items.path"));
            return switch (type) {
                case "json", "json-array" -> readJsonArray(objectMapper, resolvedPath, taskIndex);
                case "jsonl" -> readJsonLines(objectMapper, resolvedPath, taskIndex);
                case "txt", "text" -> readText(resolvedPath, taskIndex);
                default -> throw new IllegalArgumentException(taskField(taskIndex, "items.type")
                        + " is unsupported: " + type);
            };
        }

        private static List<Object> readJsonArray(ObjectMapper objectMapper, Path path, int taskIndex) throws IOException {
            Object value;
            try (var input = Files.newInputStream(path)) {
                value = objectMapper.readValue(input, Object.class);
            }
            if (!(value instanceof List<?> list)) {
                throw new IllegalArgumentException(taskField(taskIndex, "items.path") + " must contain a JSON array: " + path);
            }
            return List.copyOf(list);
        }

        private static List<Object> readJsonLines(ObjectMapper objectMapper, Path path, int taskIndex) throws IOException {
            List<Object> result = new ArrayList<>();
            int lineNumber = 0;
            for (String line : Files.readAllLines(path)) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    result.add(objectMapper.readValue(line, Object.class));
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException(taskField(taskIndex, "items.path")
                            + " contains invalid JSONL at line " + lineNumber + ": " + path, e);
                }
            }
            return List.copyOf(result);
        }

        private List<Object> readText(Path path, int taskIndex) throws IOException {
            String normalizedField = normalize(field);
            if (normalizedField == null) {
                throw new IllegalArgumentException(taskField(taskIndex, "items.field") + " is required for text item sources");
            }
            List<Object> result = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (!line.isBlank()) {
                    result.add(Map.of(normalizedField, line.trim()));
                }
            }
            return List.copyOf(result);
        }
    }
}
