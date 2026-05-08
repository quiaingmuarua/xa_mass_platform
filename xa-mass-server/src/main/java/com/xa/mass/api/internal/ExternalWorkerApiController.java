package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.*;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.ExternalWorkerOperations;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/worker-api/v1")
public class ExternalWorkerApiController {
    private static final long MAX_WORKER_POLL_TIMEOUT_MS = 30_000L;


    private final ExternalWorkerOperations externalWorkerOperations;
    private final ApiAuthorizationService apiAuthorizationService;

    public ExternalWorkerApiController(ExternalWorkerOperations externalWorkerOperations,
                                       com.xa.mass.sdk.auth.AuthProvider authProvider) {
        this(externalWorkerOperations, new ApiAuthorizationService(authProvider, null));
    }

    @Autowired
    public ExternalWorkerApiController(ExternalWorkerOperations externalWorkerOperations,
                                       ApiAuthorizationService apiAuthorizationService) {
        this.externalWorkerOperations = externalWorkerOperations;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
    }

    @PostMapping("/workers")
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
        externalWorkerOperations.registerWorker(request);
        return ApiResponse.success(Map.of(
                "workerId", request.getWorkerId(),
                "adapterId", externalWorkerOperations.getWorkerAdapterId(workerId),
                "transportHint", transportHint,
                "eventBindings", request.getEventBindings()
        ));
    }

    @PostMapping("/workers/{workerId}/contexts")
    public ApiResponse<Map<String, Object>> registerWorkerContext(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @RequestBody ExternalWorkerContextRegisterApiRequest requestBody) {
        validateContextRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader,
                authorizationHeader,
                ApiSecurityScenario.WORKER_CONTEXT_REGISTER,
                workerId,
                blankToNull(requestBody.getProject()),
                null
        );
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        WorkerContextRegistration request = WorkerContextRegistration.builder()
                .workerContextId(requireNonBlank(requestBody.getWorkerContextId(), "workerContextId"))
                .workerId(boundWorkerId)
                .project(blankToNull(requestBody.getProject()))
                .routingTags(requestBody.getRoutingTags() == null ? Set.of() : requestBody.getRoutingTags())
                .attributes(requestBody.getAttributes())
                .build();
        externalWorkerOperations.registerWorkerContext(request);
        return ApiResponse.success(Map.of(
                "workerContextId", request.getWorkerContextId(),
                "workerId", request.getWorkerId()
        ));
    }

    @PostMapping("/workers/{workerId}:online")
    public ApiResponse<Map<String, Object>> workerOnline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_ONLINE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "online");
        externalWorkerOperations.workerOnline(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "online",
                externalWorkerOperations.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:heartbeat")
    public ApiResponse<Map<String, Object>> workerHeartbeat(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                            @PathVariable String workerId,
                                                            @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_HEARTBEAT, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "heartbeat");
        externalWorkerOperations.workerHeartbeat(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "heartbeat",
                externalWorkerOperations.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:offline")
    public ApiResponse<Map<String, Object>> workerOffline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                          @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                          @PathVariable String workerId,
                                                          @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_OFFLINE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "offline");
        externalWorkerOperations.workerOffline(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "offline",
                externalWorkerOperations.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:poll")
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
        List<TaskDispatchItem> items = externalWorkerOperations.pollTasks(boundWorkerId, maxMessages, timeoutMs);
        return ApiResponse.success(Map.of(
                "workerId", boundWorkerId,
                "items", items,
                "total", items.size()
        ));
    }

    @PostMapping("/workers/{workerId}:submit-result")
    public ApiResponse<Map<String, Object>> submitResult(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody ExternalWorkerResultSubmitApiRequest requestBody) {
        validateResultRequest(requestBody);
        PrincipalContext submitter = requireAuthorizedWorkerSubmitter(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_SUBMIT_RESULT, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "submitResult");
        boolean submitted = externalWorkerOperations.submitResult(boundWorkerId, new TaskResultReport(
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

    private void validateContextRequest(ExternalWorkerContextRegisterApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker context register request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker context register fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
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
        String transportHint = externalWorkerOperations.getWorkerTransportHint(normalizedWorkerId);
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
