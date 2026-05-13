package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTask;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskAppendOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskCommandOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskCreateOutcome;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskGetResult;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskListResult;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskResultArchive;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskResultItem;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskResultWindow;
import com.xa.mass.api.model.task.TaskApiContracts.ApiTaskUpdateOutcome;
import com.xa.mass.api.model.task.TaskCommandApiRequest;
import com.xa.mass.api.model.task.TaskItemBatchIngestApiRequest;
import com.xa.mass.api.model.task.TaskShellCreateApiRequest;
import com.xa.mass.api.model.task.TaskUpdateApiRequest;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.TaskQueryOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.model.*;
import com.xa.mass.storage.api.TaskDetailStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Task API", description = "Public task shell, item ingest, command, and result APIs")
public class TaskApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> EDITABLE_TASK_STATUSES = Set.of("NEW", "BLOCKED");
    private static final int MAX_INGEST_ITEM_COUNT = Integer.getInteger("xa.mass.api.maxIngestItemCount", 500);
    private static final int MAX_INGEST_ITEM_BYTES = Integer.getInteger("xa.mass.api.maxIngestItemBytes", 64 * 1024);
    private static final int MAX_INGEST_TOTAL_BYTES = Integer.getInteger("xa.mass.api.maxIngestTotalBytes", 1024 * 1024);
    private static final int DEFAULT_RESULT_WINDOW = Integer.getInteger("xa.mass.api.taskResultDefaultWindow", 200);
    private static final int MAX_RESULT_WINDOW = Integer.getInteger("xa.mass.api.taskResultMaxWindow", 1000);
    private static final MediaType NDJSON_MEDIA_TYPE = MediaType.parseMediaType("application/x-ndjson");
    private static final com.fasterxml.jackson.databind.ObjectMapper SIZE_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private static final com.fasterxml.jackson.databind.ObjectMapper RESPONSE_OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final TaskQueryOperations taskQueries;
    private final TaskAdminOperations taskAdmin;
    private final ControlPlaneCatalog catalog;
    private final TaskDetailStore taskDetailStore;
    private final ApiAuthService apiAuthService;
    private final ApiAuthorizationService apiAuthorizationService;
    private final TaskSecurityViewSupport taskSecurityViewSupport;
    private final TaskApiContractAssembler taskApiContractAssembler;

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin) {
        this(taskQueries, taskAdmin, DefaultProjectEventCatalogFactory.createDefaultProjectRegistry(), null,
                new ApiAuthService(), new ApiAuthorizationService(), new TaskSecurityViewSupport());
    }

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             ControlPlaneCatalog catalog) {
        this(taskQueries, taskAdmin, catalog, null, new ApiAuthService(), new ApiAuthorizationService(),
                new TaskSecurityViewSupport());
    }

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             ControlPlaneCatalog catalog,
                             TaskDetailStore taskDetailStore,
                             com.xa.mass.sdk.auth.AuthProvider authProvider) {
        this(taskQueries, taskAdmin, catalog, taskDetailStore, new ApiAuthService(),
                new ApiAuthorizationService(authProvider, null), new TaskSecurityViewSupport());
    }

    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             ControlPlaneCatalog catalog,
                             com.xa.mass.sdk.auth.AuthProvider authProvider) {
        this(taskQueries, taskAdmin, catalog, null, new ApiAuthService(),
                new ApiAuthorizationService(authProvider, null), new TaskSecurityViewSupport());
    }

    @Autowired
    public TaskApiController(TaskQueryOperations taskQueries,
                             TaskAdminOperations taskAdmin,
                             ControlPlaneCatalog catalog,
                             TaskDetailStore taskDetailStore,
                             ApiAuthService apiAuthService,
                             ApiAuthorizationService apiAuthorizationService,
                             TaskSecurityViewSupport taskSecurityViewSupport) {
        this.taskQueries = taskQueries;
        this.taskAdmin = taskAdmin;
        this.catalog = catalog;
        this.taskDetailStore = taskDetailStore;
        this.apiAuthService = apiAuthService == null ? new ApiAuthService() : apiAuthService;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
        this.taskSecurityViewSupport = taskSecurityViewSupport == null ? new TaskSecurityViewSupport() : taskSecurityViewSupport;
        this.taskApiContractAssembler = new TaskApiContractAssembler(DATE_TIME_FORMATTER);
    }

    @GetMapping("")
    @Operation(
            summary = "List tasks",
            description = "Returns a bounded list of task shell summaries. Project filtering is task-level; item eventCode is not task truth."
    )
    public ResponseEntity<ApiResponse<ApiTaskListResult>> listTasks(
                                                                      @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                      @Parameter(description = "Optional keyword matched against task id, task name, or project")
                                                                      @RequestParam(required = false) String keyword,
                                                                      @Parameter(description = "Optional exact project code filter")
                                                                      @RequestParam(required = false) String project,
                                                                      @Parameter(description = "Optional task status filter")
                                                                      @RequestParam(required = false) String status,
                                                                      @Parameter(description = "Storage scan offset when status is not supplied")
                                                                      @RequestParam(defaultValue = "0") int offset,
                                                                      @Parameter(description = "Bounded list window size")
                                                                      @RequestParam(defaultValue = "500") int limit) {
        return executeApi("Task list failed", () -> {
            PrincipalContext submitterViewer = resolveTaskViewerCredential(apiKeyHeader, authorizationHeader);
            String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase();
            String normalizedProject = project == null ? "" : project.trim();
            // push status filter to storage when provided; otherwise use bounded page scan
            List<TaskSummarySnapshot> candidates = status != null
                    ? taskQueries.getTaskSummariesByStatus(status)
                    : taskQueries.listTaskSummaries(offset, Math.min(limit, 1000));
            List<ApiTask> items = candidates.stream()
                    .filter(task -> canViewTaskSummary(task, submitterViewer))
                    .filter(task -> matchesProject(task.getProject(), normalizedProject))
                    .filter(task -> matchesKeyword(task.getTaskId(), task.getTaskName(), task.getProject(), normalizedKeyword))
                    .sorted(Comparator
                            .comparing(TaskSummarySnapshot::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                    .map(taskApiContractAssembler::toApiTask)
                    .toList();
            return ok(taskApiContractAssembler.toTaskListResult(items));
        });
    }

    @PostMapping("")
    @Operation(
            summary = "Create task shell",
            description = "Creates only the task shell. Work items must be ingested separately through /items."
    )
    public ResponseEntity<ApiResponse<ApiTaskCreateOutcome>> createTask(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            HttpServletRequest httpRequest,
            @RequestBody TaskShellCreateApiRequest requestBody) {
        return executeApi("Task shell create failed", () -> {
            validateKnownFields(requestBody, "task shell create");

            ApiAuthorizationService.AuthorizedSubmitterTaskCreate submitterTaskCreate =
                    resolveSubmitterTaskCreate(apiKeyHeader, authorizationHeader, requestBody);
            if (submitterTaskCreate != null) {
                TaskShellSnapshot task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                        toMassTaskShellCreateRequest(requestBody, submitterTaskCreate.project(), submitterTaskCreate.userId()),
                        submitterTaskCreate.principal()
                ));
                return ok(taskApiContractAssembler.toCreateOutcome(
                        task,
                        requestBody.getExecutionSpec(),
                        submitterTaskCreate.principal().getPrincipalId(),
                        "Task shell created"
                ));
            }

            PrincipalContext operator = apiAuthService.requireAuthenticated(httpRequest);
            TaskShellSnapshot task = taskAdmin.createTaskShell(TaskOwnershipSupport.stamp(
                    toMassTaskShellCreateRequest(requestBody),
                    operator
            ));
            return ok(taskApiContractAssembler.toCreateOutcome(
                    task,
                    requestBody.getExecutionSpec(),
                    operator.getPrincipalId(),
                    "Task shell created"
            ));
        });
    }

    @GetMapping("/{taskId}")
    @Operation(
            summary = "Get task detail",
            description = "Returns task shell, aggregate state, execution, counters, timestamps, and security view. Item payload snapshots are not returned by default."
    )
    public ResponseEntity<ApiResponse<ApiTaskGetResult>> getTask(
                                                                    @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                    @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                    @PathVariable String taskId) {
        return executeApi("Task lookup failed", () -> {
            TaskDetailSnapshot task = requireAuthorizedTaskDetail(apiKeyHeader, authorizationHeader, taskId);
            return ok(taskApiContractAssembler.toGetResult(
                    task,
                    taskSecurityViewSupport.toSecurityView(task.getSharedConfig())
            ));
        });
    }

    @PatchMapping("/{taskId}")
    @Operation(
            summary = "Update task shell",
            description = "Updates shell-level editable fields for NEW or BLOCKED tasks. taskName is server-derived and cannot be patched."
    )
    public ResponseEntity<ApiResponse<ApiTaskUpdateOutcome>> updateTask(@Parameter(description = "Task id")
                                                                        @PathVariable String taskId,
                                                                        @RequestBody TaskUpdateApiRequest requestBody) {
        return executeApi("Task update failed", () -> {
            TaskStateSnapshot state = getExistingTaskState(taskId);
            String status = state == null ? null : state.getStatus();
            if (!EDITABLE_TASK_STATUSES.contains(status)) {
                throw badRequestError("Task update failed: Only NEW or BLOCKED tasks can be updated");
            }

            validateKnownFields(requestBody, "task update");
            MassTaskUpdateRequest request = toTaskUpdateRequest(requestBody);
            taskAdmin.updateTaskDefinition(taskId, request);
            return ok(taskApiContractAssembler.toUpdateOutcome(taskId, state, "Task updated"));
        });
    }

    @PostMapping("/{taskId}/items")
    @Operation(
            summary = "Append task items",
            description = "Explicitly ingests a batch of opaque work item payloads while task intake is open."
    )
    public ResponseEntity<ApiResponse<ApiTaskAppendOutcome>> appendTaskItems(
                                                                             @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                                             @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                                             @PathVariable String taskId,
                                                                             @RequestBody TaskItemBatchIngestApiRequest requestBody) {
        return executeApi("Append items failed", () -> {
            validateKnownFields(requestBody, "task append items");
            TaskAccessSnapshot task = requireTaskAccess(taskId);
            List<Object> items = requestBody.getItems();
            if (items == null || items.isEmpty()) {
                throw badRequestError("items must be a non-empty list");
            }
            validateIngestGuardrails(items);
            List<String> eventCodes = resolveAppendEventCodes(requestBody, items);
            if (eventCodes.isEmpty()) {
                throw badRequestError("append requires batch eventCode or per-item eventCode");
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
            return ok(taskApiContractAssembler.toAppendOutcome(
                    taskId,
                    added,
                    null,
                    task.getIntakeStatus(),
                    "Items appended"
            ));
        });
    }

    @PostMapping("/{taskId}/commands")
    @Operation(
            summary = "Execute task command",
            description = "Runs a lifecycle, governance, or intake command such as APPROVE, REJECT, PAUSE, RESUME, TERMINATE, BLOCK, or SEAL."
    )
    public ResponseEntity<ApiResponse<ApiTaskCommandOutcome>> executeTaskCommand(HttpServletRequest httpRequest,
                                                                                 @Parameter(description = "Task id")
                                                                                 @PathVariable String taskId,
                                                                                 @RequestBody TaskCommandApiRequest requestBody) {
        return executeApi("Task command failed", () -> {
            validateKnownFields(requestBody, "task command");
            TaskCommandAuthorization authorization = resolveTaskCommandAuthorization(requestBody.getCommand());
            requireTaskCommandPermission(httpRequest, taskId, authorization, requestBody.getCommand());

            TaskCommandResult result = taskAdmin.executeTaskCommand(taskId, toMassTaskCommandRequest(requestBody));
            if (result.isAccepted()) {
                return ok(taskApiContractAssembler.toCommandOutcome(result));
            }
            if (!result.isTaskExists()) {
                throw notFoundError("Task not found: " + taskId);
            }
            throw conflictError(result.getFailureReason() != null
                    ? result.getFailureReason()
                    : "Task command is not allowed in the current state");
        });
    }

    @GetMapping("/{taskId}/results")
    @Operation(
            summary = "Read live task results",
            description = "Reads an ordered result window using afterSeq. This is checkpoint-style sequential reading, not pagination and not ack-based consumption."
    )
    public ResponseEntity<ApiResponse<ApiTaskResultWindow>> getTaskResults(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @Parameter(description = "Task id")
            @PathVariable String taskId,
            @Parameter(description = "Return items after this task-local sequence number")
            @RequestParam(defaultValue = "0") long afterSeq,
            @Parameter(description = "Maximum number of items in this read window")
            @RequestParam(required = false) Integer limit) {
        return executeApi("Task results lookup failed", () -> {
            if (afterSeq < 0) {
                throw badRequestError("afterSeq must be greater than or equal to 0");
            }
            TaskDetailSnapshot task = requireAuthorizedTaskDetail(apiKeyHeader, authorizationHeader, taskId);
            ensureTaskDetailStoreConfigured();
            List<TaskDetailStore.TaskMessageProjection> projections = loadAllTaskMessageProjections(taskId);
            int resolvedLimit = resolveResultWindow(limit);
            List<ApiTaskResultItem> items = sliceTaskResultItems(projections, afterSeq, resolvedLimit);
            long nextAfterSeq = items.isEmpty() ? afterSeq : items.get(items.size() - 1).seq();
            boolean taskTerminal = isTerminalTask(task);
            boolean archiveReady = isArchiveReady(task);

            return ok(taskApiContractAssembler.toResultWindow(
                    taskId,
                    taskTerminal,
                    archiveReady,
                    items,
                    nextAfterSeq,
                    nextAfterSeq < projections.size(),
                    archiveReady ? "/api/v1/tasks/" + taskId + "/results/archive" : null
            ));
        });
    }

    @GetMapping("/{taskId}/results/archive")
    @Operation(
            summary = "Get task result archive manifest",
            description = "Returns terminal archive readiness and download metadata. Archive content is fixed to gzip-compressed ndjson."
    )
    public ResponseEntity<ApiResponse<ApiTaskResultArchive>> getTaskResultsArchiveManifest(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String taskId) {
        return executeApi("Task result archive lookup failed", () -> {
            TaskDetailSnapshot task = requireAuthorizedTaskDetail(apiKeyHeader, authorizationHeader, taskId);
            ensureTaskDetailStoreConfigured();
            boolean ready = isArchiveReady(task);
            if (ready) {
                List<TaskDetailStore.TaskMessageProjection> projections = loadAllTaskMessageProjections(taskId);
                byte[] archiveBytes = buildTaskResultArchive(projections);
                return ok(taskApiContractAssembler.toResultArchive(
                        taskId,
                        true,
                        NDJSON_MEDIA_TYPE.toString(),
                        projections.size(),
                        archiveBytes.length,
                        sha256Hex(archiveBytes),
                        "/api/v1/tasks/" + taskId + "/results/archive/content"
                ));
            }
            return ok(taskApiContractAssembler.toResultArchive(
                    taskId,
                    false,
                    NDJSON_MEDIA_TYPE.toString(),
                    0,
                    0,
                    "",
                    ""
            ));
        });
    }

    @GetMapping("/{taskId}/results/archive/content")
    @Operation(
            summary = "Download task result archive",
            description = "Downloads the gzip-compressed ndjson result archive. This endpoint returns raw content rather than ApiResponse."
    )
    public ResponseEntity<?> downloadTaskResultsArchive(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String taskId) {
        return executeRawApi("Task result archive download failed", () -> {
            TaskDetailSnapshot task = requireAuthorizedTaskDetail(apiKeyHeader, authorizationHeader, taskId);
            ensureTaskDetailStoreConfigured();
            if (!isArchiveReady(task)) {
                throw conflictError("Task result archive is not ready");
            }
            byte[] archiveBytes = buildTaskResultArchive(loadAllTaskMessageProjections(taskId));
            return ResponseEntity.ok()
                    .contentType(NDJSON_MEDIA_TYPE)
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + buildTaskResultArchiveFileName(task) + "\"")
                    .body(archiveBytes);
        });
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    private <T> ResponseEntity<ApiResponse<T>> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, message));
    }

    private <T> ResponseEntity<ApiResponse<T>> conflict(String message) {
        return ResponseEntity.status(409).body(ApiResponse.error(409, message));
    }

    private <T> ResponseEntity<ApiResponse<T>> unauthorized(String message) {
        return ResponseEntity.status(401).body(ApiResponse.error(401, message));
    }

    private <T> ResponseEntity<ApiResponse<T>> forbidden(String message) {
        return ResponseEntity.status(403).body(ApiResponse.error(403, message));
    }

    private <T> ResponseEntity<ApiResponse<T>> notFound(String message) {
        return ResponseEntity.status(404).body(ApiResponse.error(404, message));
    }

    private <T> ResponseEntity<ApiResponse<T>> executeApi(String failurePrefix,
                                                          ApiResponseSupplier<T> action) {
        try {
            return action.execute();
        } catch (TaskApiException e) {
            return e.toResponse();
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest(failurePrefix + ": " + e.getMessage());
        }
    }

    private ResponseEntity<?> executeRawApi(String failurePrefix,
                                            RawResponseSupplier action) {
        try {
            return action.execute();
        } catch (TaskApiException e) {
            return e.toResponse();
        } catch (SdkUnauthenticatedException e) {
            return unauthorized(e.getMessage());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (Exception e) {
            return badRequest(failurePrefix + ": " + e.getMessage());
        }
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

    private MassTaskCommandRequest toMassTaskCommandRequest(TaskCommandApiRequest requestBody) {
        return MassTaskCommandRequest.builder()
                .command(requestBody.getCommand())
                .reason(requestBody.getReason())
                .options(requestBody.getOptions())
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

    private boolean matchesProject(String taskProject, String normalizedProject) {
        if (normalizedProject == null || normalizedProject.isBlank()) {
            return true;
        }
        if (taskProject == null || taskProject.isBlank()) {
            return false;
        }
        return taskProject.trim().equalsIgnoreCase(normalizedProject.trim());
    }

    private boolean containsIgnoreCase(String source, String normalizedKeyword) {
        return source != null && source.toLowerCase().contains(normalizedKeyword);
    }

    private TaskCommandAuthorization resolveTaskCommandAuthorization(String command) {
        String normalizedCommand = normalizeTaskCommand(command);
        return switch (normalizedCommand) {
            case "APPROVE", "REJECT" -> new TaskCommandAuthorization(PlatformAction.APPROVE, ApiPermissionNames.TASK_GOVERN);
            case "PAUSE" -> new TaskCommandAuthorization(PlatformAction.PAUSE, ApiPermissionNames.TASK_CONTROL);
            case "RESUME" -> new TaskCommandAuthorization(PlatformAction.RESUME, ApiPermissionNames.TASK_CONTROL);
            case "TERMINATE" -> new TaskCommandAuthorization(PlatformAction.TERMINATE, ApiPermissionNames.TASK_CONTROL);
            case "BLOCK", "SEAL" -> new TaskCommandAuthorization(PlatformAction.EDIT, ApiPermissionNames.TASK_EDIT);
            default -> throw new IllegalArgumentException("Unsupported task command: " + normalizedCommand);
        };
    }

    private void requireTaskCommandPermission(HttpServletRequest request,
                                              String taskId,
                                              TaskCommandAuthorization authorization,
                                              String requestedCommand) {
        PrincipalContext principal = request != null
                ? (PrincipalContext) request.getAttribute(com.xa.mass.api.auth.ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR)
                : null;
        if (principal == null) {
            principal = apiAuthService.requireAuthenticated(request);
        }
        apiAuthorizationService.requireOperatorRoutePermission(
                principal,
                PlatformResourceType.TASK,
                authorization.action(),
                authorization.permission(),
                "task-command",
                Map.of(
                        "taskId", taskId != null ? taskId : "",
                        "command", requestedCommand != null ? requestedCommand : ""
                )
        );
    }

    private String normalizeTaskCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        return command.trim().toUpperCase(Locale.ROOT);
    }

    private int resolveResultWindow(Integer requestedLimit) {
        if (requestedLimit == null || requestedLimit <= 0) {
            return DEFAULT_RESULT_WINDOW;
        }
        return Math.min(requestedLimit, MAX_RESULT_WINDOW);
    }

    private List<TaskDetailStore.TaskMessageProjection> loadAllTaskMessageProjections(String taskId) {
        ensureTaskDetailStoreConfigured();
        TaskDetailStore.TaskMessageStats stats = taskDetailStore.getTaskMessageStats(taskId);
        long total = stats != null ? stats.getTotal() : 0L;
        if (total <= 0L) {
            return List.of();
        }
        int boundedTotal = (int) Math.min(Integer.MAX_VALUE, total);
        return taskDetailStore.getTaskMessageProjections(taskId, boundedTotal);
    }

    private List<ApiTaskResultItem> sliceTaskResultItems(List<TaskDetailStore.TaskMessageProjection> projections,
                                                         long afterSeq,
                                                         int limit) {
        if (projections == null || projections.isEmpty() || limit <= 0) {
            return List.of();
        }
        int startIndex = (int) Math.min(Math.max(afterSeq, 0L), projections.size());
        int endIndex = Math.min(startIndex + limit, projections.size());
        List<ApiTaskResultItem> items = new ArrayList<>(Math.max(0, endIndex - startIndex));
        for (int index = startIndex; index < endIndex; index++) {
            items.add(toTaskResultItem(projections.get(index), index + 1L));
        }
        return List.copyOf(items);
    }

    private ApiTaskResultItem toTaskResultItem(TaskDetailStore.TaskMessageProjection projection, long seq) {
        return taskApiContractAssembler.toResultItem(projection, seq);
    }

    private boolean isTerminalTask(TaskDetailSnapshot task) {
        return task != null && "TERMINAL".equalsIgnoreCase(task.getStatus());
    }

    private boolean isArchiveReady(TaskDetailSnapshot task) {
        return isTerminalTask(task) && taskDetailStore != null;
    }

    private byte[] buildTaskResultArchive(List<TaskDetailStore.TaskMessageProjection> projections) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
                for (int index = 0; index < projections.size(); index++) {
                    ApiTaskResultItem row = toTaskResultItem(projections.get(index), index + 1L);
                    gzip.write(RESPONSE_OBJECT_MAPPER.writeValueAsBytes(row));
                    gzip.write('\n');
                }
            }
            return raw.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build task result archive: " + e.getMessage(), e);
        }
    }

    private String buildTaskResultArchiveFileName(TaskDetailSnapshot task) {
        String taskName = task != null && task.getTaskName() != null ? task.getTaskName().trim() : "task";
        String normalizedTaskName = taskName.replaceAll("[^a-zA-Z0-9._-]+", "-");
        if (normalizedTaskName.isBlank()) {
            normalizedTaskName = "task";
        }
        return normalizedTaskName + "-results-" + task.getTaskId() + ".ndjson.gz";
    }

    private String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute archive checksum", e);
        }
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

    private void validateKnownFields(TaskCommandApiRequest requestBody, String operationName) {
        if (requestBody == null || requestBody.getCommand() == null || requestBody.getCommand().isBlank()) {
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

    private TaskDetailSnapshot requireAuthorizedTaskDetail(String apiKeyHeader,
                                                           String authorizationHeader,
                                                           String taskId) {
        TaskDetailSnapshot task = requireTaskDetail(taskId);
        resolveTaskViewer(apiKeyHeader, authorizationHeader, task.getTaskId(), task.getProject(), task.getSharedConfig());
        return task;
    }

    private TaskDetailSnapshot requireTaskDetail(String taskId) {
        TaskDetailSnapshot task = taskQueries.getTaskDetail(taskId);
        if (task == null) {
            throw notFoundError("Task not found: " + taskId);
        }
        return task;
    }

    private TaskAccessSnapshot requireTaskAccess(String taskId) {
        TaskAccessSnapshot task = taskQueries.getTaskAccess(taskId);
        if (task == null) {
            throw notFoundError("Task not found: " + taskId);
        }
        return task;
    }

    private TaskStateSnapshot getExistingTaskState(String taskId) {
        TaskStateSnapshot state = taskQueries.getTaskState(taskId);
        if (state == null && !taskQueries.taskExists(taskId)) {
            throw notFoundError("Task not found: " + taskId);
        }
        return state;
    }

    private void requireTaskExists(String taskId) {
        if (!taskQueries.taskExists(taskId)) {
            throw notFoundError("Task not found: " + taskId);
        }
    }

    private void ensureTaskDetailStoreConfigured() {
        if (taskDetailStore == null) {
            throw new IllegalStateException("Task result storage is unavailable");
        }
    }

    private TaskApiException badRequestError(String message) {
        return new TaskApiException(400, message);
    }

    private TaskApiException conflictError(String message) {
        return new TaskApiException(409, message);
    }

    private TaskApiException notFoundError(String message) {
        return new TaskApiException(404, message);
    }

    private static final class SdkUnauthenticatedException extends RuntimeException {
        private SdkUnauthenticatedException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface ApiResponseSupplier<T> {
        ResponseEntity<ApiResponse<T>> execute() throws Exception;
    }

    @FunctionalInterface
    private interface RawResponseSupplier {
        ResponseEntity<?> execute() throws Exception;
    }

    private record TaskCommandAuthorization(PlatformAction action, String permission) {
    }

    private final class TaskApiException extends RuntimeException {
        private final int statusCode;

        private TaskApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private <T> ResponseEntity<ApiResponse<T>> toResponse() {
            return switch (statusCode) {
                case 400 -> badRequest(getMessage());
                case 404 -> notFound(getMessage());
                case 409 -> conflict(getMessage());
                default -> ResponseEntity.status(statusCode).body(ApiResponse.error(statusCode, getMessage()));
            };
        }
    }
}
