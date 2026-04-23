package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.sdk.SdkTaskSubmitRequest;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.sdk.TaskOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.JsonInput;
import com.xa.mass.sdk.model.MassInput;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.TextInput;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sdk/tasks")
public class SdkTaskController {

    private final TaskOperations taskOperations;
    private final AuthProvider authProvider;

    public SdkTaskController(TaskOperations taskOperations, AuthProvider authProvider) {
        this.taskOperations = taskOperations;
        this.authProvider = authProvider;
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody SdkTaskSubmitRequest requestBody) {
        try {
            validateKnownFields(requestBody);
            TaskSubmitterContext submitter = SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
            if (submitter == null) {
                return unauthorized("Invalid or missing SDK credential");
            }

            String resolvedProject = resolveProject(requestBody, submitter);
            String resolvedUserId = resolveUserId(requestBody, submitter);
            Task task = taskOperations.createTask(toMassTaskRequest(requestBody, resolvedProject, resolvedUserId));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", task.getTid());
            data.put("project", task.getProject());
            data.put("userId", task.getUser() != null ? task.getUser().getUserId() : resolvedUserId);
            data.put("principalId", submitter.getPrincipalId());
            data.put("message", "SDK task created");
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, e.getMessage()));
        }
    }

    private void validateKnownFields(SdkTaskSubmitRequest requestBody) {
        if (requestBody == null || isEmptyRequest(requestBody)) {
            throw new IllegalArgumentException("sdk task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported sdk task fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        if (requestBody.getTaskName() == null || requestBody.getTaskName().isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        if (requestBody.getEventCode() == null || requestBody.getEventCode().isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }
    }

    private boolean isEmptyRequest(SdkTaskSubmitRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getTaskName() == null
                && requestBody.getEventCode() == null
                && requestBody.getMode() == null
                && requestBody.getPayloadType() == null
                && requestBody.getSharedConfig() == null
                && requestBody.getInputs() == null
                && requestBody.getBatchSize() == 0
                && requestBody.getDefaultMsgMaxRetryCount() == 3
                && requestBody.getMaxRuntimeSeconds() == 0;
    }

    private String resolveProject(SdkTaskSubmitRequest requestBody, TaskSubmitterContext submitter) {
        String requestedProject = SdkCredentialAuthSupport.firstNonBlank(requestBody.getProject());
        String scopedProject = SdkCredentialAuthSupport.firstNonBlank(submitter.getProjectScope());
        if (scopedProject != null) {
            if (requestedProject != null && !scopedProject.equals(requestedProject)) {
                throw new SecurityException("Submitter project scope does not allow project: " + requestedProject);
            }
            return scopedProject;
        }
        if (requestedProject != null) {
            return requestedProject;
        }
        throw new IllegalArgumentException("project is required when submitter has no project scope");
    }

    private String resolveUserId(SdkTaskSubmitRequest requestBody, TaskSubmitterContext submitter) {
        String requestedUserId = SdkCredentialAuthSupport.firstNonBlank(requestBody.getUserId());
        String scopedUserId = SdkCredentialAuthSupport.firstNonBlank(submitter.getUserId());
        if (scopedUserId != null) {
            if (requestedUserId != null && !scopedUserId.equals(requestedUserId)) {
                throw new SecurityException("Submitter user scope does not allow userId: " + requestedUserId);
            }
            return UserRef.requireUserId(scopedUserId);
        }
        if (requestedUserId != null) {
            return UserRef.requireUserId(requestedUserId);
        }
        return UserRef.requireUserId(submitter.getPrincipalId());
    }

    private MassTaskRequest toMassTaskRequest(SdkTaskSubmitRequest requestBody,
                                              String resolvedProject,
                                              String resolvedUserId) {
        PayloadType payloadType = requestBody.getPayloadType() != null
                ? requestBody.getPayloadType()
                : PayloadType.JSON;
        TaskMode mode = requestBody.getMode() != null
                ? requestBody.getMode()
                : TaskMode.SINGLE_RUN;
        List<MassInput> inputs = toMassInputs(requestBody.getInputs(), payloadType);
        return MassTaskRequest.builder()
                .userId(resolvedUserId)
                .project(resolvedProject)
                .taskName(requestBody.getTaskName())
                .eventCode(requestBody.getEventCode())
                .mode(mode)
                .payloadType(payloadType)
                .sharedConfig(requestBody.getSharedConfig())
                .inputs(inputs)
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .build();
    }

    private List<MassInput> toMassInputs(List<Object> rawInputs, PayloadType payloadType) {
        if (rawInputs == null || rawInputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must contain at least one work item");
        }
        return rawInputs.stream()
                .map(rawInput -> toMassInput(rawInput, payloadType))
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

    private Map<String, Object> stringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(copy);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> unauthorized(String message) {
        return ResponseEntity.status(401).body(ApiResponse.error(401, message));
    }
}
