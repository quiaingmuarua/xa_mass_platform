package com.xa.mass.client.worker;

import com.xa.mass.client.http.MassHttpClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerClient {
    private final MassHttpClient httpClient;

    public WorkerClient(MassHttpClient httpClient) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
    }

    public WorkerGroupDeclarationResult declareGroup(WorkerGroupSpec request) {
        return httpClient.post("/worker-api/v1/worker-groups",
                Objects.requireNonNull(request, "request is required"),
                WorkerGroupDeclarationResult.class);
    }

    public WorkerRegistrationResult registerWorker(WorkerSpec request) {
        return httpClient.post("/worker-api/v1/workers",
                Objects.requireNonNull(request, "request is required"),
                WorkerRegistrationResult.class);
    }

    public WorkerPresenceResult online(String workerId, String sessionToken, String reason) {
        return presence(workerId, "online", sessionToken, reason);
    }

    public WorkerPresenceResult heartbeat(String workerId, String sessionToken, String reason) {
        return presence(workerId, "heartbeat", sessionToken, reason);
    }

    public WorkerPresenceResult offline(String workerId, String sessionToken, String reason) {
        return presence(workerId, "offline", sessionToken, reason);
    }

    public WorkerPollResult poll(String workerId, WorkerPollRequest request) {
        WorkerPollRequest resolved = request == null ? WorkerPollRequest.builder().build() : request;
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + ":poll",
                resolved, WorkerPollResult.class);
    }

    public boolean submitActionReply(String workerId, WorkerActionReply request) {
        Map<?, ?> response = httpClient.post(
                "/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + ":submit-result",
                Objects.requireNonNull(request, "request is required"),
                Map.class);
        Object submitted = response == null ? null : response.get("submitted");
        return !(submitted instanceof Boolean value) || value;
    }

    public WorkerHandlerEvidenceResult reportHandlerEvidence(String workerId, WorkerHandlerEvidence request) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId)
                        + ":report-handler-evidence",
                Objects.requireNonNull(request, "request is required"),
                WorkerHandlerEvidenceResult.class);
    }

    public WorkerRuntimeEvidenceResult reportRuntimeEvidence(String workerId, WorkerRuntimeEvidence request) {
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId)
                        + ":report-runtime-evidence",
                Objects.requireNonNull(request, "request is required"),
                WorkerRuntimeEvidenceResult.class);
    }

    private WorkerPresenceResult presence(String workerId, String action, String sessionToken, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sessionToken", requireText(sessionToken, "sessionToken"));
        if (reason != null && !reason.isBlank()) {
            request.put("reason", reason.trim());
        }
        return httpClient.post("/worker-api/v1/workers/" + WorkerRequestSupport.encode(workerId) + ":" + action,
                request,
                WorkerPresenceResult.class);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
