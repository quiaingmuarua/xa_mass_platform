package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.starter.TaskResultCallbackCodec;
import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseConsumerEvidence;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerClaim;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing pull worker session for crawlers, queue consumers, and other
 * executors that receive work by polling instead of server push.
 */
public class PullWorkerSession {

    private final String workerId;
    private final String workerGroupId;
    private final String adapterId;
    private final String routeKey;
    private final String connectionId;
    private final DeliveryPullChannel deliveryPullChannel;
    private final PulledTaskDispatchPayloadDecoder payloadDecoder;
    private final TransportResultIngressChannel resultIngressChannel;
    private final TaskResultCallbackCodec resultCallbackCodec;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final WorkerPresenceIngress workerPresenceIngress;
    private final String transportHint;

    PullWorkerSession(String workerId,
                      String workerGroupId,
                      String adapterId,
                      String connectionId,
                      DeliveryPullChannel deliveryPullChannel,
                      TransportResultIngressChannel resultIngressChannel,
                      TransportEndpointLeaseStore endpointLeaseStore,
                      WorkerPresenceIngress workerPresenceIngress,
                      String transportHint) {
        this(workerId,
                workerGroupId,
                adapterId,
                connectionId,
                deliveryPullChannel,
                resultIngressChannel,
                endpointLeaseStore,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                workerPresenceIngress,
                transportHint);
    }

