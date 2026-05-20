package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiForbiddenException;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.*;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.WorkerClientOperations;
import com.xa.mass.sdk.WorkerRegistryOperations;
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCapabilityReportSnapshot;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/worker-api/v1")
@Tag(name = "External Worker API", description = "External worker registration, polling, presence, and result submit APIs")
public class ExternalWorkerApiController {
    private static final long MAX_WORKER_POLL_TIMEOUT_MS = 30_000L;
    private static final List<String> ALLOWED_EXTERNAL_WORKER_STATES = List.of(
            "AVAILABLE",
            "DEGRADED",
            "DRAINING",
            "OFFLINE"
    );
    private static final String ALLOWED_EXTERNAL_WORKER_STATES_MESSAGE =
            String.join(", ", ALLOWED_EXTERNAL_WORKER_STATES);


    private final WorkerRegistryOperations workerRegistry;
    private final WorkerClientOperations workerClient;
    private final WorkerControlOperations workerControl;
    private final ApiAuthorizationService apiAuthorizationService;

    public ExternalWorkerApiController(WorkerRegistryOperations workerRegistry,
                                       WorkerClientOperations workerClient,
                                       WorkerControlOperations workerControl,
                                       ApiAuthorizationService apiAuthorizationService) {
        this.workerRegistry = workerRegistry;
        this.workerClient = workerClient;
        this.workerControl = workerControl;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
    }

    public ExternalWorkerApiController(WorkerRegistryOperations workerRegistry,
                                       WorkerClientOperations workerClient,
                                       ApiAuthorizationService apiAuthorizationService) {
        this(workerRegistry, workerClient, (WorkerControlOperations) null, apiAuthorizationService);
    }

    @Autowired
    public ExternalWorkerApiController(WorkerRegistryOperations workerRegistry,
                                       WorkerClientOperations workerClient,
                                       ObjectProvider<WorkerControlOperations> workerControlProvider,
                                       ApiAuthorizationService apiAuthorizationService) {
        this(
                workerRegistry,
                workerClient,
                workerControlProvider == null ? null : workerControlProvider.getIfAvailable(),
                apiAuthorizationService
        );
    }

