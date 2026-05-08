package com.xa.mass.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.TaskCreateApiRequest;
import com.xa.mass.api.sync.SyncTaskResultBridge;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.model.ProjectRef;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.sdk.SdkTaskMessageSnapshot;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskMessageQueryOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
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

    private final TaskMessageQueryOperations taskMessageQueries;
    private final TaskAdminOperations taskAdmin;
    private final SdkMetadataCatalog metadataCatalog;
    private final ApiAuthService apiAuthService;
    private final ApiAuthorizationService apiAuthorizationService;
    private final TaskSecurityViewSupport taskSecurityViewSupport;
    private final SyncTaskResultBridge syncBridge;

    @Autowired
    public InternalDebugTaskInvocationController(TaskMessageQueryOperations taskMessageQueries,
                                                 TaskAdminOperations taskAdmin,
                                                 SdkMetadataCatalog metadataCatalog,
                                                 ApiAuthService apiAuthService,
                                                 ApiAuthorizationService apiAuthorizationService,
                                                 TaskSecurityViewSupport taskSecurityViewSupport,
                                                 SyncTaskResultBridge syncBridge) {
        this.taskMessageQueries = taskMessageQueries;
        this.taskAdmin = taskAdmin;
        this.metadataCatalog = metadataCatalog == null ? DefaultProjectEventCatalogFactory.createDefaultProjectRegistry() : metadataCatalog;
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
            @RequestBody TaskCreateApiRequest requestBody) {
        if (syncBridge == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "Sync task API is not available in this runtime profile"));
        }
        try {
            validateKnownFields(requestBody, "sync task invocation");
            validateSyncRequest(requestBody);
            validateIngestGuardrails(requestBody.getInputs());

            long resolvedTimeoutMs = resolveTimeoutMs(timeoutMs);
            String correlationId = UUID.randomUUID().toString();
            CompletableFuture<com.xa.mass.engine.TaskMessageLogicallyFinalEvent> future = syncBridge.register(correlationId);

            ApiAuthorizationService.AuthorizedSubmitterTaskCreate submitterTaskCreate =
                    resolveSubmitterTaskCreate(apiKeyHeader, authorizationHeader, requestBody);
            Task task;
            if (submitterTaskCreate != null) {
                task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                        toMassTaskShellCreateRequest(requestBody, submitterTaskCreate.project(), submitterTaskCreate.userId(), correlationId),
                        submitterTaskCreate.principal()
                ));
            } else {
                PrincipalContext operator = apiAuthService.requireAuthenticated(httpRequest);
                task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                        toMassTaskShellCreateRequest(requestBody, requestBody.getProject(), requestBody.getUserId(), correlationId),
                        operator
                ));
            }

            taskAdmin.appendTaskItems(task.getTid(), MassTaskItemBatchAppendRequest.builder()
                    .items(requestBody.getInputs())
                    .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                    .build());
            taskAdmin.sealTask(task.getTid());
            taskAdmin.approveTask(task.getTid());

            String taskId = task.getTid();
            SdkTaskMessageSnapshot messageSnapshot = taskMessageQueries.getTaskMessageSnapshot(taskId, 1);
            String messageId = messageSnapshot.messages().isEmpty() ? "" : messageSnapshot.messages().get(0).messageId();
            Optional<com.xa.mass.engine.TaskMessageLogicallyFinalEvent> result =
                    syncBridge.await(correlationId, future, resolvedTimeoutMs);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", taskId);
            data.put("messageId", messageId);
            if (result.isPresent()) {
                com.xa.mass.engine.TaskMessageLogicallyFinalEvent msg = result.get();
                data.put("synced", true);
                data.put("timedOut", false);
                data.put("status", msg.status() != null ? msg.status().name() : "UNKNOWN");
                data.put("output", msg.output() != null ? msg.output() : Map.of());
                data.put("errorCode", msg.errorCode() != null ? msg.errorCode() : "");
                data.put("errorMessage", msg.errorMessage() != null ? msg.errorMessage() : "");
            } else {
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

    private void validateKnownFields(TaskCreateApiRequest requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateSyncRequest(TaskCreateApiRequest requestBody) {
        if (requestBody.getMode() != null && requestBody.getMode() != TaskMode.SINGLE_RUN) {
            throw new IllegalArgumentException("Sync task requires SINGLE_RUN mode, got: " + requestBody.getMode());
        }
        List<Object> inputs = requestBody.getInputs();
        if (inputs == null || inputs.size() != 1) {
            throw new IllegalArgumentException("Sync task requires exactly one input item");
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
                                                                                             TaskCreateApiRequest requestBody) {
        try {
            return apiAuthorizationService.resolveAuthorizedSubmitterTaskCreate(
                    apiKeyHeader,
                    authorizationHeader,
                    requestBody != null ? requestBody.getProject() : null,
                    requestBody != null ? requestBody.getEventCode() : null,
                    requestBody != null ? requestBody.getUserId() : null,
                    Map.of(
                            "taskName", requestBody != null ? String.valueOf(requestBody.getTaskName()) : "",
                            "mode", requestBody != null ? String.valueOf(requestBody.getMode()) : "",
                            "payloadType", requestBody != null ? String.valueOf(requestBody.getPayloadType()) : "",
                            "scenario", ApiSecurityScenario.SUBMITTER_TASK_CREATE.name()
                    )
            );
        } catch (com.xa.mass.api.auth.ApiUnauthenticatedException ex) {
            throw new SdkUnauthenticatedException(ex.getMessage());
        }
    }

    private void requireBusinessBindings(String project, String userId) {
        ProjectRef.require(project);
        UserRef.requireUserId(userId);
    }

    private MassTaskShellCreateRequest toMassTaskShellCreateRequest(TaskCreateApiRequest requestBody,
                                                                    String resolvedProject,
                                                                    String resolvedUserId,
                                                                    String syncKey) {
        requireBusinessBindings(resolvedProject, resolvedUserId);
        if (requestBody.getTaskName() == null || requestBody.getTaskName().isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        if (requestBody.getEventCode() != null && !requestBody.getEventCode().isBlank()) {
            validateProjectAndEvent(resolvedProject, requestBody.getEventCode());
        }
        return MassTaskShellCreateRequest.builder()
                .userId(resolvedUserId)
                .project(resolvedProject)
                .taskName(requestBody.getTaskName())
                .eventCode(requestBody.getEventCode())
                .mode(TaskMode.SINGLE_RUN)
                .payloadType(requestBody.getPayloadType() != null ? requestBody.getPayloadType() : com.xa.mass.sdk.catalog.PayloadType.JSON)
                .sharedConfig(mergeSyncKey(requestBody.getSharedConfig(), syncKey))
                .batchSize(requestBody.getBatchSize())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .sourceType(TaskSourceType.STREAM)
                .workloadClass(requestBody.getWorkloadClass())
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
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            throw new IllegalArgumentException("Unsupported project metadata code: " + projectCode);
        }
        if (metadataCatalog.getEvent(eventCode) == null) {
            throw new IllegalArgumentException("Unsupported event code: " + eventCode);
        }
        if (!projectMetadata.getEventCodes().contains(eventCode)) {
            throw new IllegalArgumentException("Project " + projectCode + " does not support event " + eventCode);
        }
    }

    private static final class SdkUnauthenticatedException extends RuntimeException {
        private SdkUnauthenticatedException(String message) {
            super(message);
        }
    }
}
