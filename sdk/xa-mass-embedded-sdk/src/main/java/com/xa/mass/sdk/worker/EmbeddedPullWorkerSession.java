package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.starter.TaskResultCallbackCodec;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Embedded SDK pull worker session handle.
 *
 * <p>This is an in-process MassApplication facade over the resolved polling
 * transport. External Java worker processes use xa-mass-java-sdk runtimes
 * instead of this embedded runtime handle.</p>
 */
public final class EmbeddedPullWorkerSession {

    private final String workerId;
    private final String workerGroupId;
    private final String endpointAddress;
    private final String sessionToken;
    private final DeliveryPullChannel deliveryPullChannel;
    private final WorkerInvocationPayloadDecoder payloadDecoder;
    private final TransportResultIngressChannel resultIngressChannel;
    private final TaskResultCallbackCodec resultCallbackCodec;
    private final PullSessionEvidenceDriver evidenceDriver;
    private final String transportHint;

    EmbeddedPullWorkerSession(String workerId,
                              String workerGroupId,
                              String sessionToken,
                              DeliveryPullChannel deliveryPullChannel,
                              TransportResultIngressChannel resultIngressChannel,
                              PullSessionEvidenceDriver evidenceDriver,
                              String transportHint) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId.trim();
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.endpointAddress = CanonicalWorkerGroupRouteKeyCodec.encode(this.workerGroupId);
        this.sessionToken = requireText(sessionToken, "sessionToken");
        this.deliveryPullChannel = Objects.requireNonNull(deliveryPullChannel, "deliveryPullChannel");
        this.payloadDecoder = new WorkerInvocationPayloadDecoder();
        this.resultIngressChannel = Objects.requireNonNull(resultIngressChannel, "resultIngressChannel");
        this.resultCallbackCodec = new TaskResultCallbackCodec();
        this.evidenceDriver = Objects.requireNonNull(evidenceDriver, "evidenceDriver");
        this.transportHint = transportHint;
    }

    public String workerId() {
        return workerId;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public String transportHint() {
        return transportHint;
    }

    public void connect() {
        connect("pull-session-connect");
    }

    public void connect(String reason) {
        connectAndClaim(reason);
    }

    public boolean connectAndClaim(String reason) {
        return evidenceDriver.connect(
                workerId,
                workerGroupId,
                endpointAddress,
                sessionToken,
                normalizeReason(reason, "pull-session-connect")
        );
    }

    public void disconnect() {
        disconnect("pull-session-disconnect");
    }

    public void disconnect(String reason) {
        disconnectIfCurrent(reason);
    }

    public boolean disconnectIfCurrent(String reason) {
        return evidenceDriver.disconnect(
                workerId,
                workerGroupId,
                endpointAddress,
                sessionToken,
                normalizeReason(reason, "pull-session-disconnect")
        );
    }

    public void heartbeat() {
        heartbeat("pull-session-heartbeat");
    }

    public void heartbeat(String reason) {
        refreshHeartbeatIfCurrent(reason);
    }

    public boolean refreshHeartbeatIfCurrent(String reason) {
        return evidenceDriver.heartbeat(
                workerId,
                workerGroupId,
                endpointAddress,
                sessionToken,
                normalizeReason(reason, "pull-session-heartbeat")
        );
    }

    public List<WorkerInvocation> poll(int maxMessages) {
        return poll(maxMessages, 0L);
    }

    public List<WorkerInvocation> poll(int maxMessages, long timeoutMillis) {
        return pollResult(maxMessages, timeoutMillis).getItems();
    }

    public WorkerPollResult pollResult(int maxMessages) {
        return pollResult(maxMessages, 0L);
    }

    public WorkerPollResult pollResult(int maxMessages, long timeoutMillis) {
        DeliveryPullResult result = deliveryPullChannel.pollDeliveryMessagesResult(
                workerGroupId,
                workerId,
                maxMessages,
                timeoutMillis
        );
        return WorkerPollResult.of(mapStatus(result.getStatus()), result.getItems().stream()
                .map(payloadDecoder::decode)
                .toList());
    }

    public boolean submitResult(WorkerInvocation item, boolean success, String result) {
        Objects.requireNonNull(item, "item");
        return submitResult(item, success, null, result);
    }

    public boolean submitResult(WorkerInvocation item,
                                boolean success,
                                String resultCode,
                                String result) {
        Objects.requireNonNull(item, "item");
        WorkerResultSubmission request = new WorkerResultSubmission(
                item.getResultCorrelationRef(),
                success,
                resultCode,
                result
        );
        return submitResult(request);
    }

    public boolean submitResult(WorkerResultSubmission request) {
        Objects.requireNonNull(request, "request");
        return resultIngressChannel.ingest(resultCallbackCodec.toEnvelope(
                request,
                request.resultCorrelationRef(),
                Map.of()
        ));
    }

    public boolean submitResult(String resultCorrelationRef,
                                boolean success,
                                String resultCode,
                                String result) {
        return submitResult(WorkerResultSubmission.of(
                resultCorrelationRef,
                success,
                resultCode,
                result
        ));
    }

    private String normalizeReason(String reason, String defaultReason) {
        return reason == null || reason.isBlank() ? defaultReason : reason.trim();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static WorkerPollStatus mapStatus(DeliveryPullStatus status) {
        if (status == null) {
            return WorkerPollStatus.UNAVAILABLE;
        }
        return switch (status) {
            case DELIVERED -> WorkerPollStatus.DELIVERED;
            case EMPTY -> WorkerPollStatus.EMPTY;
            case INVALID_REQUEST -> WorkerPollStatus.INVALID_REQUEST;
            case UNAVAILABLE -> WorkerPollStatus.UNAVAILABLE;
            case SHUTDOWN -> WorkerPollStatus.SHUTDOWN;
        };
    }
}
