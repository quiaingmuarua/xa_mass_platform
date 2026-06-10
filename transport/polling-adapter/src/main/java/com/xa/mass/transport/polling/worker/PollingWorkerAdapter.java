package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.presence.WorkerPresenceStore;
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

    private final WorkerSystemEventChannel systemEventChannel;
    private final WorkerPresenceStore workerPresenceStore;
    private final TransportDeliveryService deliveryService;

    public PollingWorkerAdapter(WorkerSystemEventChannel systemEventChannel,
                                WorkerPresenceStore workerPresenceStore,
                                TransportDeliveryService deliveryService) {
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
        this.workerPresenceStore = Objects.requireNonNull(workerPresenceStore, "workerPresenceStore");
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
        List<DispatchOutcome> outcomes = deliveryService.enqueue(envelopes);
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
    public TaskPullResult pollTaskMessagesResult(String routeKey, int maxMessages, long timeoutMillis) {
        if (routeKey == null || routeKey.isBlank() || maxMessages <= 0) {
            return TaskPullResult.invalidRequest();
        }
        TransportDeliveryPollResult result = deliveryService.pollEnvelopeResult(PROTOCOL, routeKey, maxMessages, timeoutMillis);
        return TaskPullResult.of(mapStatus(result.getStatus()), TransportDeliveryService.toDispatchViews(result.getEnvelopes()));
    }

    public void announceWorkerOnline(String workerId, String routeKey, String connectionId, String reason) {
        workerPresenceStore.markOnline(workerId, PROTOCOL, routeKey, connectionId, reason);
        systemEventChannel.publishWorkerOnline(workerId, reason, connectionId);
    }

    public void announceWorkerOffline(String workerId, String routeKey, String connectionId, String reason) {
        workerPresenceStore.markOffline(workerId, PROTOCOL, routeKey, connectionId, reason);
        systemEventChannel.publishWorkerOffline(workerId, reason, connectionId);
    }

    public void publishWorkerHeartbeat(String workerId, String routeKey, String connectionId, String reason) {
        workerPresenceStore.refreshHeartbeat(workerId, PROTOCOL, routeKey, connectionId, reason);
        systemEventChannel.publishWorkerHeartbeat(workerId, reason, connectionId);
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

}
