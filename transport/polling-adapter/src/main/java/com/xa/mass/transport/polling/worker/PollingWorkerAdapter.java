package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
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
    private final TransportDeliveryService deliveryService;

    public PollingWorkerAdapter(WorkerSystemEventChannel systemEventChannel,
                                TransportDeliveryService deliveryService) {
        this.systemEventChannel = Objects.requireNonNull(systemEventChannel, "systemEventChannel");
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
    public List<TaskDispatchItem> pollTaskMessages(String workerId, int maxMessages, long timeoutMillis) {
        if (workerId == null || workerId.isBlank() || maxMessages <= 0) {
            return List.of();
        }
        return deliveryService.pollEnvelopes(PROTOCOL, workerId, maxMessages, timeoutMillis).stream()
                .map(TransportDeliveryService::toDispatchItem)
                .toList();
    }

    public void announceWorkerOnline(String workerId, String reason) {
        systemEventChannel.publishWorkerOnline(workerId, reason, workerId);
    }

    public void announceWorkerOffline(String workerId, String reason) {
        systemEventChannel.publishWorkerOffline(workerId, reason, workerId);
    }

    public void publishWorkerHeartbeat(String workerId, String reason) {
        systemEventChannel.publishWorkerHeartbeat(workerId, reason, workerId);
    }

}
