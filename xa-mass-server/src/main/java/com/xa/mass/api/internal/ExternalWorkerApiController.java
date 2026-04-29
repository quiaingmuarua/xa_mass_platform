package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiForbiddenException;
import com.xa.mass.api.auth.ApiUnauthenticatedException;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.*;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.AuthorizationDecision;
import com.xa.mass.sdk.authz.AuthorizationPolicy;
import com.xa.mass.sdk.authz.AuthorizationRequest;
import com.xa.mass.sdk.authz.DefaultAuthorizationPolicy;
import com.xa.mass.sdk.authz.PlatformAction;
import com.xa.mass.sdk.authz.PlatformResourceType;
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
@RequestMapping("/worker-api")
public class ExternalWorkerApiController {
    private static final long MAX_WORKER_POLL_TIMEOUT_MS = 30_000L;


    private static final String WORKER_ID_ATTRIBUTE = "workerId";

    private final ExternalWorkerOperations externalWorkerOperations;
    private final AuthProvider authProvider;
    private final AuthorizationPolicy authorizationPolicy;

    public ExternalWorkerApiController(ExternalWorkerOperations externalWorkerOperations,
                                       AuthProvider authProvider) {
        this(externalWorkerOperations, authProvider, new DefaultAuthorizationPolicy());
    }

    @Autowired
    public ExternalWorkerApiController(ExternalWorkerOperations externalWorkerOperations,
                                       AuthProvider authProvider,
                                       AuthorizationPolicy authorizationPolicy) {
        this.externalWorkerOperations = externalWorkerOperations;
        this.authProvider = authProvider;
        this.authorizationPolicy = authorizationPolicy == null ? new DefaultAuthorizationPolicy() : authorizationPolicy;
    }

    @PostMapping("/workers/register")
    public ApiResponse<Map<String, Object>> registerWorker(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalWorkerRegisterApiRequest requestBody) {
        validateRegisterRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER, PlatformAction.REGISTER, requestBody.getWorkerId(),
                null, toEventBindings(requestBody.getEventBindings()));
        String workerId = requireBoundWorkerId(submitter, requestBody.getWorkerId());
        String transportHint = resolveSupportedTransportHint(requestBody.getTransportHint());
        List<WorkerEventBinding> eventBindings = toEventBindings(requestBody.getEventBindings());
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

    @PostMapping("/worker-contexts/register")
    public ApiResponse<Map<String, Object>> registerWorkerContext(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalWorkerContextRegisterApiRequest requestBody) {
        validateContextRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER_CONTEXT, PlatformAction.REGISTER,
                requestBody.getWorkerId(), blankToNull(requestBody.getProject()), null);
        String workerId = requireBoundWorkerId(submitter, requestBody.getWorkerId());
        WorkerContextRegistration request = WorkerContextRegistration.builder()
                .workerContextId(requireNonBlank(requestBody.getWorkerContextId(), "workerContextId"))
                .workerId(workerId)
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

    @PostMapping("/workers/{workerId}/online")
    public ApiResponse<Map<String, Object>> workerOnline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER, PlatformAction.POLL, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "online");
        externalWorkerOperations.workerOnline(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "online",
                externalWorkerOperations.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}/heartbeat")
    public ApiResponse<Map<String, Object>> workerHeartbeat(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                            @PathVariable String workerId,
                                                            @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER, PlatformAction.POLL, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "heartbeat");
        externalWorkerOperations.workerHeartbeat(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "heartbeat",
                externalWorkerOperations.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}/offline")
    public ApiResponse<Map<String, Object>> workerOffline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                          @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                          @PathVariable String workerId,
                                                          @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER, PlatformAction.POLL, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "offline");
        externalWorkerOperations.workerOffline(boundWorkerId, requestBody == null ? null : requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "offline",
                externalWorkerOperations.getWorkerAdapterId(boundWorkerId),
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}/poll")
    public ApiResponse<Map<String, Object>> pollTasks(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                      @PathVariable String workerId,
                                                      @RequestBody(required = false) ExternalWorkerPollApiRequest requestBody) {
        validatePollRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER, PlatformAction.POLL, workerId, null, null);
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

    @PostMapping("/workers/{workerId}/results")
    public ApiResponse<Map<String, Object>> submitResult(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody ExternalWorkerResultSubmitApiRequest requestBody) {
        validateResultRequest(requestBody);
        PrincipalContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        authorizeWorkerRequest(submitter, PlatformResourceType.WORKER, PlatformAction.REPORT_RESULT, workerId, null, null);
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

    private PrincipalContext requireExternalWorkerSubmitter(String apiKeyHeader, String authorizationHeader) {
        PrincipalContext submitter =
                SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            throw new ApiUnauthenticatedException("Invalid or missing worker credential");
        }
        return submitter;
    }

    private String requireBoundWorkerId(PrincipalContext submitter, String requestedWorkerId) {
        return requireNonBlank(requestedWorkerId, "workerId");
    }

    private void authorizeWorkerRequest(PrincipalContext submitter,
                                        PlatformResourceType resourceType,
                                        PlatformAction action,
                                        String workerId,
                                        String project,
                                        List<WorkerEventBinding> eventBindings) {
        Map<String, Object> resourceAttributes = new LinkedHashMap<>();
        resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_REQUIRED_PERMISSION, PrincipalContext.EXTERNAL_WORKER_PERMISSION);
        if (eventBindings != null && !eventBindings.isEmpty()) {
            resourceAttributes.put(DefaultAuthorizationPolicy.ATTR_EVENT_BINDINGS, eventBindings);
        }
        AuthorizationDecision decision = authorizationPolicy.authorize(AuthorizationRequest.builder()
                .principal(submitter)
                .resourceType(resourceType)
                .action(action)
                .workerId(workerId)
                .project(project)
                .resourceAttributes(resourceAttributes)
                .build());
        if (!decision.isAllowed()) {
            throw new ApiForbiddenException(toSdkCredentialMessage(decision.getReason()));
        }
    }

    private String toSdkCredentialMessage(String reason) {
        if (reason == null || reason.isBlank()) {
            return "SDK credential authorization denied";
        }
        if (reason.startsWith("permission denied: ")) {
            return "SDK credential permission denied: " + reason.substring("permission denied: ".length());
        }
        if (reason.equals("worker binding missing")) {
            return "SDK credential is missing workerId binding";
        }
        if (reason.startsWith("worker binding denied: ")) {
            return "SDK credential worker binding denied: " + reason.substring("worker binding denied: ".length());
        }
        if (reason.startsWith("project scope denied: ")) {
            return "SDK credential project scope denied: " + reason.substring("project scope denied: ".length());
        }
        if (reason.startsWith("event scope denied: ")) {
            return "SDK credential event scope denied: " + reason.substring("event scope denied: ".length());
        }
        return reason;
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
