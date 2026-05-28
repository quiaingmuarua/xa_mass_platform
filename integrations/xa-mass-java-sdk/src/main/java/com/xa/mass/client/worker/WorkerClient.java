package com.xa.mass.client.worker;

import com.xa.mass.client.http.MassHttpClient;

import java.util.Map;
import java.util.Objects;

public final class WorkerClient {
    private final MassHttpClient httpClient;

    public WorkerClient(MassHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
    }

    public AdapterNodeRegistrationResult registerAdapterNode(AdapterNodeSpec request) {
        return httpClient.post("/worker-api/v1/adapter-nodes",
                Objects.requireNonNull(request, "request is required"),
                AdapterNodeRegistrationResult.class);
    }

    public WorkerGroupDeclarationResult declareGroup(WorkerGroupSpec request) {
        return httpClient.post("/worker-api/v1/worker-groups",
                Objects.requireNonNull(request, "request is required"),
                WorkerGroupDeclarationResult.class);
    }

    public NodeGroupBindingResult bindNodeGroup(NodeGroupBindingSpec request) {
        return httpClient.post("/worker-api/v1/node-group-bindings",
                Objects.requireNonNull(request, "request is required"),
                NodeGroupBindingResult.class);
    }

    public NodeGroupBindingResult bindNodeGroup(String adapterNodeId, String workerGroupId) {
        return bindNodeGroup(NodeGroupBindingSpec.builder()
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .build());
    }

    public WorkerRegistrationResult registerWorker(WorkerSpec request) {
        return httpClient.post("/worker-api/v1/workers",
                Objects.requireNonNull(request, "request is required"),
                WorkerRegistrationResult.class);
    }

    public WorkerPresenceResult online(String workerId, String reason) {
        return presence(workerId, "online", reason);
    }

    public WorkerPresenceResult heartbeat(String workerId, String reason) {
        return presence(workerId, "heartbeat", reason);
    }

    public WorkerPresenceResult offline(String workerId, String reason) {
        return presence(workerId, "offline", reason);
    }

    public WorkerPollResult poll(String workerId, WorkerPollRequest request) {
        WorkerPollRequest resolved = request == null ? WorkerPollRequest.builder().build() : request;
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + ":poll",
                resolved, WorkerPollResult.class);
    }

    public WorkerResultSubmitOutcome submitResult(String workerId, WorkerResultSubmitRequest request) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + ":submit-result",
                Objects.requireNonNull(request, "request is required"),
                WorkerResultSubmitOutcome.class);
    }

    public WorkerCommandPollResult pollCommands(String workerId, WorkerCommandPollRequest request) {
        WorkerCommandPollRequest resolved = request == null ? WorkerCommandPollRequest.builder().build() : request;
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + "/commands:poll",
                resolved, WorkerCommandPollResult.class);
    }

    public WorkerCommandAckResult ackCommand(String workerId, String commandId, WorkerCommandAck request) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId)
                        + "/commands/" + WorkerRequestSupport.encode(commandId) + ":ack",
                Objects.requireNonNull(request, "request is required"),
                WorkerCommandAckResult.class);
    }

    public WorkerCapabilityReportResult reportCapability(String workerId, WorkerCapabilityReport request) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId)
                        + ":report-capability",
                Objects.requireNonNull(request, "request is required"),
                WorkerCapabilityReportResult.class);
    }

    public WorkerStateReportResult reportState(String workerId, WorkerStateReport request) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId)
                        + ":report-state",
                Objects.requireNonNull(request, "request is required"),
                WorkerStateReportResult.class);
    }

    private WorkerPresenceResult presence(String workerId, String action, String reason) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + ":" + action,
                reason == null || reason.isBlank() ? Map.of() : Map.of("reason", reason),
                WorkerPresenceResult.class);
    }
}
