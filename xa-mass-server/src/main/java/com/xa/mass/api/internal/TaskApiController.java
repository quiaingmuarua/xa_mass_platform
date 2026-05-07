package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.TaskAppendItemsApiRequest;
import com.xa.mass.api.sync.SyncTaskResultBridge;
import com.xa.mass.api.model.task.TaskCreateApiRequest;
import com.xa.mass.api.model.task.TaskUpdateApiRequest;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.ProjectRef;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.sdk.SdkTaskResumeResult;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.catalog.*;
import com.xa.mass.sdk.model.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/status/api/tasks")
public class TaskApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<TaskStatus> EDITABLE_TASK_STATUSES = Set.of(TaskStatus.NEW, TaskStatus.BLOCKED);
    private static final int DEFAULT_TASK_MESSAGE_SNAPSHOT_LIMIT = 100;
    private static final int MAX_TASK_MESSAGE_SNAPSHOT_LIMIT = 500;

    private final TaskQueryOperations taskQueries;
    private final TaskAdminOperations taskAdmin;
    private final SdkMetadataCatalog metadataCatalog;
    private final ApiAuthService apiAuthService;
    private final ApiAuthorizationService apiAuthorizationService;
    private final TaskSecurityViewSupport taskSecurityViewSupport;

    @Autowired(required = false)
    private SyncTaskResultBridge syncBridge;

    public TaskApiController(TaskQueryOperations taskQueries, TaskAdminOperations taskAdmin) {
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
                                                                      @RequestParam(required = false) TaskStatus status,
                                                                      @RequestParam(defaultValue = "0") int offset,
                                                                      @RequestParam(defaultValue = "500") int limit) {
        try {
            PrincipalContext submitterViewer = resolveTaskViewerCredential(apiKeyHeader, authorizationHeader);
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            // push status filter to storage when provided; otherwise use bounded page scan
            List<Task> candidates = status != null
                    ? taskQueries.getTasksByStatus(status)
                    : taskQueries.listTasksPaged(offset, Math.min(limit, 1000));
            List<Map<String, Object>> items = candidates.stream()
                    .filter(task -> submitterViewer == null || apiAuthorizationService.allowsTaskOwnershipAccess(submitterViewer, task))
                    .filter(task -> matchesKeyword(task, normalizedKeyword))
                    .sorted(Comparator
                            .comparing(Task::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(Task::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(this::toTaskListItem)
                    .collect(Collectors.toList());
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
            @RequestBody TaskCreateApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task create");

            ApiAuthorizationService.AuthorizedSubmitterTaskCreate submitterTaskCreate =
                    resolveSubmitterTaskCreate(apiKeyHeader, authorizationHeader, requestBody);
            if (submitterTaskCreate != null) {
                Task task = taskAdmin.createTask(TaskOwnershipSupport.stamp(
                        toMassTaskRequest(requestBody, submitterTaskCreate.project(), submitterTaskCreate.userId()),
                        submitterTaskCreate.principal()
                ));
                return ok(Map.of(
                        "taskId", task.getTid(),
                        "project", task.getProject(),
                        "userId", task.getUser() != null ? task.getUser().getUserId() : submitterTaskCreate.userId(),
                        "principalId", submitterTaskCreate.principal().getPrincipalId(),
                        "message", "Task created"
                ));
            }

            PrincipalContext operator = apiAuthService.requireAuthenticated(httpRequest);
            Task task = hasEventCode(requestBody)
                    ? taskAdmin.createTask(TaskOwnershipSupport.stamp(toMassTaskRequest(requestBody), operator))
                    : taskAdmin.createTask(TaskOwnershipSupport.stamp(toTaskCreateRequest(requestBody), operator));
            return ok(Map.of("taskId", task.getTid(), "message", "Task created"));
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Task create failed: " + e.getMessage());
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createTaskSync(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestParam(required = false) Long timeoutMs,
            HttpServletRequest httpRequest,
            @RequestBody TaskCreateApiRequest requestBody) {
        if (syncBridge == null) {
            return badRequest("Sync task API is not available in this runtime profile");
        }
        try {
            validateKnownFields(requestBody, "sync task create");
            validateSyncRequest(requestBody);

            long resolvedTimeoutMs = resolveTimeoutMs(timeoutMs);
            String correlationId = UUID.randomUUID().toString();

            // Register future BEFORE task creation to close the timing gap.
            CompletableFuture<com.xa.mass.engine.TaskMessageLogicallyFinalEvent> future =
                    syncBridge.register(correlationId);

            ApiAuthorizationService.AuthorizedSubmitterTaskCreate submitterTaskCreate =
                    resolveSubmitterTaskCreate(apiKeyHeader, authorizationHeader, requestBody);
            Task task;
            if (submitterTaskCreate != null) {
                task = taskAdmin.createTask(TaskOwnershipSupport.stamp(
                        toMassTaskRequestWithSyncKey(
                                requestBody,
                                submitterTaskCreate.project(),
                                submitterTaskCreate.userId(),
                                correlationId
                        ),
                        submitterTaskCreate.principal()
                ));
            } else {
                PrincipalContext operator = apiAuthService.requireAuthenticated(httpRequest);
                task = hasEventCode(requestBody)
                        ? taskAdmin.createTask(TaskOwnershipSupport.stamp(
                                toMassTaskRequestWithSyncKey(requestBody, requestBody.getProject(), requestBody.getUserId(), correlationId),
                                operator
                        ))
                        : taskAdmin.createTask(TaskOwnershipSupport.stamp(
                                toTaskCreateRequestWithSyncKey(requestBody, correlationId),
                                operator
                        ));
            }

            String taskId = task.getTid();
            TaskMessageSnapshot messageSnapshot = taskQueries.getTaskMessageSnapshot(taskId, 1);
            String messageId = messageSnapshot.messages().isEmpty() ? "" : messageSnapshot.messages().get(0).getMessageId();

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
            return ok(data);
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Sync task create failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTask(
                                                                    @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                    @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                    @PathVariable String taskId,
                                                                    @RequestParam(required = false) Integer limit) {
        try {
            int boundedLimit = resolveTaskMessageLimit(limit);
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            resolveTaskViewer(apiKeyHeader, authorizationHeader, task);
            TaskMessageSnapshot taskItemSnapshot = taskQueries.getTaskMessageSnapshot(taskId, boundedLimit);
            List<Map<String, Object>> items = taskItemSnapshot.messages().stream()
                    .map(TaskMsg::getInput)
                    .map(input -> input == null ? Map.<String, Object>of() : new LinkedHashMap<>(input))
                    .collect(Collectors.toList());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("task", toTaskDetailTaskView(task));
            response.put("items", items);
            response.put("itemsLimit", boundedLimit);
            response.put("itemsReturned", items.size());
            response.put("itemsTruncated", taskItemSnapshot.truncated());
            response.put("security", taskSecurityViewSupport.toSecurityView(task));
            response.put("stateValidation", taskQueries.validateTaskState(taskId));
            return ok(response);
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Task lookup failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTaskStatus(@PathVariable String taskId,
                                                                             @RequestParam TaskStatus status) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }

            boolean success = switch (status) {
                case READY -> task.getStatus() == TaskStatus.PAUSED
                        ? taskAdmin.resumeTaskDetailed(taskId).success()
                        : taskAdmin.approveTask(taskId);
                case BLOCKED -> blockTask(task);
                case PAUSED -> taskAdmin.pauseTask(taskId);
                case TERMINAL -> taskAdmin.terminateTask(taskId, TaskTerminalReason.MANUAL_CANCELLED);
                default -> false;
            };

            Task updatedTask = taskQueries.getTask(taskId);
            if (success && updatedTask != null) {
                return ok(Map.of("message", "Task status updated", "newStatus", updatedTask.getStatus().name()));
            }
            return conflict("Task cannot transition to status " + status.name());
        } catch (Exception e) {
            return badRequest("Task status update failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/block")
    public ResponseEntity<ApiResponse<Map<String, Object>>> blockTask(@PathVariable String taskId) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (blockTask(task)) {
                return ok(Map.of("message", "Task blocked"));
            }
            return conflict("Task cannot be blocked from the current state");
        } catch (Exception e) {
            return badRequest("Task block failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/audit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> auditTask(@PathVariable String taskId,
                                                                      @RequestParam String approved,
                                                                      @RequestParam(required = false) String comment) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            boolean isApproved = "true".equalsIgnoreCase(approved);
            boolean success = isApproved ? taskAdmin.approveTask(taskId) : taskAdmin.rejectTask(taskId);
            Task updatedTask = taskQueries.getTask(taskId);
            if (success && updatedTask != null) {
                return ok(Map.of(
                        "message", isApproved ? "Task approved" : "Task rejected",
                        "newStatus", updatedTask.getStatus().name()
                ));
            }
            return badRequest("Task cannot be audited from the current state");
        } catch (Exception e) {
            return badRequest("Task audit failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pauseTask(@PathVariable String taskId) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (taskAdmin.pauseTask(taskId)) {
                return ok(Map.of("message", "Task paused"));
            }
            return conflict("Task cannot be paused from the current state");
        } catch (Exception e) {
            return badRequest("Task pause failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resumeTask(@PathVariable String taskId) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
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
            return conflict("Task cannot be resumed from the current state");
        } catch (Exception e) {
            return badRequest("Task resume failed: " + e.getMessage());
        }
    }

    @PostMapping("/{taskId}/terminate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> terminateTask(@PathVariable String taskId) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (taskAdmin.terminateTask(taskId, TaskTerminalReason.MANUAL_CANCELLED)) {
                return ok(Map.of("message", "Task terminated"));
            }
            return conflict("Task cannot be terminated from the current state");
        } catch (Exception e) {
            return badRequest("Task terminate failed: " + e.getMessage());
        }
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteTask(@PathVariable String taskId) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            boolean deleted = taskAdmin.deleteTask(taskId);
            if (deleted) {
                return ok(Map.of("message", "Task deleted"));
            }
            return badRequest("Task delete failed: current status " + task.getStatus().name() + " cannot be deleted");
        } catch (Exception e) {
            return badRequest("Task delete failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTask(@PathVariable String taskId,
                                                                       @RequestBody TaskUpdateApiRequest requestBody) {
        try {
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            if (!EDITABLE_TASK_STATUSES.contains(task.getStatus())) {
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
    public ResponseEntity<ApiResponse<Map<String, Object>>> appendTaskItems(@PathVariable String taskId,
                                                                            @RequestBody TaskAppendItemsApiRequest requestBody) {
        try {
            validateKnownFields(requestBody, "task append items");
            Task task = taskQueries.getTask(taskId);
            if (task == null) {
                return notFound("Task not found: " + taskId);
            }
            List<Object> inputs = requestBody.getInputs();
            if (inputs == null || inputs.isEmpty()) {
                return badRequest("inputs must be a non-empty list");
            }
            int added = taskAdmin.appendTaskItems(taskId, toAppendInputs(inputs, task));
            return ok(Map.of("message", "Items appended", "added", added));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            return badRequest("Append items failed: " + e.getMessage());
        }
    }

    @PutMapping("/{taskId}/seal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sealTask(@PathVariable String taskId) {
        try {
            boolean sealed = taskAdmin.sealTask(taskId);
            if (sealed) {
                Task task = taskQueries.getTask(taskId);
                return ok(Map.of("message", "Task sealed", "status", task != null ? task.getStatus().name() : ""));
            }
            return conflict("Task not found or not open-ended");
        } catch (Exception e) {
            return badRequest("Seal task failed: " + e.getMessage());
        }
    }

    @GetMapping("/{taskId}/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaskMessages(
                                                                            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                            @PathVariable String taskId,
                                                                            @RequestParam(required = false) Integer limit) {
        try {
            if (SdkCredentialAuthSupport.hasCredentialAttempt(apiKeyHeader, authorizationHeader)) {
                Task task = taskQueries.getTask(taskId);
                if (task == null) {
                    return notFound("Task not found: " + taskId);
                }
                resolveTaskViewer(apiKeyHeader, authorizationHeader, task);
            }
            int boundedLimit = resolveTaskMessageLimit(limit);
            TaskMessageSnapshot messageSnapshot = taskQueries.getTaskMessageSnapshot(taskId, boundedLimit);
            List<Map<String, Object>> messages = messageSnapshot.messages().stream()
                    .map(this::toTaskMessageSummaryView)
                    .collect(Collectors.toList());
            return ok(Map.of(
                    "limit", messageSnapshot.limit(),
                    "returned", messageSnapshot.returned(),
                    "truncated", messageSnapshot.truncated(),
                    "messages", messages
            ));
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest("Task message lookup failed: " + e.getMessage());
        }
    }

    private int resolveTaskMessageLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_TASK_MESSAGE_SNAPSHOT_LIMIT;
        }
        if (requestedLimit <= 0) {
            throw new IllegalArgumentException("limit must be greater than 0");
        }
        return Math.min(requestedLimit, MAX_TASK_MESSAGE_SNAPSHOT_LIMIT);
    }

    private Map<String, Object> toTaskMessageSummaryView(TaskMsg taskMsg) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("messageId", taskMsg.getMessageId());
        view.put("taskId", taskMsg.getTaskId());
        view.put("status", taskMsg.getStatus() != null ? taskMsg.getStatus().name() : null);
        view.put("latestAttemptId", taskMsg.latestAttemptId());
        view.put("latestAttemptWorkerId", taskMsg.getLatestAttemptWorkerId());
        view.put("latestAttemptWorkerContextId", taskMsg.getLatestAttemptWorkerContextId());
        view.put("latestAttemptBatchId", taskMsg.getLatestAttemptBatchId());
        view.put("retryCount", taskMsg.getRetryCount());
        view.put("maxRetryCount", taskMsg.getMaxRetryCount());
        view.put("errorMessage", taskMsg.getErrorMessage());
        view.put("errorCode", taskMsg.getErrorCode());
        view.put("finalReason", taskMsg.getFinalReason() != null ? taskMsg.getFinalReason().name() : null);
        view.put("assignedTime", taskMsg.getAssignedTime());
        view.put("createTime", taskMsg.getCreateTime());
        view.put("updateTime", taskMsg.getUpdateTime());
        view.put("startTime", taskMsg.getStartTime());
        view.put("completeTime", taskMsg.getCompleteTime());
        return view;
    }

    private Map<String, Object> toTaskListItem(Task task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.getTid());
        item.put("taskName", task.getTaskName());
        item.put("project", task.getProject());
        item.put("userId", task.getUser() != null ? task.getUser().getUserId() : null);
        item.put("status", task.getStatus() != null ? task.getStatus().name() : null);
        item.put("workloadClass", task.getWorkloadClass() != null ? task.getWorkloadClass().name() : null);
        item.put("terminalReason", task.getTerminalReason() != null ? task.getTerminalReason().name() : null);
        item.put("successCount", task.getTaskSuccessNumber());
        item.put("eligibleCount", task.getTaskEligibleNumber());
        item.put("batchSize", task.getBatchSize());
        item.put("updatedAt", formatDateTime(task.getUpdateTime()));
        item.put("security", taskSecurityViewSupport.toSecurityView(task));
        return item;
    }

    private Task toTaskDetailTaskView(Task task) {
        Task view = new Task();
        BeanUtils.copyProperties(task, view);
        view.setSharedConfig(taskSecurityViewSupport.sanitizeSharedConfig(task.getSharedConfig()));
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

    private void validateKnownFields(TaskCreateApiRequest requestBody, String operationName) {
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

    private void validateKnownFields(TaskAppendItemsApiRequest requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported " + operationName + " fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private MassTaskCreateRequest toTaskCreateRequest(TaskCreateApiRequest requestBody) {
        requireBusinessBindings(requestBody.getProject(), requestBody.getUserId());
        return MassTaskCreateRequest.builder()
                .userId(requestBody.getUserId())
                .project(requestBody.getProject())
                .taskName(requestBody.getTaskName())
                .sharedConfig(taskSecurityViewSupport.sanitizeSharedConfig(requestBody.getSharedConfig()))
                .inputs(toPlainJsonInputs(requestBody.getInputs()))
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .openEnded(requestBody.isOpenEnded())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .sourceType(resolveSourceType(requestBody))
                .workloadClass(requestBody.getWorkloadClass())
                .sourceRef(requestBody.getSourceRef())
                .build();
    }

    private MassTaskRequest toMassTaskRequest(TaskCreateApiRequest requestBody) {
        return toMassTaskRequest(requestBody, requestBody.getProject(), requestBody.getUserId());
    }

    private MassTaskRequest toMassTaskRequest(TaskCreateApiRequest requestBody, String resolvedProject, String resolvedUserId) {
        requireBusinessBindings(resolvedProject, resolvedUserId);
        if (requestBody.getTaskName() == null || requestBody.getTaskName().isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        if (requestBody.getEventCode() == null || requestBody.getEventCode().isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }
        validateProjectAndEvent(resolvedProject, requestBody.getEventCode());

        TaskMode mode = requestBody.getMode() != null
                ? requestBody.getMode()
                : (requestBody.isOpenEnded() ? TaskMode.STREAMING : TaskMode.SINGLE_RUN);
        PayloadType payloadType = requestBody.getPayloadType() != null
                ? requestBody.getPayloadType()
                : PayloadType.JSON;

        return MassTaskRequest.builder()
                .userId(resolvedUserId)
                .project(resolvedProject)
                .taskName(requestBody.getTaskName())
                .eventCode(requestBody.getEventCode())
                .mode(mode)
                .payloadType(payloadType)
                .sharedConfig(taskSecurityViewSupport.sanitizeSharedConfig(requestBody.getSharedConfig()))
                .inputs(toMassInputs(requestBody.getInputs(), payloadType, resolveSourceType(requestBody)))
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .sourceType(resolveSourceType(requestBody))
                .workloadClass(requestBody.getWorkloadClass())
                .sourceRef(requestBody.getSourceRef())
                .build();
    }

    private MassTaskUpdateRequest toTaskUpdateRequest(TaskUpdateApiRequest requestBody) {
        if (requestBody.getProject() != null) {
            ProjectRef.require(requestBody.getProject());
        }
        if (requestBody.getUserId() != null) {
            UserRef.requireUserId(requestBody.getUserId());
        }
        return MassTaskUpdateRequest.builder()
                .userId(requestBody.getUserId())
                .project(requestBody.getProject())
                .taskName(requestBody.getTaskName())
                .sharedConfig(taskSecurityViewSupport.sanitizeSharedConfig(requestBody.getSharedConfig()))
                .batchSize(requestBody.getBatchSize())
                .build();
    }

    private boolean isEmptyCreateRequest(TaskCreateApiRequest requestBody) {
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
                && !requestBody.isOpenEnded()
                && requestBody.getMaxRuntimeSeconds() == 0
                && requestBody.getSourceType() == null
                && requestBody.getWorkloadClass() == null
                && requestBody.getSourceRef() == null;
    }

    private boolean isEmptyUpdateRequest(TaskUpdateApiRequest requestBody) {
        return requestBody.getUserId() == null
                && requestBody.getProject() == null
                && requestBody.getTaskName() == null
                && requestBody.getSharedConfig() == null
                && requestBody.getBatchSize() == null;
    }

    private boolean blockTask(Task task) {
        if (task == null) {
            return false;
        }
        if (task.getStatus() == TaskStatus.NEW) {
            return taskAdmin.rejectTask(task.getTid());
        }
        return taskAdmin.blockTask(task.getTid());
    }

    private boolean matchesKeyword(Task task, String normalizedKeyword) {
        if (normalizedKeyword == null || normalizedKeyword.isBlank()) {
            return true;
        }
        return containsIgnoreCase(task.getTid(), normalizedKeyword)
                || containsIgnoreCase(task.getTaskName(), normalizedKeyword)
                || containsIgnoreCase(task.getProject(), normalizedKeyword);
    }

    private boolean containsIgnoreCase(String source, String normalizedKeyword) {
        return source != null && source.toLowerCase().contains(normalizedKeyword);
    }

    private void requireBusinessBindings(String project, String userId) {
        ProjectRef.require(project);
        UserRef.requireUserId(userId);
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

    private PrincipalContext resolveTaskViewer(String apiKeyHeader,
                                               String authorizationHeader,
                                               Task task) {
        try {
            return apiAuthorizationService.resolveAuthorizedTaskViewer(
                    apiKeyHeader,
                    authorizationHeader,
                    task,
                    Map.of(
                            "taskId", task != null ? String.valueOf(task.getTid()) : "",
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

    private boolean hasEventCode(TaskCreateApiRequest requestBody) {
        return requestBody.getEventCode() != null && !requestBody.getEventCode().isBlank();
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

    private List<Map<String, Object>> toPlainJsonInputs(List<Object> rawInputs) {
        if (rawInputs == null) {
            return null;
        }
        return rawInputs.stream()
                .map(this::mapInputWithoutDeclaredPayloadType)
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> toAppendInputs(List<Object> rawInputs, Task task) {
        PayloadType payloadType = resolvePayloadType(task);
        if (payloadType == null) {
            return rawInputs.stream()
                    .map(this::mapInputWithoutDeclaredPayloadType)
                    .collect(Collectors.toList());
        }
        return toMassInputs(rawInputs, payloadType, task.getSourceType()).stream()
                .map(MassInput::toTaskMsgInput)
                .collect(Collectors.toList());
    }

    private Map<String, Object> mapInputWithoutDeclaredPayloadType(Object rawInput) {
        if (rawInput instanceof String text) {
            return new TextInput(text).toTaskMsgInput();
        }
        if (rawInput instanceof Map<?, ?> map) {
            return stringObjectMap(map);
        }
        throw new IllegalArgumentException("Unsupported input item type: " + rawInput);
    }

    private List<MassInput> toMassInputs(List<Object> rawInputs, PayloadType payloadType, TaskSourceType sourceType) {
        if ((rawInputs == null || rawInputs.isEmpty())
                && sourceType != null
                && sourceType.allowsEmptyInitialInputs()) {
            return List.of();
        }
        if (rawInputs == null || rawInputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must contain at least one work item");
        }
        PayloadType resolvedPayloadType = payloadType != null ? payloadType : PayloadType.JSON;
        return rawInputs.stream()
                .map(rawInput -> toMassInput(rawInput, resolvedPayloadType))
                .collect(Collectors.toList());
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

    private Map<String, Object> stringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(copy);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }

    private TaskSourceType resolveSourceType(TaskCreateApiRequest requestBody) {
        if (requestBody.getSourceType() != null) {
            return requestBody.getSourceType();
        }
        return requestBody.isOpenEnded() ? TaskSourceType.STREAM : TaskSourceType.BATCH;
    }

    private void validateSyncRequest(TaskCreateApiRequest requestBody) {
        if (requestBody.isOpenEnded()) {
            throw new IllegalArgumentException("Sync task does not support open-ended mode");
        }
        if (requestBody.getMode() != null && requestBody.getMode() != TaskMode.SINGLE_RUN) {
            throw new IllegalArgumentException("Sync task requires SINGLE_RUN mode, got: " + requestBody.getMode());
        }
        List<Object> inputs = requestBody.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Sync task requires exactly one input item");
        }
        if (inputs.size() > 1) {
            throw new IllegalArgumentException("Sync task requires exactly one input item; got " + inputs.size());
        }
    }

    private long resolveTimeoutMs(Long requested) {
        if (requested == null || requested <= 0) {
            return SyncTaskResultBridge.DEFAULT_TIMEOUT_MS;
        }
        return Math.min(requested, SyncTaskResultBridge.MAX_TIMEOUT_MS);
    }

    private Map<String, Object> mergeSyncKey(Map<String, Object> existing, String syncKey) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(taskSecurityViewSupport.sanitizeSharedConfig(existing));
        }
        merged.put(SyncTaskResultBridge.SYNC_KEY, syncKey);
        return Map.copyOf(merged);
    }

    private MassTaskRequest toMassTaskRequestWithSyncKey(TaskCreateApiRequest requestBody,
                                                         String resolvedProject,
                                                         String resolvedUserId,
                                                         String syncKey) {
        requireBusinessBindings(resolvedProject, resolvedUserId);
        if (requestBody.getTaskName() == null || requestBody.getTaskName().isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }
        if (requestBody.getEventCode() == null || requestBody.getEventCode().isBlank()) {
            throw new IllegalArgumentException("eventCode is required");
        }
        validateProjectAndEvent(resolvedProject, requestBody.getEventCode());
        PayloadType payloadType = requestBody.getPayloadType() != null ? requestBody.getPayloadType() : PayloadType.JSON;
        return MassTaskRequest.builder()
                .userId(resolvedUserId)
                .project(resolvedProject)
                .taskName(requestBody.getTaskName())
                .eventCode(requestBody.getEventCode())
                .mode(TaskMode.SINGLE_RUN)
                .payloadType(payloadType)
                .sharedConfig(mergeSyncKey(requestBody.getSharedConfig(), syncKey))
                .inputs(toMassInputs(requestBody.getInputs(), payloadType, TaskSourceType.BATCH))
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .sourceType(TaskSourceType.BATCH)
                .workloadClass(requestBody.getWorkloadClass())
                .sourceRef(requestBody.getSourceRef())
                .build();
    }

    private MassTaskCreateRequest toTaskCreateRequestWithSyncKey(TaskCreateApiRequest requestBody, String syncKey) {
        requireBusinessBindings(requestBody.getProject(), requestBody.getUserId());
        return MassTaskCreateRequest.builder()
                .userId(requestBody.getUserId())
                .project(requestBody.getProject())
                .taskName(requestBody.getTaskName())
                .sharedConfig(mergeSyncKey(requestBody.getSharedConfig(), syncKey))
                .inputs(toPlainJsonInputs(requestBody.getInputs()))
                .batchSize(requestBody.getBatchSize())
                .defaultMsgMaxRetryCount(requestBody.getDefaultMsgMaxRetryCount())
                .openEnded(false)
                .maxRuntimeSeconds(requestBody.getMaxRuntimeSeconds())
                .sourceType(TaskSourceType.BATCH)
                .workloadClass(requestBody.getWorkloadClass())
                .sourceRef(requestBody.getSourceRef())
                .build();
    }

    private static final class SdkUnauthenticatedException extends RuntimeException {
        private SdkUnauthenticatedException(String message) {
            super(message);
        }
    }
}
