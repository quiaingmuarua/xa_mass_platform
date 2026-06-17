package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.QueuedPulledDispatch;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollResult;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollStatus;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.lease.TransportEndpointLeasePublisher;
import com.xa.mass.transport.worker.AdapterCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Pull-based worker adapter for crawlers, queue consumers, and other workers
 * that do not maintain a server-push transport.
 */
public class PollingWorkerAdapter implements AdapterCommandExecutor, DeliveryPullChannel {

    private static final Logger logger = LoggerFactory.getLogger(PollingWorkerAdapter.class);

    /** Maximum items held per worker inbox before new dispatches are dropped. */
    static final int MAX_INBOX_SIZE = 10_000;

    private final TransportDeliveryService deliveryService;
    private final TransportEndpointLeasePublisher endpointLeasePublisher;
    private final String adapterId;

    public PollingWorkerAdapter(TransportEndpointLeaseStore endpointLeaseStore,
                                TransportDeliveryService deliveryService,
                                DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                String adapterId) {
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.adapterId = requireText(adapterId, "adapterId");
        this.endpointLeasePublisher = new TransportEndpointLeasePublisher(this.adapterId);
        this.endpointLeasePublisher.setEndpointLeaseStore(Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore"));
        this.endpointLeasePublisher.setDeliveryCommandConsumerRegistry(deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE);
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = deliveryService.enqueue(adapterId, commands);
        for (DispatchOutcome outcome : outcomes) {
            if (outcome.isRetryable()) {
                logger.warn("Polling delivery rejected: selectedWorkerId={}, deliveryId={}, status={}, reason={}",
                        outcome.getSelectedWorkerId(), outcome.getDeliveryId(),
                        outcome.getStatus(), outcome.getReason());
            }
        }
        return outcomes;
    }

    @Override
    public DeliveryPullResult pollDeliveryMessagesResult(String deliveryBucketId,
                                                         String selectedWorkerId,
                                                         int maxMessages,
                                                         long timeoutMillis) {
        if (deliveryBucketId == null || deliveryBucketId.isBlank()
                || selectedWorkerId == null || selectedWorkerId.isBlank()
                || maxMessages <= 0) {
            return DeliveryPullResult.invalidRequest();
        }
        TransportDeliveryPollResult result = deliveryService.pollItemResult(
                deliveryBucketId,
                selectedWorkerId,
                maxMessages,
                timeoutMillis
        );
        return DeliveryPullResult.of(mapStatus(result.getStatus()), toPulledItems(result.getItems()));
    }

    public void announceWorkerOnline(String workerId,
                                     String deliveryBucketId,
                                     String routeKey,
                                     String connectionId,
                                     String reason) {
        endpointLeasePublisher.claim(workerId, deliveryBucketId, routeKey, connectionId, reason);
    }

    public void announceWorkerOffline(String workerId,
                                      String deliveryBucketId,
                                      String routeKey,
                                      String connectionId,
                                      String reason) {
        endpointLeasePublisher.release(workerId, deliveryBucketId, routeKey, connectionId, reason);
    }

    public void refreshEndpointLeaseHeartbeat(String workerId,
                                              String deliveryBucketId,
                                              String routeKey,
                                              String connectionId,
                                              String reason) {
        endpointLeasePublisher.refresh(workerId, deliveryBucketId, routeKey, connectionId, reason);
    }

    private static DeliveryPullStatus mapStatus(TransportDeliveryPollStatus status) {
        if (status == null) {
            return DeliveryPullStatus.UNAVAILABLE;
        }
        return switch (status) {
            case DELIVERED -> DeliveryPullStatus.DELIVERED;
            case EMPTY -> DeliveryPullStatus.EMPTY;
            case INVALID_REQUEST -> DeliveryPullStatus.INVALID_REQUEST;
            case UNAVAILABLE -> DeliveryPullStatus.UNAVAILABLE;
            case SHUTDOWN -> DeliveryPullStatus.SHUTDOWN;
        };
    }

    private static List<PulledDeliveryMessage> toPulledItems(List<QueuedPulledDispatch> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(QueuedPulledDispatch::toPulledDeliveryMessage)
                .toList();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

}
