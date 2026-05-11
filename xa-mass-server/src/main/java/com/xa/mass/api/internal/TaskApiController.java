package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.TaskItemBatchIngestApiRequest;
import com.xa.mass.api.model.task.TaskShellCreateApiRequest;
import com.xa.mass.api.model.task.TaskUpdateApiRequest;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import com.xa.mass.sdk.model.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> EDITABLE_TASK_STATUSES = Set.of("NEW", "BLOCKED");
    private static final int MAX_INGEST_ITEM_COUNT = Integer.getInteger("xa.mass.api.maxIngestItemCount", 500);
    private static final int MAX_INGEST_ITEM_BYTES = Integer.getInteger("xa.mass.api.maxIngestItemBytes", 64 * 1024);
    private static final int MAX_INGEST_TOTAL_BYTES = Integer.getInteger("xa.mass.api.maxIngestTotalBytes", 1024 * 1024);
    private static final com.fasterxml.jackson.databind.ObjectMapper SIZE_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final TaskQueryOperations taskQueries;
    private final TaskAdminOperations taskAdmin;
    private final SdkMetadataCatalog metadataCatalog;
    private final ApiAuthService apiAuthService;
    private final ApiAuthorizationService apiAuthorizationService;
    private final TaskSecurityViewSupport taskSecurityViewSupport;

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin) {
        this(taskQueries, taskAdmin, DefaultProjectEventCatalogFactory.createDefaultProjectRegistry(),
                new ApiAuthService(), new ApiAuthorizationService(), new TaskSecurityViewSupport());
    }

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             SdkMetadataCatalog metadataCatalog) {
        this(taskQueries, taskAdmin, metadataCatalog, new ApiAuthService(), new ApiAuthorizationService(),
                new TaskSecurityViewSupport());
    }

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             SdkMetadataCatalog metadataCatalog,
                             com.xa.mass.sdk.auth.AuthProvider authProvider) {
        this(taskQueries, taskAdmin, metadataCatalog, new ApiAuthService(),
                new ApiAuthorizationService(authProvider, null), new TaskSecurityViewSupport());
    }

    @Autowired
    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             SdkMetadataCatalog metadataCatalog,
                             ApiAuthService apiAuthService,
                             ApiAuthorizationService apiAuthorizationService,
                             TaskSecurityViewSupport taskSecurityViewSupport) {
        this.taskQueries = taskQueries;
        this.taskAdmin = taskAdmin;
        this.metadataCatalog = metadataCatalog;
        this.apiAuthService = apiAuthService == null ? new ApiAuthService() : apiAuthService;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
        this.taskSecurityViewSupport = taskSecurityViewSupport == null ? new TaskSecurityViewSupport() : taskSecurityViewSupport;
    }

    @GetMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listTasks(
                                                                      @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                      @RequestParam(required = false) String keyword,
                                                                      @RequestParam(required = false) String status,
                                                                      @RequestParam(defaultValue = "0") int offset,
                                                                      @RequestParam(defaultValue = "500") int limit) {
        try {
            PrincipalContext submitterViewer = resolveTaskViewerCredential(apiKeyHeader, authorizationHeader);
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            // push status filter to storage when provided; otherwise use bounded page scan
            List<TaskSummarySnapshot> candidates = status != null
                    ? taskQueries.getTaskSummariesByStatus(status)
                    : taskQueries.listTaskSummaries(offset, Math.min(limit, 1000));
            List<Map<String, Object>> items = candidates.stream()
                    .filter(task -> canViewTaskSummary(task, submitterViewer))
                    .filter(task -> matchesKeyword(task.getTaskId(), task.getTaskName(), task.getProject(), normalizedKeyword))
                    .sorted(Comparator
                            .comparing(TaskSummarySnapshot::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(task -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", task.getTaskId());
                        item.put("taskName", task.getTaskName());
                        item.put("tenantId", task.getTenantId());
                        item.put("project", task.getProject());
                        item.put("userId", task.getUserId());
                        item.put("contract", task.getContract());
                        item.put("status", task.getStatus());
                        item.put("executionSpec", task.getExecutionSpec());
                        item.put("terminalReason", task.getTerminalReason());
                        item.put("successCount", task.getTaskSuccessNumber());
                        item.put("eligibleCount", task.getTaskEligibleNumber());
                        item.put("updatedAt", formatDateTime(task.getUpdateTime()));
                        return item;
                    })
                    .toList();
            return ok(Map.of("items", items, "total", items.size()));
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Task list failed: " + e.getMessage());
        }
    }

    @PostMapping("")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTask(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            HttpServletRequest httpRequest,
            @RequestBody TaskShellCreateApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task shell create");

            ApiAuthorizationService.AuthorizedSubmitterTaskCreate submitterTaskCreate =
                    resolveSubmitterTaskCreate(apiKeyHeader, authorizationHeader, requestBody);
            if (submitterTaskCreate != null) {
                TaskShellSnapshot task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                        toMassTaskShellCreateRequest(requestBody, submitterTaskCreate.project(), submitterTaskCreate.userId()),
                        submitterTaskCreate.principal()
                ));
                return ok(Map.of(
                        "taskId", task.getTaskId(),
                        "project", task.getProject(),
                        "userId", task.getUserId() != null ? task.getUserId() : submitterTaskCreate.userId(),
                        "principalId", submitterTaskCreate.principal().getPrincipalId(),
                        "message", "Task shell created"
                ));
            }

            PrincipalContext operator = apiAuthService.requireAuthenticated(httpRequest);
            TaskShellSnapshot task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                    toMassTaskShellCreateRequest(requestBody),
                    operator
            ));
            return ok(Map.of("taskId", task.getTaskId(), "message", "Task shell created"));
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Task shell create failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTask(
                                                                    @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                    @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                    @PathVariable String taskId) {
        try {
            TaskDetailSnapshot task = taskQueries.getTaskDetail(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            resolveTaskViewer(apiKeyHeader, authorizationHeader, task.getTaskId(), task.getProject(), task.getSharedConfig());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("task", toTaskDetailView(task));
            response.put("security", taskSecurityViewSupport.toSecurityView(task.getSharedConfig()));
            return ok(response);
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Task lookup failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:block")
    public ResponseEntity<ApiResponse<Map<String, Object>>> blockTask(@PathVariable String taskId) {
        try {
            if (taskAdmin.blockTask(taskId)) {
                return ok(Map.of("message", "Task blocked"));
            }
            TaskStateSnapshot state = taskQueries.getTaskState(taskId);
            if (state == null && !taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            if (state != null && "NEW".equals(state.getStatus()) && taskAdmin.rejectTask(taskId)) {
                return ok(Map.of("message", "Task blocked"));
            }
            return conflict("Task cannot be blocked from the current state");
        } catch (Exception e) {
            return badRequest("Task block failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveTask(@PathVariable String taskId) {
        try {
            boolean success = taskAdmin.approveTask(taskId);
            if (success) {
                TaskStateSnapshot updatedState = taskQueries.getTaskState(taskId);
                return ok(Map.of(
                        "message", "Task approved",
                        "newStatus", updatedState != null ? defaultString(updatedState.getStatus()) : ""
                ));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            return badRequest("Task cannot be approved from the current state");
        } catch (Exception e) {
            return badRequest("Task approve failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:reject")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rejectTask(@PathVariable String taskId) {
        try {
            boolean success = taskAdmin.rejectTask(taskId);
            if (success) {
                TaskStateSnapshot updatedState = taskQueries.getTaskState(taskId);
                return ok(Map.of(
                        "message", "Task rejected",
                        "newStatus", updatedState != null ? defaultString(updatedState.getStatus()) : ""
                ));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            return badRequest("Task cannot be rejected from the current state");
        } catch (Exception e) {
            return badRequest("Task reject failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:pause")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pauseTask(@PathVariable String taskId) {
        try {
            if (taskAdmin.pauseTask(taskId)) {
                return ok(Map.of("message", "Task paused"));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            return conflict("Task cannot be paused from the current state");
        } catch (Exception e) {
            return badRequest("Task pause failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:resume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resumeTask(@PathVariable String taskId) {
        try {
            SdkTaskResumeResult result = taskAdmin.resumeTaskDetailed(taskId);
            if (result.success()) {
                String message = result.completedToTerminal()
                        ? "Task already completed while paused and was closed to TERMINAL"
                        : "Task resumed";
                return ok(Map.of(
                        "message", message,
                        "newStatus", result.status(),
                        "terminalReason", result.terminalReason() != null ? result.terminalReason() : ""
                ));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            return conflict("Task cannot be resumed from the current state");
        } catch (Exception e) {
            return badRequest("Task resume failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:terminate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> terminateTask(@PathVariable String taskId) {
        try {
            if (taskAdmin.terminateTask(taskId, "MANUAL_CANCELLED")) {
                return ok(Map.of("message", "Task terminated"));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            return conflict("Task cannot be terminated from the current state");
        } catch (Exception e) {
            return badRequest("Task terminate failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTask(@PathVariable String taskId) {
        try {
            boolean deleted = taskAdmin.deleteTask(taskId);
            if (deleted) {
                return ok(Map.of("message", "Task deleted"));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            TaskStateSnapshot state = taskQueries.getTaskState(taskId);
            return badRequest("Task delete failed: current status "
                    + (state != null ? defaultString(state.getStatus()) : "UNKNOWN") + " cannot be deleted");
        } catch (Exception e) {
            return badRequest("Task delete failed: " + e.getMessage());
        }
    }

    @PatchMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTask(@PathVariable String taskId,
                                                                       @RequestBody TaskUpdateApiRequest requestBody) {
        try {
            TaskStateSnapshot state = taskQueries.getTaskState(taskId);
            if (state == null && !taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            String status = state == null ? null : state.getStatus();
            if (!EDITABLE_TASK_STATUSES.contains(status)) {
                return badRequest("Task update failed: Only NEW or BLOCKED tasks can be updated");
            }

            validateKnownFields(requestBody, "task update");
            MassTaskUpdateRequest request = toTaskUpdateRequest(requestBody);
            taskAdmin.updateTaskDefinition(taskId, request);
            return ok(Map.of("message", "Task updated"));
        } catch (Exception e) {
            return badRequest("Task update failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/items")
    public ResponseEntity<ApiResponse<Map<String, Object>>> appendTaskItems(
                                                                            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                            @PathVariable String taskId,
                                                                            @RequestBody TaskItemBatchIngestApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task append items");
            TaskAccessSnapshot task = taskQueries.getTaskAccess(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            List<Object> items = requestBody.getItems();
            if (items == null || items.isEmpty()) {
                return badRequest("items must be a non-empty list");
            }
            validateIngestGuardrails(items);
            List<String> eventCodes = resolveAppendEventCodes(requestBody, items);
            if (eventCodes.isEmpty()) {
                return badRequest("append requires batch eventCode or per-item eventCode");
            }
            for (String eventCode : eventCodes) {
                validateProjectAndEvent(task.getProject(), eventCode);
            }
            resolveTaskAppender(apiKeyHeader, authorizationHeader, task.getTaskId(), task.getProject(),
                    task.getSharedConfig(), eventCodes);
            int added = taskAdmin.appendTaskItems(taskId, MassTaskItemBatchAppendRequest.builder()
                    .eventCode(requestBody.getEventCode())
                    .items(items)
                    .build());
            return ok(Map.of("message", "Items appended", "added", added));
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            return badRequest("Append items failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}:seal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sealTask(@PathVariable String taskId) {
        try {
            boolean sealed = taskAdmin.sealTask(taskId);
            if (sealed) {
                TaskStateSnapshot state = taskQueries.getTaskState(taskId);
                return ok(Map.of("message", "Task sealed", "status", state != null ? defaultString(state.getStatus()) : ""));
            }
            if (!taskQueries.taskExists(taskId)) {
                return notFound("Task not found: " + taskId);
            }
            return conflict("Task intake is already sealed");
        } catch (Exception e) {
            return badRequest("Seal task failed: " + e.getMessage());
        }
    }

    private Map<String, Object> toTaskDetailView(TaskDetailSnapshot task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("tid", task.getTaskId());
        view.put("tenantId", task.getTenantId());
        view.put("taskName", task.getTaskName());
        view.put("contract", task.getContract());
        view.put("project", task.getProject());
        view.put("status", task.getStatus());
        view.put("taskTargetNumber", task.getTaskTargetNumber());
        view.put("taskEligibleNumber", task.getTaskEligibleNumber());
        view.put("taskSuccessNumber", task.getTaskSuccessNumber());
        view.put("taskNonSuccessNumber", task.getTaskNonSuccessNumber());
        view.put("minRequiredWorkerCount", task.getMinRequiredWorkerCount());
        view.put("peakAssignedWorkerCount", task.getPeakAssignedWorkerCount());
        view.put("sharedConfig", taskSecurityViewSupport.sanitizeSharedConfig(task.getSharedConfig()));
        view.put("holdReason", task.getHoldReason());
        view.put("executionSpec", task.getExecutionSpec());
        view.put("sourceRef", task.getSourceRef());
        view.put("intakeStatus", task.getIntakeStatus());
        view.put("userId", task.getUserId());
        view.put("createTime", task.getCreateTime());
        view.put("updateTime", task.getUpdateTime());
        view.put("startTime", task.getStartTime());
        view.put("endTime", task.getEndTime());
        view.put("terminalReason", task.getTerminalReason());
        return view;
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> ok(Map<String, ?> data) {
        return ResponseEntity.ok(ApiResponse.success(new LinkedHashMap<>(data)));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> conflict(String message) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> unauthorized(String message) {
        return ResponseEntity.status(401).body(ApiResponse.error(401, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> forbidden(String message) {
        return ResponseEntity.status(403).body(ApiResponse.error(403, message));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> notFound(String message) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, message));
    }

    private MassTaskShellCreateRequest toMassTaskShellCreateRequest(TaskShellCreateApiRequest requestBody) {
        return toMassTaskShellCreateRequest(requestBody, requestBody.getProject(), requestBody.getUserId());
    }

    private MassTaskShellCreateRequest toMassTaskShellCreateRequest(TaskShellCreateApiRequest requestBody,
                                                                    String resolvedProject,
                                                                    String resolvedUserId) {
        requireBusinessBindings(resolvedProject, resolvedUserId);
        return MassTaskShellCreateRequest.builder()
                .userId(resolvedUserId)
                .tenantId(resolveProjectTenantId(resolvedProject))
                .project(resolvedProject)
                .contract(requestBody.getContract())
                .sharedConfig(taskSecurityViewSupport.sanitizeSharedConfig(requestBody.getSharedConfig()))
                .executionSpec(TaskExecutionOptions.normalized(requestBody.getExecutionSpec()))
                .sourceRef(requestBody.getSourceRef())
                .build();
    }

    private MassTaskUpdateRequest toTaskUpdateRequest(TaskUpdateApiRequest requestBody) {
        if (requestBody.getProject() != null) {
            requireProjectCode(requestBody.getProject());
        }
        if (requestBody.getUserId() != null) {
            requireUserId(requestBody.getUserId());
        }
        return MassTaskUpdateRequest.builder()
                .userId(requestBody.getUserId())
                .project(requestBody.getProject())
                .sharedConfig(taskSecurityViewSupport.sanitizeSharedConfig(requestBody.getSharedConfig()))
                .build();
    }

    private boolean isEmptyCreateRequest(TaskShellCreateApiRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getContract() == null
                && requestBody.getSharedConfig() == null
                && requestBody.getExecutionSpec() == null
                && requestBody.getSourceRef() == null;
    }

    private boolean isEmptyUpdateRequest(TaskUpdateApiRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getSharedConfig() == null;
    }

    private boolean matchesKeyword(String taskId,
                                   String taskName,
                                   String project,
                                   String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsIgnoreCase(taskId, normalizedKeyword)
                || containsIgnoreCase(taskName, normalizedKeyword)
                || containsIgnoreCase(project, normalizedKeyword);
    }

    private boolean containsIgnoreCase(String source, String normalizedKeyword) {
        return source != null && source.toLowerCase().contains(normalizedKeyword);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private void requireBusinessBindings(String project, String userId) {
        requireProjectCode(project);
        requireUserId(userId);
    }

    private ApiAuthorizationService.AuthorizedSubmitterTaskCreate resolveSubmitterTaskCreate(String apiKeyHeader,
                                                                                             String authorizationHeader,
                                                                                             TaskShellCreateApiRequest requestBody) {
        try {
            return apiAuthorizationService.resolveAuthorizedSubmitterTaskCreate(
                    apiKeyHeader,
                    authorizationHeader,
                    requestBody != null ? requestBody.getProject() : null,
                    null,
                    requestBody != null ? requestBody.getUserId() : null,
                    Map.of(
                        "executionProfile", requestBody != null && requestBody.getExecutionSpec() != null
                                ? String.valueOf(requestBody.getExecutionSpec().getProfile()) : "",
                        "scenario", ApiSecurityScenario.SUBMITTER_TASK_CREATE.name()
                    )
            );
        } catch (com.xa.mass.api.auth.ApiUnauthenticatedException ex) {
            throw new SdkUnauthenticatedException(ex.getMessage());
        }
    }

    private PrincipalContext resolveTaskViewer(String apiKeyHeader,
                                               String authorizationHeader,
                                               String taskId,
                                               String project,
                                               Map<String, Object> sharedConfig) {
        try {
            return apiAuthorizationService.resolveAuthorizedTaskViewer(
                    apiKeyHeader,
                    authorizationHeader,
                    taskId,
                    project,
                    sharedConfig,
                    Map.of(
                            "taskId", taskId != null ? taskId : "",
                            "scenario", ApiSecurityScenario.SUBMITTER_TASK_VIEW.name()
                    )
            );
        } catch (com.xa.mass.api.auth.ApiForbiddenException ex) {
            throw new SecurityException(ex.getMessage());
        } catch (com.xa.mass.api.auth.ApiUnauthenticatedException ex) {
            throw new SdkUnauthenticatedException(ex.getMessage());
        }
    }

    private PrincipalContext resolveTaskViewerCredential(String apiKeyHeader,
                                                         String authorizationHeader) {
        try {
            return apiAuthorizationService.resolveTaskViewerCredential(
                    apiKeyHeader,
                    authorizationHeader,
                    Map.of("scenario", ApiSecurityScenario.SUBMITTER_TASK_VIEW.name())
            );
        } catch (com.xa.mass.api.auth.ApiUnauthenticatedException ex) {
            throw new SdkUnauthenticatedException(ex.getMessage());
        }
    }

    private boolean canViewTaskSummary(TaskSummarySnapshot task, PrincipalContext submitterViewer) {
        if (submitterViewer == null) {
            return true;
        }
        TaskAccessSnapshot access = taskQueries.getTaskAccess(task.getTaskId());
        if (access == null) {
            return false;
        }
        return apiAuthorizationService.allowsTaskOwnershipAccess(submitterViewer, access.getSharedConfig());
    }

    private PrincipalContext resolveTaskAppender(String apiKeyHeader,
                                                 String authorizationHeader,
                                                 String taskId,
                                                 String project,
                                                 Map<String, Object> sharedConfig,
                                                 List<String> eventCodes) {
        try {
            return apiAuthorizationService.resolveAuthorizedTaskAppender(
                    apiKeyHeader,
                    authorizationHeader,
                    taskId,
                    project,
                    sharedConfig,
                    eventCodes,
                    Map.of(
                            "taskId", taskId != null ? taskId : "",
                            "project", project != null ? project : "",
                            "eventCodes", eventCodes == null ? List.of() : eventCodes,
                            "scenario", ApiSecurityScenario.SUBMITTER_TASK_APPEND.name()
                    )
            );
        } catch (com.xa.mass.api.auth.ApiForbiddenException ex) {
            throw new SecurityException(ex.getMessage());
        } catch (com.xa.mass.api.auth.ApiUnauthenticatedException ex) {
            throw new SdkUnauthenticatedException(ex.getMessage());
        }
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

    private void validateKnownFields(TaskShellCreateApiRequest requestBody, String operationName) {
        if (requestBody == null || isEmptyCreateRequest(requestBody)) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateKnownFields(TaskUpdateApiRequest requestBody, String operationName) {
        if (requestBody == null || isEmptyUpdateRequest(requestBody)) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateKnownFields(TaskItemBatchIngestApiRequest requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private List<String> resolveAppendEventCodes(TaskItemBatchIngestApiRequest requestBody,
                                                 List<Object> items) {
        LinkedHashSet<String> eventCodes = new LinkedHashSet<>();
        String batchEventCode = normalizeEventCode(requestBody != null ? requestBody.getEventCode() : null);
        if (batchEventCode != null) {
            eventCodes.add(batchEventCode);
        }
        if (items != null) {
            for (Object item : items) {
                String itemEventCode = extractItemEventCode(item);
                if (itemEventCode != null) {
                    eventCodes.add(itemEventCode);
                }
            }
        }
        return eventCodes.isEmpty() ? List.of() : List.copyOf(eventCodes);
    }

    private String extractItemEventCode(Object item) {
        if (!(item instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Object rawEventCode = rawMap.get("eventCode");
        if (rawEventCode == null) {
            return null;
        }
        return normalizeEventCode(String.valueOf(rawEventCode));
    }

    private String normalizeEventCode(String eventCode) {
        if (eventCode == null || eventCode.isBlank()) {
            return null;
        }
        return eventCode.trim();
    }

    private void validateProjectAndEvent(String projectCode, String eventCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            throw new IllegalArgumentException("Unsupported project metadata code: " + projectCode);
        }
        if (metadataCatalog.getEvent(eventCode) == null) {
            throw new IllegalArgumentException("Unsupported event code: " + eventCode);
        }
        if (!projectMetadata.getAuthorizedEventCodes().contains(eventCode)) {
            throw new IllegalArgumentException("Project " + projectCode + " does not support event " + eventCode);
        }
    }

    private String resolveProjectTenantId(String projectCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            throw new IllegalArgumentException("Unsupported project metadata code: " + projectCode);
        }
        return projectMetadata.getTenantId();
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
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("item cannot be serialized as JSON: " + e.getMessage(), e);
            }
        }
        if (totalBytes > MAX_INGEST_TOTAL_BYTES) {
            throw new IllegalArgumentException("items exceed total size limit: "
                    + totalBytes + " > " + MAX_INGEST_TOTAL_BYTES);
        }
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }

    private static final class SdkUnauthenticatedException extends RuntimeException {
        private SdkUnauthenticatedException(String message) {
            super(message);
        }
    }
}