    @PostMapping("/workers")
    @Operation(summary = "Register external worker", description = "Registers worker identity and eventBindings capability for external worker runtimes.")
    public ApiResponse<Map<String, Object>> registerWorker(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalWorkerRegisterApiRequest requestBody) {
        validateRegisterRequest(requestBody);
        List<WorkerEventBinding> eventBindings = toEventBindings(requestBody.getEventBindings());
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader,
                authorizationHeader,
                ApiSecurityScenario.WORKER_REGISTER,
                requestBody.getWorkerId(),
                null,
                eventBindings
        );
        String workerId = requireBoundWorkerId(submitter, requestBody.getWorkerId());
        String transportHint = resolveSupportedTransportHint(requestBody.getTransportHint());
        WorkerRegistration request = WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(blankToNull(requestBody.getWorkerGroupId()))
                .adapterId(blankToNull(requestBody.getAdapterId()))
                .transportHint(transportHint)
                .attributes(requestBody.getAttributes())
                .eventBindings(eventBindings)
                .build();
        workerRegistry.registerWorker(request);
        return ApiResponse.success(Map.of(
                "workerId", request.getWorkerId(),
                "workerGroupId", request.getWorkerGroupId(),
                "adapterId", workerClient.getWorkerAdapterId(workerId),
                "transportHint", transportHint,
                "eventBindings", request.getEventBindings()
        ));
    }

    @PostMapping("/workers/{workerId}:online")
    @Operation(summary = "Mark polling worker online", description = "Records external polling worker reachability through the worker client surface.")
    public ApiResponse<Map<String, Object>> workerOnline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_ONLINE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "online");
        workerClient.workerOnline(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "online",
                workerClient.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:heartbeat")
    @Operation(summary = "Heartbeat polling worker", description = "Refreshes external polling worker reachability without changing worker capability registration.")
    public ApiResponse<Map<String, Object>> workerHeartbeat(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                            @PathVariable String workerId,
                                                            @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_HEARTBEAT, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "heartbeat");
        workerClient.workerHeartbeat(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "heartbeat",
                workerClient.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:offline")
    @Operation(summary = "Mark polling worker offline", description = "Records external polling worker offline state through the worker client surface.")
    public ApiResponse<Map<String, Object>> workerOffline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                          @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                          @PathVariable String workerId,
                                                          @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_OFFLINE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "offline");
        workerClient.workerOffline(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "offline",
                workerClient.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:poll")
    @Operation(summary = "Poll task dispatch items", description = "Returns dispatch-ready task items for polling workers. Realtime workers must use their transport adapter.")
    public ApiResponse<Map<String, Object>> pollTasks(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                      @PathVariable String workerId,
                                                      @RequestBody(required = false) ExternalWorkerPollApiRequest requestBody) {
        validatePollRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_POLL, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "poll");
        int maxMessages = requestBody == null || requestBody.getMaxMessages() == null ? 1 : requestBody.getMaxMessages();
        long timeoutMs = requestBody == null || requestBody.getTimeoutMs() == null ? 0L : requestBody.getTimeoutMs();
        List<TaskDispatchItem> items = workerClient.pollTasks(boundWorkerId, maxMessages, timeoutMs);
        return ApiResponse.success(Map.of(
                "workerId", boundWorkerId,
                "items", items,
                "total", items.size()
        ));
    }

    @PostMapping("/workers/{workerId}:submit-result")
    @Operation(summary = "Submit task item result", description = "Submits a worker result callback for a previously dispatched task item.")
    public ApiResponse<Map<String, Object>> submitResult(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody ExternalWorkerResultSubmitApiRequest requestBody) {
        validateResultRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_SUBMIT_RESULT, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "submitResult");
        boolean submitted = workerClient.submitResult(boundWorkerId, new TaskResultReport(
                requireNonBlank(requestBody.getTaskId(), "taskId"),
                requireNonBlank(requestBody.getMessageId(), "messageId"),
                requestBody.isSuccess(),
                blankToNull(requestBody.getDetail()),
                blankToNull(requestBody.getErrorCode()),
                requestBody.getOutput()
        ));
        return ApiResponse.success(Map.of(
                "workerId", boundWorkerId,
                "taskId", requestBody.getTaskId().trim(),
                "messageId", requestBody.getMessageId().trim(),
                "submitted", submitted
            ));
    }

    @PostMapping("/workers/{workerId}:report-capability")
    @Operation(summary = "Report worker capability", description = "Reports a polling worker capability snapshot through the owner-backed worker control surface.")
    public ApiResponse<WorkerCapabilityReportSnapshot> reportWorkerCapability(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @RequestBody ExternalWorkerCapabilityReportApiRequest requestBody) {
        validateCapabilityReportRequest(requestBody);
        List<WorkerEventBinding> eventBindings = toCapabilityEventBindings(requestBody.getAvailableEventCodes());
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_REPORT_CAPABILITY, workerId, null, eventBindings);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "reportCapability");
        requireWorkerEventScope(submitter, requestBody.getAvailableEventCodes());
        long capabilityVersion = resolveOptionalVersion(requestBody.getCapabilityVersion(), "capabilityVersion");
        return ApiResponse.success(requireWorkerControl().reportWorkerCapability(
                new WorkerCapabilityReportRequest(
                        resolveWorkerId(workerId, requestBody.getWorkerId()),
                        capabilityVersion,
                        requestBody.getAvailableEventCodes(),
                        requestBody.getSchedulingAttributes(),
                        blankToNull(requestBody.getAgentVersion())
                )
        ));
    }

    @PostMapping("/workers/{workerId}:report-state")
    @Operation(summary = "Report worker state", description = "Reports a polling worker bounded state snapshot through the owner-backed worker control surface.")
    public ApiResponse<WorkerStateReportSnapshot> reportWorkerState(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @RequestBody ExternalWorkerStateReportApiRequest requestBody) {
        validateStateReportRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_REPORT_STATE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "reportState");
        long stateVersion = resolveOptionalVersion(requestBody.getStateVersion(), "stateVersion");
        return ApiResponse.success(requireWorkerControl().reportWorkerState(
                new WorkerStateReportRequest(
                        resolveWorkerId(workerId, requestBody.getWorkerId()),
                        stateVersion,
                        normalizeExternalWorkerState(requestBody.getState()),
                        blankToNull(requestBody.getReason()),
                        requestBody.getObservedAt(),
                        requestBody.getAttributes()
                )
        ));
    }

    @PostMapping("/workers/{workerId}/commands/{commandId}:ack")
    @Operation(summary = "Acknowledge worker command", description = "Reports a polling worker command acknowledgement through the owner-backed worker control surface.")
    public ApiResponse<WorkerCommandResultSnapshot> acknowledgeWorkerCommand(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @PathVariable String commandId,
            @RequestBody WorkerCommandAcknowledgementApiRequest requestBody) {
        validateCommandAcknowledgementRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_ACK_COMMAND, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "ackCommand");
        WorkerCommandSnapshot command = requireWorkerCommandOwnership(commandId, boundWorkerId);
        return ApiResponse.success(requireWorkerControl().acknowledgeWorkerCommand(
                new WorkerCommandAcknowledgementRequest(
                        command.commandId(),
                        requireNonBlank(requestBody.getStatus(), "status"),
                        blankToNull(requestBody.getReason())
                )
        ));
    }

    private void validateRegisterRequest(ExternalWorkerRegisterApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker register request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker register fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        if (requestBody.getEventBindings() == null || requestBody.getEventBindings().isEmpty()) {
            throw new IllegalArgumentException("eventBindings is required");
        }
        requireNonBlank(requestBody.getWorkerGroupId(), "workerGroupId");
        for (ExternalWorkerEventBindingApiRequest binding : requestBody.getEventBindings()) {
            if (binding == null) {
                throw new IllegalArgumentException("eventBindings must not contain null items");
            }
            if (binding.hasUnknownFields()) {
                throw new IllegalArgumentException("Unsupported worker event binding fields: "
                        + String.join(", ", binding.getUnknownFieldNames()));
            }
        }
    }

    private void validatePresenceRequest(ExternalWorkerPresenceApiRequest requestBody) {
        if (requestBody != null && requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker presence fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validatePollRequest(ExternalWorkerPollApiRequest requestBody) {
        if (requestBody != null && requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker poll fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        if (requestBody != null && requestBody.getMaxMessages() != null && requestBody.getMaxMessages() <= 0) {
            throw new IllegalArgumentException("maxMessages must be greater than 0");
        }
        if (requestBody != null && requestBody.getTimeoutMs() != null) {
            if (requestBody.getTimeoutMs() < 0) {
                throw new IllegalArgumentException("timeoutMs must be greater than or equal to 0");
            }
            if (requestBody.getTimeoutMs() > MAX_WORKER_POLL_TIMEOUT_MS) {
                throw new IllegalArgumentException("timeoutMs must be less than or equal to " + MAX_WORKER_POLL_TIMEOUT_MS);
            }
        }
    }

    private void validateResultRequest(ExternalWorkerResultSubmitApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker result request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker result fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateCapabilityReportRequest(ExternalWorkerCapabilityReportApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker capability report request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker capability report fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateStateReportRequest(ExternalWorkerStateReportApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker state report request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker state report fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getState(), "state");
    }

    private void validateCommandAcknowledgementRequest(WorkerCommandAcknowledgementApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker command acknowledgement request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker command acknowledgement fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getStatus(), "status");
    }

    private String resolveSupportedTransportHint(String requestedTransportHint) {
        String normalized = requestedTransportHint == null || requestedTransportHint.isBlank()
                ? WorkerTransportHints.POLLING
                : WorkerTransportHints.normalize(requestedTransportHint);
        if (!WorkerTransportHints.POLLING.equals(normalized) && !WorkerTransportHints.REALTIME.equals(normalized)) {
            throw new IllegalArgumentException("External worker API supports only polling or realtime transport");
        }
        return normalized;
    }

    private void requirePollingWorker(String workerId, String operation) {
        String normalizedWorkerId = requireNonBlank(workerId, "workerId");
        String transportHint = workerClient.getWorkerTransportHint(normalizedWorkerId);
        if (WorkerTransportHints.isPolling(transportHint)) {
            return;
        }
        throw new IllegalStateException("External worker API " + operation
                + " only supports polling workers; worker "
                + normalizedWorkerId + " uses transport '" + transportHint + "'");
    }

    private List<WorkerEventBinding> toEventBindings(List<ExternalWorkerEventBindingApiRequest> requests) {
        return requests.stream()
                .map(request -> WorkerEventBinding.builder()
                        .eventCode(requireNonBlank(request.getEventCode(), "eventCode"))
                        .projectCodes(request.getProjectCodes())
                        .build())
                .toList();
    }

    private List<WorkerEventBinding> toCapabilityEventBindings(List<String> eventCodes) {
        if (eventCodes == null || eventCodes.isEmpty()) {
            return List.of();
        }
        return eventCodes.stream()
                .map(eventCode -> WorkerEventBinding.builder()
                        .eventCode(requireNonBlank(eventCode, "availableEventCodes"))
                        .build())
                .toList();
    }

    private long resolveOptionalVersion(Long providedVersion, String fieldName) {
        if (providedVersion == null) {
            return System.currentTimeMillis();
        }
        if (providedVersion <= 0L) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return providedVersion;
    }

    private String normalizeExternalWorkerState(String state) {
        String normalized = requireNonBlank(state, "state").toUpperCase(Locale.ROOT);
        if (!ALLOWED_EXTERNAL_WORKER_STATES.contains(normalized)) {
            throw new IllegalArgumentException("state must be one of " + ALLOWED_EXTERNAL_WORKER_STATES_MESSAGE);
        }
        return normalized;
    }

    private void requireWorkerEventScope(PrincipalContext principal, List<String> eventCodes) {
        if (principal == null || eventCodes == null || eventCodes.isEmpty()) {
            return;
        }
        for (String eventCode : eventCodes) {
            String normalizedEventCode = requireNonBlank(eventCode, "availableEventCodes");
            if (!principal.allowsEvent(normalizedEventCode)) {
                throw new ApiForbiddenException("Worker credential event scope denied: " + normalizedEventCode);
            }
        }
    }

    private String resolveWorkerId(String pathWorkerId, String requestWorkerId) {
        String bodyWorkerId = blankToNull(requestWorkerId);
        if (bodyWorkerId != null && !bodyWorkerId.equals(pathWorkerId)) {
            throw new IllegalArgumentException("workerId in request body must match path workerId");
        }
        return pathWorkerId;
    }

    private WorkerControlOperations requireWorkerControl() {
        if (workerControl == null) {
            throw new IllegalStateException("Worker control operations are not available");
        }
        return workerControl;
    }

    private WorkerCommandSnapshot requireWorkerCommandOwnership(String commandId, String workerId) {
        WorkerCommandSnapshot command = requireWorkerControl().getWorkerCommand(requireNonBlank(commandId, "commandId"));
        if (command == null) {
            throw new IllegalArgumentException("Unknown worker command: " + commandId);
        }
        if (!workerId.equals(command.workerId())) {
            throw new IllegalArgumentException("worker command does not belong to worker " + workerId);
        }
        return command;
    }

    private String requireBoundWorkerId(PrincipalContext submitter, String requestedWorkerId) {
        return requireNonBlank(requestedWorkerId, "workerId");
    }

    private PrincipalContext requireAuthorizedWorkerSubmitter(String apiKeyHeader,
                                                              String authorizationHeader,
                                                              ApiSecurityScenario scenario,
                                                              String workerId,
                                                              String project,
                                                              List<WorkerEventBinding> eventBindings) {
        return apiAuthorizationService.requireAuthorizedWorkerCredential(
                apiKeyHeader,
                authorizationHeader,
                scenario,
                workerId,
                project,
                eventBindings,
                Map.of(
                        "workerId", String.valueOf(workerId),
                        "scenario", scenario.name()
                )
        );
    }

    private Map<String, Object> presenceResponse(String workerId,
                                                String action,
                                                String adapterId,
                                                String transportHint) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workerId", requireNonBlank(workerId, "workerId"));
        response.put("action", action);
        response.put("adapterId", requireNonBlank(adapterId, "adapterId"));
        response.put("transportHint", requireNonBlank(transportHint, "transportHint"));
        return response;
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
