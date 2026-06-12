package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollResult;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollStatus;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Pull-based worker adapter for crawlers, queue consumers, and other workers
 * that do not maintain a server-push transport.
 */
public class PollingWorkerAdapter implements WorkerAdapter, TaskPullChannel {

    private static final Logger logger = LoggerFactory.getLogger(PollingWorkerAdapter.class);

    /** Maximum items held per worker inbox before new dispatches are dropped. */
    static final int MAX_INBOX_SIZE = 10_000;

    public static final String PROTOCOL = "polling";

    private final TransportRouteOwnerStore routeOwnerStore;
    private final TransportDeliveryService deliveryService;

    public PollingWorkerAdapter(TransportRouteOwnerStore routeOwnerStore,
                                TransportDeliveryService deliveryService) {
        this.routeOwnerStore = Objects.requireNonNull(routeOwnerStore, "routeOwnerStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = deliveryService.enqueue(PROTOCOL, envelopes);
        for (DispatchOutcome outcome : outcomes) {
            if (outcome.isRetryable()) {
                logger.warn("Polling delivery rejected: routeKey={}, deliveryId={}, attemptId={}, status={}, reason={}",
                        outcome.getRouteKey(), outcome.getDeliveryId(), outcome.getAttemptId(),
                        outcome.getStatus(), outcome.getReason());
            }
        }
        return outcomes;
    }

    @Override
    public TaskPullResult pollTaskMessagesResult(String selectedWorkerId, int maxMessages, long timeoutMillis) {
        if (selectedWorkerId == null || selectedWorkerId.isBlank() || maxMessages <= 0) {
            return TaskPullResult.invalidRequest();
        }
        TransportDeliveryPollResult result = deliveryService.pollEnvelopeResult(
                PROTOCOL,
                selectedWorkerId,
                maxMessages,
                timeoutMillis
        );
        return TaskPullResult.of(mapStatus(result.getStatus()), toPulledItems(result.getEnvelopes()));
    }

    public void announceWorkerOnline(String workerId, String routeKey, String connectionId, String reason) {
        routeOwnerStore.claimRouteOwner(workerId, PROTOCOL, routeKey, connectionId, reason);
    }

    public void announceWorkerOffline(String workerId, String routeKey, String connectionId, String reason) {
        routeOwnerStore.releaseRouteOwner(workerId, PROTOCOL, routeKey, connectionId, reason);
    }

    public void refreshRouteOwnerHeartbeat(String workerId, String routeKey, String connectionId, String reason) {
        routeOwnerStore.refreshHeartbeat(workerId, PROTOCOL, routeKey, connectionId, reason);
    }

    private static TaskPullStatus mapStatus(TransportDeliveryPollStatus status) {
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

    private static List<PulledTaskDispatch> toPulledItems(List<TransportDispatchEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return List.of();
        }
        return envelopes.stream()
                .map(PollingWorkerAdapter::toPulledItem)
                .toList();
    }

    private static PulledTaskDispatch toPulledItem(TransportDispatchEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        TransportPacket packet = envelope.getPacket();
        return new PulledTaskDispatch(
                packet.taskId(),
                packet.messageId(),
                packet.eventCode(),
                packet.payloadObject(TransportPacket.PAYLOAD_INPUT),
                packet.payloadObject(TransportPacket.PAYLOAD_SHARED_CONFIG),
                packet.attemptId(),
                packet.payloadInt(TransportPacket.PAYLOAD_ATTEMPT_NO),
                packet.payloadInt(TransportPacket.PAYLOAD_RETRY_COUNT),
                packet.payloadString(TransportPacket.PAYLOAD_BATCH_ID)
        );
    }

}
