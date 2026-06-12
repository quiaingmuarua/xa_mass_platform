package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.delivery.QueuedPulledDispatch;
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
    public List<DispatchOutcome> dispatch(List<AdapterDispatchRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = deliveryService.enqueue(PROTOCOL, requests);
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
        TransportDeliveryPollResult result = deliveryService.pollItemResult(
                PROTOCOL,
                selectedWorkerId,
                maxMessages,
                timeoutMillis
        );
        return TaskPullResult.of(mapStatus(result.getStatus()), toPulledItems(result.getItems()));
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

    private static List<PulledTaskDispatch> toPulledItems(List<QueuedPulledDispatch> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(QueuedPulledDispatch::toPulledTaskDispatch)
                .toList();
    }

}
