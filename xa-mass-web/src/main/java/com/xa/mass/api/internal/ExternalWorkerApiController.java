package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiForbiddenException;
import com.xa.mass.api.auth.ApiUnauthenticatedException;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.*;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.ExternalWorkerOperations;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/worker-api")
public class ExternalWorkerApiController {

    private static final String WORKER_ID_ATTRIBUTE = "workerId";

    private final ExternalWorkerOperations externalWorkerOperations;
    private final AuthProvider authProvider;

    public ExternalWorkerApiController(ExternalWorkerOperations externalWorkerOperations,
                                       AuthProvider authProvider) {
        this.externalWorkerOperations = externalWorkerOperations;
        this.authProvider = authProvider;
    }

    @PostMapping("/workers/register")
    public ApiResponse<Map<String, Object>> registerWorker(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalWorkerRegisterApiRequest requestBody) {
        validateRegisterRequest(requestBody);
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        String workerId = requireBoundWorkerId(submitter, requestBody.getWorkerId());
        String transportHint = resolveSupportedTransportHint(requestBody.getTransportHint());
        List<WorkerEventBinding> eventBindings = toEventBindings(requestBody.getEventBindings());
        requireWorkerBindingScopes(submitter, eventBindings);
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
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        String workerId = requireBoundWorkerId(submitter, requestBody.getWorkerId());
        requireProjectScope(submitter, blankToNull(requestBody.getProject()));
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
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
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
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
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
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
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
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
        String boundWorkerId = requireBoundWorkerId(submitter, workerId);
        requirePollingWorker(boundWorkerId, "poll");
        int maxMessages = requestBody == null || requestBody.getMaxMessages() == null ? 1 : requestBody.getMaxMessages();
        List<TaskDispatchItem> items = externalWorkerOperations.pollTasks(boundWorkerId, maxMessages);
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
        TaskSubmitterContext submitter = requireExternalWorkerSubmitter(apiKeyHeader, authorizationHeader);
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

    private TaskSubmitterContext requireExternalWorkerSubmitter(String apiKeyHeader, String authorizationHeader) {
        TaskSubmitterContext submitter =
                SdkCredentialAuthSupport.authenticate(authProvider, apiKeyHeader, authorizationHeader);
        if (submitter == null) {
            throw new ApiUnauthenticatedException("Invalid or missing worker credential");
        }
        if (!submitter.hasPermission(TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION)) {
            throw new ApiForbiddenException("SDK credential permission denied: " + TaskSubmitterContext.EXTERNAL_WORKER_PERMISSION);
        }
        return submitter;
    }

    private String requireBoundWorkerId(TaskSubmitterContext submitter, String requestedWorkerId) {
        String workerId = requireNonBlank(requestedWorkerId, "workerId");
        String boundWorkerId = submitter.getAttributes().get(WORKER_ID_ATTRIBUTE);
        if (boundWorkerId == null || boundWorkerId.isBlank()) {
            throw new ApiForbiddenException("SDK credential is missing workerId binding");
        }
        if (!workerId.equals(boundWorkerId.trim())) {
            throw new ApiForbiddenException("SDK credential worker binding denied: " + workerId);
        }
        return workerId;
    }

    private void requireWorkerBindingScopes(TaskSubmitterContext submitter, List<WorkerEventBinding> bindings) {
        for (WorkerEventBinding binding : bindings) {
            if (!submitter.allowsEvent(binding.getEventCode())) {
                throw new ApiForbiddenException("SDK credential event scope denied: " + binding.getEventCode());
            }
            if (binding.getProjectCodes() == null || binding.getProjectCodes().isEmpty()) {
                continue;
            }
            for (String projectCode : binding.getProjectCodes()) {
                requireProjectScope(submitter, projectCode);
            }
        }
    }

    private void requireProjectScope(TaskSubmitterContext submitter, String projectCode) {
        if (projectCode != null && !submitter.allowsProject(projectCode)) {
            throw new ApiForbiddenException("SDK credential project scope denied: " + projectCode);
        }
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