    PullWorkerSession(String workerId,
                      String workerGroupId,
                      String adapterId,
                      String connectionId,
                      DeliveryPullChannel deliveryPullChannel,
                      TransportResultIngressChannel resultIngressChannel,
                      TransportEndpointLeaseStore endpointLeaseStore,
                      DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                      WorkerPresenceIngress workerPresenceIngress,
                      String transportHint) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId.trim();
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.routeKey = CanonicalWorkerGroupRouteKeyCodec.encode(this.workerGroupId);
        this.connectionId = requireText(connectionId, "connectionId");
        this.deliveryPullChannel = Objects.requireNonNull(deliveryPullChannel, "deliveryPullChannel");
        this.payloadDecoder = new PulledTaskDispatchPayloadDecoder();
        this.resultIngressChannel = Objects.requireNonNull(resultIngressChannel, "resultIngressChannel");
        this.resultCallbackCodec = new TaskResultCallbackCodec();
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.workerPresenceIngress = workerPresenceIngress != null
                ? workerPresenceIngress
                : NoopWorkerPresenceIngress.INSTANCE;
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
        String normalizedReason = normalizeReason(reason, "pull-session-connect");
        workerPresenceIngress.sessionConnected(WorkerSessionPresenceEvent.connected(
                workerId,
                adapterId,
                routeKey,
                connectionId,
                normalizedReason,
                connectionId
        ));
        TransportEndpointLeaseConsumerEvidence evidence =
                endpointLeaseStore.claimEndpointLease(endpointLeaseClaim(normalizedReason));
        claimDeliveryConsumer(evidence);
        return true;
    }

    public void disconnect() {
        disconnect("pull-session-disconnect");
    }

    public void disconnect(String reason) {
        disconnectIfCurrent(reason);
    }

    public boolean disconnectIfCurrent(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-disconnect");
        workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                workerId,
                adapterId,
                routeKey,
                connectionId,
                normalizedReason,
                connectionId
        ));
        boolean releasedCurrent = endpointLeaseStore.releaseEndpointLease(endpointLeaseRelease(normalizedReason));
        releaseDeliveryConsumer();
        return releasedCurrent;
    }

    public void heartbeat() {
        heartbeat("pull-session-heartbeat");
    }

    public void heartbeat(String reason) {
        refreshHeartbeatIfCurrent(reason);
    }

    public boolean refreshHeartbeatIfCurrent(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-heartbeat");
        workerPresenceIngress.sessionHeartbeat(WorkerSessionPresenceEvent.heartbeat(
                workerId,
                adapterId,
                routeKey,
                connectionId,
                normalizedReason,
                connectionId
        ));
        return endpointLeaseStore.refreshEndpointLease(endpointLeaseHeartbeat(normalizedReason))
                .map(evidence -> {
                    claimDeliveryConsumer(evidence);
                    return true;
                })
                .orElseGet(() -> {
                    releaseDeliveryConsumer();
                    return false;
                });
    }

    public List<PulledTaskDispatch> poll(int maxMessages) {
        return poll(maxMessages, 0L);
    }

    public List<PulledTaskDispatch> poll(int maxMessages, long timeoutMillis) {
        return pollResult(maxMessages, timeoutMillis).getItems();
    }

    public TaskPullResult pollResult(int maxMessages) {
        return pollResult(maxMessages, 0L);
    }

    public TaskPullResult pollResult(int maxMessages, long timeoutMillis) {
        DeliveryPullResult result = deliveryPullChannel.pollDeliveryMessagesResult(
                workerGroupId,
                workerId,
                maxMessages,
                timeoutMillis
        );
        return TaskPullResult.of(mapStatus(result.getStatus()), result.getItems().stream()
                .map(payloadDecoder::decode)
                .toList());
    }

    public boolean submitResult(PulledTaskDispatch item, boolean success, String detail) {
        Objects.requireNonNull(item, "item");
        return submitResult(item, success, detail, null, Map.of());
    }

    public boolean submitResult(PulledTaskDispatch item,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(item, "item");
        return submitResult(item, success, detail, null, output);
    }

    public boolean submitResult(PulledTaskDispatch item,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        Objects.requireNonNull(item, "item");
        WorkerResultSubmitRequest request = new WorkerResultSubmitRequest(
                item.getResultCorrelationRef(),
                success,
                detail,
                errorCode,
                output
        );
        return submitResult(request);
    }

    public boolean submitResult(WorkerResultSubmitRequest request) {
        Objects.requireNonNull(request, "request");
        return resultIngressChannel.ingest(resultCallbackCodec.toEnvelope(
                request,
                request.resultCorrelationRef(),
                diagnostics(null)
        ));
    }

    public boolean submitResult(String resultCorrelationRef,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        return submitResult(WorkerResultSubmitRequest.of(
                resultCorrelationRef,
                success,
                detail,
                errorCode,
                output
        ));
    }

    private Map<String, String> diagnostics(String traceId) {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        values.put("adapterId", adapterId);
        values.put("routeKey", routeKey);
        if (traceId != null && !traceId.isBlank()) {
            values.put("traceId", traceId);
        }
        return Map.copyOf(values);
    }

    private String normalizeReason(String reason, String defaultReason) {
        return reason == null || reason.isBlank() ? defaultReason : reason.trim();
    }

    private void claimDeliveryConsumer(TransportEndpointLeaseConsumerEvidence evidence) {
        deliveryCommandConsumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                evidence.deliveryBucketId(),
                evidence.workerId(),
                evidence.endpointLeaseId(),
                evidence.leaseExpireAtEpochMillis()
        ));
    }

    private void releaseDeliveryConsumer() {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                workerGroupId,
                workerId,
                connectionId,
                0L
        ));
    }

    private TransportEndpointLeaseClaim endpointLeaseClaim(String reason) {
        return new TransportEndpointLeaseClaim(
                workerId,
                workerGroupId,
                adapterId,
                routeKey,
                connectionId,
                reason
        );
    }

    private TransportEndpointLeaseHeartbeat endpointLeaseHeartbeat(String reason) {
        return new TransportEndpointLeaseHeartbeat(
                workerId,
                workerGroupId,
                adapterId,
                routeKey,
                connectionId,
                reason
        );
    }

    private TransportEndpointLeaseRelease endpointLeaseRelease(String reason) {
        return new TransportEndpointLeaseRelease(
                workerId,
                workerGroupId,
                adapterId,
                routeKey,
                connectionId,
                reason
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static TaskPullStatus mapStatus(DeliveryPullStatus status) {
        if (status == null) {
            return TaskPullStatus.UNAVAILABLE;
        }
        return switch (status) {
            case DELIVERED -> TaskPullStatus.DELIVERED;
            case EMPTY -> TaskPullStatus.EMPTY;
            case INVALID_REQUEST -> TaskPullStatus.INVALID_REQUEST;
            case UNAVAILABLE -> TaskPullStatus.UNAVAILABLE;
            case SHUTDOWN -> TaskPullStatus.SHUTDOWN;
        };
    }
}
