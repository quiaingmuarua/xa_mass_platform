package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.sdk.SdkTaskAppendItemsApiRequest;
import com.xa.mass.api.model.sdk.SdkTaskCreateApiRequest;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.JsonInput;
import com.xa.mass.sdk.model.MassInput;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.TextInput;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK-facing task APIs that do not depend on the control-console endpoints.
 */
@RestController
@RequestMapping("/sdk/tasks")
public class SdkTaskController {

    static final String API_KEY_HEADER = "X-Mass-Api-Key";
    private static final String DEFAULT_SUBMITTER_USER_ID = "sdk-client";

    private final TaskManager taskManager;
    private final ProjectEventCatalog projectEventCatalog;
    @Nullable
    private final AuthProvider authProvider;

    public SdkTaskController(TaskManager taskManager,
                             ProjectEventCatalog projectEventCatalog,
                             @Nullable AuthProvider authProvider) {
        this.taskManager = taskManager;
        this.projectEventCatalog = projectEventCatalog;
        this.authProvider = authProvider;
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(
            @RequestBody SdkTaskCreateApiRequest requestBody,
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey) {
        try {
            validateKnownFields(requestBody, "sdk task create");
            TaskSubmitterContext submitterContext = resolveSubmitterContext(apiKey);
            MassTaskRequest request = toMassTaskRequest(requestBody, submitterContext);
            validateProjectAndEvent(request.getProject(), request.getEventCode());
            Task task = taskManager.createTask(toEngineRequest(request));
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "taskId", task.getTid(),
                    "message", "SDK task created"
            )));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTask(@PathVariable String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Task not found: " + taskId));
        }
        List<Map<String, Object>> items = taskManager.getTaskMessages(taskId).stream()
                .map(TaskMsg::getInput)
                .map(input -> input == null ? Map.<String, Object>of() : new LinkedHashMap<>(input))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task", task);
        response.put("items", items);
        response.put("stateValidation", taskManager.validateTaskState(taskId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{taskId}/items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> appendItems(@PathVariable String taskId,
                                                                        @RequestBody SdkTaskAppendItemsApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "sdk task append");
            Task task = taskManager.getTask(taskId);
            if (task == null) {
                return ResponseEntity.status(404).body(ApiResponse.error(404, "Task not found: " + taskId));
            }
            int added = taskManager.appendTaskItems(taskId, toAppendInputs(requestBody, task));
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "taskId", taskId,
                    "added", added,
                    "message", "SDK task items appended"
            )));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PutMapping("/{taskId}/seal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sealTask(@PathVariable String taskId) {
        Task task = taskManager.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Task not found: " + taskId));
        }
        if (!taskManager.sealTask(taskId)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Task is not open-ended: " + taskId));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "taskId", taskId,
                "message", "SDK task intake sealed"
        )));
    }

    private MassTaskRequest toMassTaskRequest(SdkTaskCreateApiRequest requestBody, TaskSubmitterContext submitterContext) {
        String userId = firstNonBlank(
                requestBody.getUserId(),
                submitterContext != null ? submitterContext.getUserId() : null,
                submitterContext != null ? submitterContext.getPrincipalId() : null,
                DEFAULT_SUBMITTER_USER_ID
        );
        String project = firstNonBlank(
                requestBody.getProject(),
                submitterContext != null ? submitterContext.getProjectScope() : null
        );
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
        if (requestBody.getTaskName() == null || requestBody.getTaskName().isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        if (requestBody.getEventCode() == null || requestBody.getEventCode().isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }

        return MassTaskRequest.builder()
                .userId(userId)
                .project(project)
                .taskName(requestBody.getTaskName())
                .eventCode(requestBody.getEventCode())
                .mode(requestBody.getMode() != null ? requestBody.getMode() : TaskMode.SINGLE_RUN)
                .payloadType(requestBody.getPayloadType() != null ? requestBody.getPayloadType() : PayloadType.JSON)
                .sharedConfig(requestBody.getSharedConfig())
                .inputs(toMassInputs(requestBody.getInputs(), requestBody.getPayloadType()))
                .routingCode(resolveRoutingCode(requestBody.getRoutingCode(), requestBody.getEventCode()))
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .build();
    }

    private TaskCreateRequestDto toEngineRequest(MassTaskRequest request) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setProject(request.getProject());
        dto.setTaskName(request.getTaskName());
        dto.setSharedConfig(withSdkMetadata(request));
        dto.setInputs(request.toEngineInputs());
        dto.setRoutingCode(request.getRoutingCode());
        dto.setBatchSize(request.getBatchSize());
        dto.setDefaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount());
        dto.setOpenEnded(request.isStreaming());
        dto.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        return dto;
    }

    private Map<String, Object> withSdkMetadata(MassTaskRequest request) {
        Map<String, Object> merged = new LinkedHashMap<>(request.getSharedConfig());
        Map<String, Object> sdkMetadata = new LinkedHashMap<>();
        if (request.getEventCode() != null && !request.getEventCode().isBlank()) {
            sdkMetadata.put("eventCode", request.getEventCode());
        }
        sdkMetadata.put("payloadType", request.getPayloadType().name());
        sdkMetadata.put("taskMode", request.getMode().name());
        if (!sdkMetadata.isEmpty()) {
            merged.put("_sdk", Map.copyOf(sdkMetadata));
        }
        return Map.copyOf(merged);
    }

    private List<Map<String, Object>> toAppendInputs(SdkTaskAppendItemsApiRequest requestBody, Task task) {
        List<Object> rawInputs = requestBody.getInputs();
        if (rawInputs == null || rawInputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must contain at least one work item");
        }
        PayloadType payloadType = resolvePayloadType(task);
        if (payloadType == null) {
            return rawInputs.stream()
                    .map(this::mapInputWithoutDeclaredPayloadType)
                    .toList();
        }
        return toMassInputs(rawInputs, payloadType).stream()
                .map(MassInput::toTaskMsgInput)
                .toList();
    }

    private Map<String, Object> mapInputWithoutDeclaredPayloadType(Object rawInput) {
        if (rawInput instanceof String text) {
            return new TextInput(text).toTaskMsgInput();
        }
        if (rawInput instanceof Map<?, ?> map) {
            return new JsonInput(stringObjectMap(map)).toTaskMsgInput();
        }
        throw new IllegalArgumentException("Unsupported input item type: " + rawInput);
    }

    private List<MassInput> toMassInputs(List<Object> rawInputs, PayloadType payloadType) {
        if (rawInputs == null || rawInputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must contain at least one work item");
        }
        PayloadType resolvedPayloadType = payloadType != null ? payloadType : PayloadType.JSON;
        return rawInputs.stream()
                .map(rawInput -> toMassInput(rawInput, resolvedPayloadType))
                .toList();
    }

    private MassInput toMassInput(Object rawInput, PayloadType payloadType) {
        return switch (payloadType) {
            case TEXT -> {
                if (!(rawInput instanceof String text)) {
                    throw new IllegalArgumentException("TEXT payloadType requires string inputs");
                }
                yield new TextInput(text);
            }
            case JSON -> {
                if (!(rawInput instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("JSON payloadType requires object inputs");
                }
                yield new JsonInput(stringObjectMap(map));
            }
        };
    }

    private PayloadType resolvePayloadType(Task task) {
        if (task == null || task.getSharedConfig() == null) {
            return null;
        }
        Object sdk = task.getSharedConfig().get("_sdk");
        if (!(sdk instanceof Map<?, ?> sdkMetadata)) {
            return null;
        }
        Object payloadType = sdkMetadata.get("payloadType");
        if (!(payloadType instanceof String payloadTypeName) || payloadTypeName.isBlank()) {
            return null;
        }
        return PayloadType.valueOf(payloadTypeName);
    }

    /**
     * Resolves the effective routing code for a task request.
     * Falls back to the event's {@code defaultRoutingCode} when the request does not
     * specify one, so event metadata actually drives dispatch defaults.
     */
    private String resolveRoutingCode(String requestedRoutingCode, String eventCode) {
        if (requestedRoutingCode != null && !requestedRoutingCode.isBlank()) {
            return requestedRoutingCode;
        }
        if (eventCode != null && !eventCode.isBlank()) {
            EventMetadata eventMeta = projectEventCatalog.getEvent(eventCode);
            if (eventMeta != null && eventMeta.getDefaultRoutingCode() != null
                    && !eventMeta.getDefaultRoutingCode().isBlank()) {
                return eventMeta.getDefaultRoutingCode();
            }
        }
        return requestedRoutingCode;
    }

    private void validateProjectAndEvent(String projectCode, String eventCode) {
        ProjectMetadata projectMetadata = projectEventCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            throw new IllegalArgumentException("Unsupported project metadata code: " + projectCode);
        }
        if (projectEventCatalog.getEvent(eventCode) == null) {
            throw new IllegalArgumentException("Unsupported event code: " + eventCode);
        }
        if (!projectMetadata.getEventCodes().contains(eventCode)) {
            throw new IllegalArgumentException("Project " + projectCode + " does not support event " + eventCode);
        }
    }

    private TaskSubmitterContext resolveSubmitterContext(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || authProvider == null) {
            return null;
        }
        return authProvider.authenticate(apiKey);
    }

    private void validateKnownFields(Object requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody instanceof com.xa.mass.api.model.AbstractUnknownFieldRequest unknownFieldRequest
                && unknownFieldRequest.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", unknownFieldRequest.getUnknownFieldNames()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(copy);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
