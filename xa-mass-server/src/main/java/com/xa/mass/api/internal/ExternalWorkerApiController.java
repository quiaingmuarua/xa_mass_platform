package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiForbiddenException;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.*;
import com.xa.mass.api.worker.registration.WorkerRegistrationObservationService;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.WorkerClientOperations;
import com.xa.mass.sdk.WorkerRegistryOperations;
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCapabilityReportSnapshot;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandResultSnapshot;
import com.xa.mass.sdk.model.WorkerCommandSnapshot;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerStateReportSnapshot;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.sdk.worker.WorkerInvocation;
import com.xa.mass.sdk.worker.WorkerResultSubmission;
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
    private static final String WORKER_ID_BINDING_ATTRIBUTE = "workerId";
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
    private final WorkerRegistrationObservationService registrationObservationService;

    public ExternalWorkerApiController(WorkerRegistryOperations workerRegistry,
                                       WorkerClientOperations workerClient,
                                       WorkerControlOperations workerControl,
                                       ApiAuthorizationService apiAuthorizationService) {
        this(workerRegistry, workerClient, workerControl, apiAuthorizationService, null);
    }

    public ExternalWorkerApiController(WorkerRegistryOperations workerRegistry,
                                       WorkerClientOperations workerClient,
                                       WorkerControlOperations workerControl,
                                       ApiAuthorizationService apiAuthorizationService,
                                       WorkerRegistrationObservationService registrationObservationService) {
        this.workerRegistry = workerRegistry;
        this.workerClient = workerClient;
        this.workerControl = workerControl;
        this.apiAuthorizationService = apiAuthorizationService == null ? new ApiAuthorizationService() : apiAuthorizationService;
        this.registrationObservationService = registrationObservationService;
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
                                       ObjectProvider<WorkerRegistrationObservationService> registrationObservationServiceProvider,
                                       ApiAuthorizationService apiAuthorizationService) {
        this(
                workerRegistry,
                workerClient,
                workerControlProvider == null ? null : workerControlProvider.getIfAvailable(),
                apiAuthorizationService,
                registrationObservationServiceProvider == null
                        ? null
                        : registrationObservationServiceProvider.getIfAvailable()
        );
    }

    @PostMapping("/adapter-nodes")
    @Operation(summary = "Register external adapter node", description = "Registers AdapterNode endpoint identity before node/group binding and worker registration.")
    public ApiResponse<Map<String, Object>> registerAdapterNode(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalAdapterNodeRegisterApiRequest requestBody) {
        validateAdapterNodeRegisterRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader,
                authorizationHeader,
                ApiSecurityScenario.WORKER_REGISTER,
                null,
                null,
                null
        );
        AdapterNodeRegistration request = AdapterNodeRegistration.builder()
                .adapterNodeId(requestBody.getAdapterNodeId())
                .adapterType(requestBody.getAdapterType())
                .adapterVersion(requestBody.getAdapterVersion())
                .endpointId(requestBody.getEndpointId())
                .enabled(requestBody.getEnabled() == null || requestBody.getEnabled())
                .online(requestBody.getOnline() == null || requestBody.getOnline())
                .attributes(requestBody.getAttributes())
                .build();
        workerRegistry.registerAdapterNode(request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adapterNodeId", request.getAdapterNodeId());
        response.put("adapterType", request.getAdapterType());
        response.put("adapterVersion", request.getAdapterVersion());
        response.put("endpointId", request.getEndpointId());
        response.put("enabled", request.isEnabled());
        response.put("online", request.isOnline());
        response.put("attributes", request.getAttributes());
        observeRegistration("ADAPTER_NODE", request.getAdapterNodeId(), "REGISTER", workerPrincipal, response);
        return ApiResponse.success(response);
    }

    @PostMapping("/worker-groups")
    @Operation(summary = "Declare external worker group", description = "Declares WorkerGroup capability truth before individual workers register.")
    public ApiResponse<Map<String, Object>> declareWorkerGroup(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalWorkerGroupDeclareApiRequest requestBody) {
        validateWorkerGroupDeclareRequest(requestBody);
        List<WorkerEventBinding> eventBindings = toEventBindings(requestBody.getEventBindings());
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader,
                authorizationHeader,
                ApiSecurityScenario.WORKER_REGISTER,
                null,
                null,
                eventBindings
        );
        WorkerGroupDeclaration.Builder builder = WorkerGroupDeclaration.builder()
                .groupId(requestBody.getGroupId())
                .eventBindings(eventBindings)
                .defaultAttributes(requestBody.getDefaultAttributes());
        if (requestBody.getDefaultMaxConcurrentWork() != null) {
            builder.defaultMaxConcurrentWork(requestBody.getDefaultMaxConcurrentWork());
        }
        WorkerGroupDeclaration request = builder.build();
        workerRegistry.declareWorkerGroup(request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("groupId", request.getGroupId());
        response.put("eventBindings", request.getEventBindings());
        response.put("defaultAttributes", request.getDefaultAttributes());
        response.put("defaultMaxConcurrentWork", request.getDefaultMaxConcurrentWork());
        observeRegistration("WORKER_GROUP", request.getGroupId(), "DECLARE", workerPrincipal, response);
        return ApiResponse.success(response);
    }

    @PostMapping("/node-group-bindings")
    @Operation(summary = "Bind adapter node to worker group", description = "Declares that an AdapterNode currently hosts a WorkerGroup.")
    public ApiResponse<Map<String, Object>> bindNodeGroup(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalNodeGroupBindingApiRequest requestBody) {
        validateNodeGroupBindingRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader,
                authorizationHeader,
                ApiSecurityScenario.WORKER_REGISTER,
                null,
                null,
                null
        );
        NodeGroupBindingRegistration request = NodeGroupBindingRegistration.builder()
                .adapterNodeId(requestBody.getAdapterNodeId())
                .workerGroupId(requestBody.getWorkerGroupId())
                .pluginVersion(requestBody.getPluginVersion())
                .deploymentVersion(requestBody.getDeploymentVersion())
                .enabled(requestBody.getEnabled() == null || requestBody.getEnabled())
                .draining(requestBody.getDraining() != null && requestBody.getDraining())
                .attributes(requestBody.getAttributes())
                .build();
        workerRegistry.bindNodeGroup(request);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adapterNodeId", request.getAdapterNodeId());
        response.put("workerGroupId", request.getWorkerGroupId());
        response.put("pluginVersion", request.getPluginVersion());
        response.put("deploymentVersion", request.getDeploymentVersion());
        response.put("enabled", request.isEnabled());
        response.put("draining", request.isDraining());
        response.put("attributes", request.getAttributes());
        observeRegistration("NODE_GROUP_BINDING",
                request.getAdapterNodeId() + ":" + request.getWorkerGroupId(),
                "BIND",
                workerPrincipal,
                response);
        return ApiResponse.success(response);
    }

    @PostMapping("/workers")
    @Operation(summary = "Register external worker", description = "Registers worker execution identity for external worker runtimes.")
    public ApiResponse<Map<String, Object>> registerWorker(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody ExternalWorkerRegisterApiRequest requestBody) {
        validateRegisterRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader,
                authorizationHeader,
                ApiSecurityScenario.WORKER_REGISTER,
                requestBody.getWorkerId(),
                null,
                null
        );
        String workerId = requireBoundWorkerId(workerPrincipal, requestBody.getWorkerId());
        String transportHint = resolveSupportedTransportHint(requestBody.getTransportHint());
        WorkerRegistration request = WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(blankToNull(requestBody.getWorkerGroupId()))
                .transportHint(transportHint)
                .attributes(requestBody.getAttributes())
                .build();
        workerRegistry.registerWorker(request);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workerId", request.getWorkerId());
        response.put("workerGroupId", request.getWorkerGroupId());
        response.put("transportHint", transportHint);
        observeRegistration("WORKER", request.getWorkerId(), "REGISTER", workerPrincipal, response);
        return ApiResponse.success(response);
    }

    @PostMapping("/workers/{workerId}:online")
    @Operation(summary = "Mark polling worker online", description = "Records external polling worker reachability through the worker client surface.")
    public ApiResponse<Map<String, Object>> workerOnline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                         @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                         @PathVariable String workerId,
                                                         @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_ONLINE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "online");
        workerClient.workerOnline(boundWorkerId, presenceSessionToken(requestBody), requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "online",
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:heartbeat")
    @Operation(summary = "Heartbeat polling worker", description = "Refreshes external polling worker reachability without changing worker capability registration.")
    public ApiResponse<Map<String, Object>> workerHeartbeat(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                            @PathVariable String workerId,
                                                            @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_HEARTBEAT, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "heartbeat");
        workerClient.workerHeartbeat(boundWorkerId, presenceSessionToken(requestBody), requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "heartbeat",
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:offline")
    @Operation(summary = "Mark polling worker offline", description = "Records external polling worker offline state through the worker client surface.")
    public ApiResponse<Map<String, Object>> workerOffline(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                          @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                          @PathVariable String workerId,
                                                          @RequestBody(required = false) ExternalWorkerPresenceApiRequest requestBody) {
        validatePresenceRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_OFFLINE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "offline");
        workerClient.workerOffline(boundWorkerId, presenceSessionToken(requestBody), requestBody.getReason());
        return ApiResponse.success(presenceResponse(
                boundWorkerId,
                "offline",
                WorkerTransportHints.POLLING));
    }

    @PostMapping("/workers/{workerId}:poll")
    @Operation(summary = "Poll task dispatch items", description = "Returns dispatch-ready task items for polling workers. Realtime workers must use their transport adapter.")
    public ApiResponse<Map<String, Object>> pollTasks(@RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
                                                      @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
                                                      @PathVariable String workerId,
                                                      @RequestBody(required = false) ExternalWorkerPollApiRequest requestBody) {
        validatePollRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_POLL, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "poll");
        int maxMessages = requestBody == null || requestBody.getMaxMessages() == null ? 1 : requestBody.getMaxMessages();
        long timeoutMs = requestBody == null || requestBody.getTimeoutMs() == null ? 0L : requestBody.getTimeoutMs();
        List<WorkerInvocation> items = workerClient.pollTasks(boundWorkerId, maxMessages, timeoutMs);
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
                                                         @RequestBody WorkerResultSubmissionRequest requestBody) {
        validateResultRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_SUBMIT_RESULT, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "submitResult");
        boolean submitted = workerClient.submitResult(boundWorkerId, new WorkerResultSubmission(
                requireNonBlank(requestBody.getResultCorrelationRef(), "resultCorrelationRef"),
                requestBody.isSuccess(),
                blankToNull(requestBody.getResultCode()),
                blankToNull(requestBody.getResult())
        ));
        return ApiResponse.success(Map.of(
                "workerId", boundWorkerId,
                "resultCorrelationRef", requestBody.getResultCorrelationRef().trim(),
                "submitted", submitted
            ));
    }

    @PostMapping("/workers/{workerId}/commands:poll")
    @Operation(summary = "Poll worker commands", description = "Returns owner-backed worker commands for polling workers.")
    public ApiResponse<Map<String, Object>> pollWorkerCommands(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @RequestBody(required = false) ExternalWorkerCommandPollApiRequest requestBody) {
        validateCommandPollRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_POLL, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "pollCommand");
        int maxCommands = requestBody == null || requestBody.getMaxCommands() == null
                ? 10
                : requestBody.getMaxCommands();
        List<WorkerCommandSnapshot> commands = requireWorkerControl().pullWorkerCommands(boundWorkerId, maxCommands);
        return ApiResponse.success(Map.of(
                "workerId", boundWorkerId,
                "commands", commands,
                "count", commands.size()
        ));
    }

    @PostMapping("/workers/{workerId}:report-handler-evidence")
    @Operation(summary = "Report worker handler evidence", description = "Reports a polling worker handler evidence snapshot through the owner-backed worker control surface.")
    public ApiResponse<Map<String, Object>> reportWorkerHandlerEvidence(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @RequestBody ExternalWorkerHandlerEvidenceApiRequest requestBody) {
        validateHandlerEvidenceRequest(requestBody);
        List<WorkerEventBinding> eventBindings = toCapabilityEventBindings(requestBody.getEventCodes());
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_REPORT_HANDLER_EVIDENCE, workerId, null, eventBindings);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "reportHandlerEvidence");
        requireWorkerEventScope(workerPrincipal, requestBody.getEventCodes());
        long evidenceVersion = resolveOptionalVersion(requestBody.getEvidenceVersion(), "evidenceVersion");
        WorkerCapabilityReportSnapshot snapshot = requireWorkerControl().reportWorkerCapability(
                new WorkerCapabilityReportRequest(
                        resolveWorkerId(workerId, requestBody.getWorkerId()),
                        evidenceVersion,
                        requestBody.getEventCodes(),
                        requestBody.getAttributes(),
                        blankToNull(requestBody.getAgentVersion())
                )
        );
        return ApiResponse.success(toHandlerEvidenceResponse(snapshot));
    }

    @PostMapping("/workers/{workerId}:report-runtime-evidence")
    @Operation(summary = "Report worker runtime evidence", description = "Reports a polling worker bounded runtime evidence snapshot through the owner-backed worker control surface.")
    public ApiResponse<Map<String, Object>> reportWorkerRuntimeEvidence(
            @RequestHeader(value = SdkCredentialAuthSupport.API_KEY_HEADER, required = false) String apiKeyHeader,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @PathVariable String workerId,
            @RequestBody ExternalWorkerRuntimeEvidenceApiRequest requestBody) {
        validateRuntimeEvidenceRequest(requestBody);
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_REPORT_RUNTIME_EVIDENCE, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
        requirePollingWorker(boundWorkerId, "reportRuntimeEvidence");
        long evidenceVersion = resolveOptionalVersion(requestBody.getEvidenceVersion(), "evidenceVersion");
        WorkerStateReportSnapshot snapshot = requireWorkerControl().reportWorkerState(
                new WorkerStateReportRequest(
                        resolveWorkerId(workerId, requestBody.getWorkerId()),
                        evidenceVersion,
                        normalizeExternalWorkerState(requestBody.getState()),
                        blankToNull(requestBody.getReason()),
                        requestBody.getObservedAt(),
                        requestBody.getAttributes()
                )
        );
        return ApiResponse.success(toRuntimeEvidenceResponse(snapshot));
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
        PrincipalContext workerPrincipal = requireAuthorizedWorkerCredential(
                apiKeyHeader, authorizationHeader, ApiSecurityScenario.WORKER_ACK_COMMAND, workerId, null, null);
        String boundWorkerId = requireBoundWorkerId(workerPrincipal, workerId);
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
        requireNonBlank(requestBody.getWorkerGroupId(), "workerGroupId");
    }

    private void validateAdapterNodeRegisterRequest(ExternalAdapterNodeRegisterApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("adapter node register request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported adapter node register fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getAdapterNodeId(), "adapterNodeId");
    }

    private void validateNodeGroupBindingRequest(ExternalNodeGroupBindingApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("node group binding request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported node group binding fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getAdapterNodeId(), "adapterNodeId");
        requireNonBlank(requestBody.getWorkerGroupId(), "workerGroupId");
    }

    private void validateWorkerGroupDeclareRequest(ExternalWorkerGroupDeclareApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker group declare request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker group declare fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getGroupId(), "groupId");
        if (requestBody.getEventBindings() == null || requestBody.getEventBindings().isEmpty()) {
            throw new IllegalArgumentException("eventBindings is required");
        }
        if (requestBody.getDefaultMaxConcurrentWork() != null && requestBody.getDefaultMaxConcurrentWork() <= 0) {
            throw new IllegalArgumentException("defaultMaxConcurrentWork must be greater than 0");
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

    private void validatePresenceRequest(ExternalWorkerPresenceApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker presence request body is required");
        }
        if (requestBody != null && requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker presence fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getSessionToken(), "sessionToken");
    }

    private String presenceSessionToken(ExternalWorkerPresenceApiRequest requestBody) {
        return requireNonBlank(requestBody.getSessionToken(), "sessionToken");
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

    private void validateResultRequest(WorkerResultSubmissionRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker result request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker result fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateHandlerEvidenceRequest(ExternalWorkerHandlerEvidenceApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker handler evidence request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker handler evidence fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private void validateRuntimeEvidenceRequest(ExternalWorkerRuntimeEvidenceApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker runtime evidence request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker runtime evidence fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        requireNonBlank(requestBody.getState(), "state");
    }

    private Map<String, Object> toHandlerEvidenceResponse(WorkerCapabilityReportSnapshot snapshot) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", snapshot.status());
        response.put("workerId", snapshot.workerId());
        response.put("evidenceVersion", snapshot.capabilityVersion());
        response.put("accepted", snapshot.accepted());
        response.put("changed", snapshot.snapshotChanged());
        response.put("reason", snapshot.reason());
        return response;
    }

    private Map<String, Object> toRuntimeEvidenceResponse(WorkerStateReportSnapshot snapshot) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", snapshot.status());
        response.put("workerId", snapshot.workerId());
        response.put("evidenceVersion", snapshot.stateVersion());
        response.put("accepted", snapshot.accepted());
        response.put("changed", snapshot.projectionChanged());
        response.put("reason", snapshot.reason());
        if (snapshot.projection() != null) {
            Map<String, Object> projection = new LinkedHashMap<>();
            projection.put("workerId", snapshot.projection().workerId());
            projection.put("evidenceVersion", snapshot.projection().stateVersion());
            projection.put("state", snapshot.projection().state());
            projection.put("reason", snapshot.projection().reason());
            projection.put("observedAt", snapshot.projection().observedAt());
            projection.put("acceptedAt", snapshot.projection().acceptedAt());
            response.put("snapshot", projection);
        }
        return response;
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

    private void validateCommandPollRequest(ExternalWorkerCommandPollApiRequest requestBody) {
        if (requestBody != null && requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker command poll fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        if (requestBody != null && requestBody.getMaxCommands() != null && requestBody.getMaxCommands() <= 0) {
            throw new IllegalArgumentException("maxCommands must be greater than 0");
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
        String transportHint = workerClient.getWorkerTransportHint(normalizedWorkerId);
        if (WorkerTransportHints.isPolling(transportHint)) {
            return;
        }
        throw new IllegalStateException("External worker API " + operation
                + " only supports polling workers; worker "
                + normalizedWorkerId + " uses transport '" + transportHint + "'");
    }

    private List<WorkerEventBinding> toEventBindings(List<ExternalWorkerEventBindingApiRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
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
                        .eventCode(requireNonBlank(eventCode, "eventCodes"))
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
            String normalizedEventCode = requireNonBlank(eventCode, "eventCodes");
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

    private String requireBoundWorkerId(PrincipalContext workerPrincipal, String requestedWorkerId) {
        String normalizedWorkerId = requireNonBlank(requestedWorkerId, "workerId");
        if (workerPrincipal == null || workerPrincipal.getAttributes() == null) {
            return normalizedWorkerId;
        }
        String boundWorkerId = blankToNull(workerPrincipal.getAttributes().get(WORKER_ID_BINDING_ATTRIBUTE));
        if (boundWorkerId != null && !boundWorkerId.equals(normalizedWorkerId)) {
            throw new ApiForbiddenException("Worker credential binding denied: " + normalizedWorkerId);
        }
        return normalizedWorkerId;
    }

    private PrincipalContext requireAuthorizedWorkerCredential(String apiKeyHeader,
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

    private void observeRegistration(String resourceType,
                                     String resourceId,
                                     String action,
                                     PrincipalContext principal,
                                     Map<String, Object> payload) {
        if (registrationObservationService == null) {
            return;
        }
        registrationObservationService.observeSuccessfulRegistration(
                resourceType,
                resourceId,
                action,
                principal,
                payload == null ? Map.of() : new LinkedHashMap<>(payload)
        );
    }

    private Map<String, Object> presenceResponse(String workerId,
                                                String action,
                                                String transportHint) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workerId", requireNonBlank(workerId, "workerId"));
        response.put("action", action);
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
