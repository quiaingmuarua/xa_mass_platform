package com.xa.mass.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.InternalDebugTaskInvocationApiRequest;
import com.xa.mass.api.sync.SyncTaskResultBridge;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/internal/v1/debug")
public class InternalDebugTaskInvocationController {

    private static final int MAX_INGEST_ITEM_COUNT = Integer.getInteger("xa.mass.api.maxIngestItemCount", 500);
    private static final int MAX_INGEST_ITEM_BYTES = Integer.getInteger("xa.mass.api.maxIngestItemBytes", 64 * 1024);
    private static final int MAX_INGEST_TOTAL_BYTES = Integer.getInteger("xa.mass.api.maxIngestTotalBytes", 1024 * 1024);
    private static final ObjectMapper SIZE_OBJECT_MAPPER = new ObjectMapper();

    private final TaskAdminOperations taskAdmin;
    private final ControlPlaneCatalog catalog;
    private final ApiAuthService apiAuthService;
    private final ApiAuthorizationService apiAuthorizationService;
    private final TaskSecurityViewSupport taskSecurityViewSupport;
    private final SyncTaskResultBridge syncBridge;

    @Autowired
    public InternalDebugTaskInvocationController(TaskAdminOperations taskAdmin,
                                                 ControlPlaneCatalog catalog,
                                                 ApiAuthService apiAuthService,
                                                 ApiAuthorizationService apiAuthorizationService,
                                                 TaskSecurityViewSupport taskSecurityViewSupport,
                                                 SyncTaskResultBridge syncBridge) {
        this.taskAdmin = taskAdmin;
        this.catalog = catalog == null ? DefaultProjectEventCatalogFactory.createDefaultProjectRegistry() : catalog;
        this.apiAuthService = apiAuthService == null ? new ApiAuthService() : apiAuthService;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
        this.taskSecurityViewSupport = taskSecurityViewSupport == null ? new TaskSecurityViewSupport() : taskSecurityViewSupport;
        this.syncBridge = syncBridge;
    }

