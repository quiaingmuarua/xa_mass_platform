package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.lease.TransportEndpointLeaseClaim;
import com.xa.mass.transport.lease.TransportEndpointLeaseConsumerEvidence;
import com.xa.mass.transport.lease.TransportEndpointLeaseHeartbeat;
import com.xa.mass.transport.lease.TransportEndpointLeaseRelease;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerClaim;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
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
public class PollingWorkerAdapter implements WorkerAdapter, DeliveryPullChannel {

    private static final Logger logger = LoggerFactory.getLogger(PollingWorkerAdapter.class);

    /** Maximum items held per worker inbox before new dispatches are dropped. */
    static final int MAX_INBOX_SIZE = 10_000;

    public static final String PROTOCOL = "polling";
    public static final String DEFAULT_ADAPTER_ID = "polling-default";

    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final TransportDeliveryService deliveryService;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final String adapterId;

    public PollingWorkerAdapter(TransportEndpointLeaseStore endpointLeaseStore,
                                TransportDeliveryService deliveryService) {
        this(endpointLeaseStore,
                deliveryService,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                DEFAULT_ADAPTER_ID);
    }

    public PollingWorkerAdapter(TransportEndpointLeaseStore endpointLeaseStore,
                                TransportDeliveryService deliveryService,
                                DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                String adapterId) {
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.adapterId = requireText(adapterId, "adapterId");
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public String adapterId() {
        return adapterId;
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
        TransportEndpointLeaseClaim claim = endpointLeaseClaim(workerId, deliveryBucketId, routeKey, connectionId, reason);
        claimDeliveryConsumer(endpointLeaseStore.claimEndpointLease(claim));
    }

    public void announceWorkerOffline(String workerId,
                                      String deliveryBucketId,
                                      String routeKey,
                                      String connectionId,
                                      String reason) {
        TransportEndpointLeaseRelease claim = endpointLeaseRelease(workerId, deliveryBucketId, routeKey, connectionId, reason);
        endpointLeaseStore.releaseEndpointLease(claim);
        releaseDeliveryConsumer(claim);
    }

    public void refreshEndpointLeaseHeartbeat(String workerId,
                                              String deliveryBucketId,
                                              String routeKey,
                                              String connectionId,
                                              String reason) {
        TransportEndpointLeaseHeartbeat heartbeat =
                endpointLeaseHeartbeat(workerId, deliveryBucketId, routeKey, connectionId, reason);
        endpointLeaseStore.refreshEndpointLease(heartbeat).ifPresentOrElse(
                this::claimDeliveryConsumer,
                () -> releaseDeliveryConsumer(heartbeat)
        );
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

    private void claimDeliveryConsumer(TransportEndpointLeaseConsumerEvidence evidence) {
        deliveryCommandConsumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                evidence.deliveryBucketId(),
                evidence.workerId(),
                evidence.endpointLeaseId(),
                evidence.leaseExpireAtEpochMillis()
        ));
    }

    private void releaseDeliveryConsumer(TransportEndpointLeaseRelease claim) {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                claim.deliveryBucketId(),
                claim.workerId(),
                claim.endpointLeaseId(),
                0L
        ));
    }

    private void releaseDeliveryConsumer(TransportEndpointLeaseHeartbeat heartbeat) {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                heartbeat.deliveryBucketId(),
                heartbeat.workerId(),
                heartbeat.endpointLeaseId(),
                0L
        ));
    }

    private TransportEndpointLeaseClaim endpointLeaseClaim(String workerId,
                                                           String deliveryBucketId,
                                                           String routeKey,
                                                           String connectionId,
                                                           String reason) {
        return new TransportEndpointLeaseClaim(workerId, deliveryBucketId, adapterId, routeKey, connectionId, reason);
    }

    private TransportEndpointLeaseHeartbeat endpointLeaseHeartbeat(String workerId,
                                                                   String deliveryBucketId,
                                                                   String routeKey,
                                                                   String connectionId,
                                                                   String reason) {
        return new TransportEndpointLeaseHeartbeat(workerId, deliveryBucketId, adapterId, routeKey, connectionId, reason);
    }

    private TransportEndpointLeaseRelease endpointLeaseRelease(String workerId,
                                                               String deliveryBucketId,
                                                               String routeKey,
                                                               String connectionId,
                                                               String reason) {
        return new TransportEndpointLeaseRelease(workerId, deliveryBucketId, adapterId, routeKey, connectionId, reason);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

}