    @PostMapping("/task-invocations:sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTaskSync(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) Long timeoutMs,
            HttpServletRequest httpRequest,
            @RequestBody InternalDebugTaskInvocationApiRequest requestBody) {
        if (syncBridge == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Sync task API is not available in this runtime profile"));
        }
        try {
            validateKnownFields(requestBody, "sync task invocation");
            validateSyncRequest(requestBody);
            validateIngestGuardrails(requestBody.getItems());

            long resolvedTimeoutMs = resolveTimeoutMs(timeoutMs);
            String correlationId = UUID.randomUUID().toString();
            CompletableFuture<TaskWorkFinalSnapshot> future = syncBridge.register(correlationId);

            ApiAuthorizationService.AuthorizedSubmitterTaskCreate submitterTaskCreate =
                    resolveSubmitterTaskCreate(apiKeyHeader, authorizationHeader, requestBody);
            TaskShellSnapshot task = submitterTaskCreate != null
                    ? taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                    toMassTaskShellCreateRequest(requestBody, submitterTaskCreate.project(),
                            submitterTaskCreate.userId(), correlationId),
                    submitterTaskCreate.principal()
            ))
                    : null;
            if (submitterTaskCreate != null) {
            } else {
                PrincipalContext operator = apiAuthService.requireAuthenticated(httpRequest);
                task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                        toMassTaskShellCreateRequest(requestBody, requestBody.getProject(), requestBody.getUserId(), correlationId),
                    operator
                ));
            }

            taskAdmin.appendTaskItems(task.getTaskId(), MassTaskItemBatchAppendRequest.builder()
                    .eventCode(requestBody.getEventCode())
                    .items(requestBody.getItems())
                    .build());
            taskAdmin.sealTask(task.getTaskId());
            taskAdmin.approveTask(task.getTaskId());

            String taskId = task.getTaskId();
            Optional<TaskWorkFinalSnapshot> result =
                    syncBridge.await(correlationId, future, resolvedTimeoutMs);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            if (result.isPresent()) {
                TaskWorkFinalSnapshot msg = result.get();
                data.put("messageId", msg.messageId() != null ? msg.messageId() : "");
                data.put("synced", true);
                data.put("timedOut", false);
                data.put("status", msg.status() != null ? msg.status() : "UNKNOWN");
                data.put("output", msg.output());
                data.put("errorCode", msg.errorCode() != null ? msg.errorCode() : "");
                data.put("errorMessage", msg.errorMessage() != null ? msg.errorMessage() : "");
            } else {
                data.put("messageId", "");
                data.put("synced", false);
                data.put("timedOut", true);
                data.put("timeoutMs", resolvedTimeoutMs);
            }
            return ResponseEntity.ok(ApiResponse.success(data));
        } catch (SdkUnauthenticatedException e) {
            return ResponseEntity.status(401).body(ApiResponse.error(401, e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(ApiResponse.error(403, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Sync task invocation failed: " + e.getMessage()));
        }
    }

    private void validateKnownFields(InternalDebugTaskInvocationApiRequest requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateSyncRequest(InternalDebugTaskInvocationApiRequest requestBody) {
        if (requestBody.getMode() != null && requestBody.getMode() != TaskMode.SINGLE_RUN) {
            throw new IllegalArgumentException("Sync task requires SINGLE_RUN mode, got: " + requestBody.getMode());
        }
        List<Object> items = requestBody.getItems();
        if (items == null || items.size() != 1) {
            throw new IllegalArgumentException("Sync task requires exactly one item");
        }
    }

    private void validateIngestGuardrails(List<Object> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must contain at least one work item");
        }
        if (items.size() > MAX_INGEST_ITEM_COUNT) {
            throw new IllegalArgumentException("items exceed ingest batch limit: "
                    + items.size() + " > " + MAX_INGEST_ITEM_COUNT);
        }
        int totalBytes = 0;
        for (Object item : items) {
            try {
                int bytes = SIZE_OBJECT_MAPPER.writeValueAsString(item).getBytes(StandardCharsets.UTF_8).length;
                if (bytes > MAX_INGEST_ITEM_BYTES) {
                    throw new IllegalArgumentException("single item exceeds size limit: "
                            + bytes + " > " + MAX_INGEST_ITEM_BYTES);
                }
                totalBytes += bytes;
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("item cannot be serialized as JSON: " + e.getMessage(), e);
            }
        }
        if (totalBytes > MAX_INGEST_TOTAL_BYTES) {
            throw new IllegalArgumentException("items exceed total size limit: "
                    + totalBytes + " > " + MAX_INGEST_TOTAL_BYTES);
        }
    }

    private long resolveTimeoutMs(Long requested) {
        if (requested == null || requested <= 0) {
            return SyncTaskResultBridge.DEFAULT_TIMEOUT_MS;
        }
        return Math.min(requested, SyncTaskResultBridge.MAX_TIMEOUT_MS);
    }

    private ApiAuthorizationService.AuthorizedSubmitterTaskCreate resolveSubmitterTaskCreate(String apiKeyHeader,
                                                                                             String authorizationHeader,
                                                                                             InternalDebugTaskInvocationApiRequest requestBody) {
        try {
            return apiAuthorizationService.resolveAuthorizedSubmitterTaskCreate(
                    apiKeyHeader,
                    authorizationHeader,
                    requestBody != null ? requestBody.getProject() : null,
                    requestBody != null ? requestBody.getEventCode() : null,
                    requestBody != null ? requestBody.getUserId() : null,
                    Map.of(
                            "mode", requestBody != null ? String.valueOf(requestBody.getMode()) : "",
                            "eventCode", requestBody != null ? String.valueOf(requestBody.getEventCode()) : "",
                            "scenario", ApiSecurityScenario.SUBMITTER_TASK_CREATE.name()
                    )
            );
        } catch (com.xa.mass.api.auth.ApiUnauthenticatedException ex) {
            throw new SdkUnauthenticatedException(ex.getMessage());
        }
    }

    private void requireBusinessBindings(String project, String userId) {
        requireProjectCode(project);
        requireUserId(userId);
    }

    private MassTaskShellCreateRequest toMassTaskShellCreateRequest(InternalDebugTaskInvocationApiRequest requestBody,
                                                                    String resolvedProject,
                                                                    String resolvedUserId,
                                                                    String syncKey) {
        requireBusinessBindings(resolvedProject, resolvedUserId);
        if (requestBody.getEventCode() != null && !requestBody.getEventCode().isBlank()) {
            validateProjectAndEvent(resolvedProject, requestBody.getEventCode());
        }
        TaskExecutionOptions executionSpec = new TaskExecutionOptions();
        executionSpec.setBatchSize(Math.max(requestBody.getBatchSize(), 1));
        executionSpec.setMaxRuntimeSeconds(requestBody.getMaxRuntimeSeconds());
        executionSpec.setWorkloadClass(requestBody.getWorkloadClass());
        return MassTaskShellCreateRequest.builder()
                .userId(resolvedUserId)
                .tenantId(resolveProjectTenantId(resolvedProject))
                .project(resolvedProject)
                .contract("BATCH")
                .sharedConfig(mergeSyncKey(requestBody.getSharedConfig(), syncKey))
                .executionSpec(executionSpec)
                .sourceRef(requestBody.getSourceRef())
                .build();
    }

    private Map<String, Object> mergeSyncKey(Map<String, Object> existing, String syncKey) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(taskSecurityViewSupport.sanitizeSharedConfig(existing));
        }
        merged.put(SyncTaskResultBridge.SYNC_KEY, syncKey);
        return Map.copyOf(merged);
    }

    private void validateProjectAndEvent(String projectCode, String eventCode) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        if (projectDefinition == null) {
            throw new IllegalArgumentException("Unsupported project code: " + projectCode);
        }
        if (catalog.getEvent(eventCode) == null) {
            throw new IllegalArgumentException("Unsupported event code: " + eventCode);
        }
        if (!projectDefinition.getAuthorizedEventCodes().contains(eventCode)) {
            throw new IllegalArgumentException("Project " + projectCode + " does not support event " + eventCode);
        }
    }

    private String resolveProjectTenantId(String projectCode) {
        ProjectDefinition projectDefinition = catalog.getProject(projectCode);
        if (projectDefinition == null) {
            throw new IllegalArgumentException("Unsupported project code: " + projectCode);
        }
        return projectDefinition.getTenantId();
    }

    private String requireProjectCode(String project) {
        if (project == null || project.isBlank()) {
            throw new IllegalArgumentException("project is required");
        }
        return project.trim();
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        return userId.trim();
    }

    private static final class SdkUnauthenticatedException extends RuntimeException {
        private SdkUnauthenticatedException(String message) {
            super(message);
        }
    }
}
