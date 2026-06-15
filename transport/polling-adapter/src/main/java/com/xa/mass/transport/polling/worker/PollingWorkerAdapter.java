package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
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
public class PollingWorkerAdapter implements WorkerAdapter, TaskPullChannel {

    private static final Logger logger = LoggerFactory.getLogger(PollingWorkerAdapter.class);

    /** Maximum items held per worker inbox before new dispatches are dropped. */
    static final int MAX_INBOX_SIZE = 10_000;

    public static final String PROTOCOL = "polling";

    private final TransportRouteOwnerStore routeOwnerStore;
    private final TransportDeliveryService deliveryService;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final String deliveryCommandConsumerKey;

    public PollingWorkerAdapter(TransportRouteOwnerStore routeOwnerStore,
                                TransportDeliveryService deliveryService) {
        this(routeOwnerStore,
                deliveryService,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                PROTOCOL);
    }

    public PollingWorkerAdapter(TransportRouteOwnerStore routeOwnerStore,
                                TransportDeliveryService deliveryService,
                                DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                String deliveryCommandConsumerKey) {
        this.routeOwnerStore = Objects.requireNonNull(routeOwnerStore, "routeOwnerStore");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.deliveryCommandConsumerKey = requireText(deliveryCommandConsumerKey, "deliveryCommandConsumerKey");
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
                logger.warn("Polling delivery rejected: selectedWorkerId={}, deliveryId={}, attemptId={}, status={}, reason={}",
                        outcome.getSelectedWorkerId(), outcome.getDeliveryId(), outcome.getAttemptId(),
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

    public void announceWorkerOnline(String workerId,
                                     String deliveryBucketId,
                                     String routeKey,
                                     String connectionId,
                                     String reason) {
        TransportRouteOwnerClaim claim = routeOwnerClaim(workerId, deliveryBucketId, routeKey, connectionId, reason);
        claimDeliveryConsumerIfCurrent(routeOwnerStore.claimRouteOwner(claim), claim);
    }

    public void announceWorkerOffline(String workerId,
                                      String deliveryBucketId,
                                      String routeKey,
                                      String connectionId,
                                      String reason) {
        TransportRouteOwnerClaim claim = routeOwnerClaim(workerId, deliveryBucketId, routeKey, connectionId, reason);
        routeOwnerStore.releaseRouteOwner(claim);
        releaseDeliveryConsumer(claim);
    }

    public void refreshRouteOwnerHeartbeat(String workerId,
                                           String deliveryBucketId,
                                           String routeKey,
                                           String connectionId,
                                           String reason) {
        TransportRouteOwnerClaim claim = routeOwnerClaim(workerId, deliveryBucketId, routeKey, connectionId, reason);
        TransportRouteOwnerRecord record = routeOwnerStore.refreshHeartbeat(claim);
        if (isCurrentOwner(record, claim)) {
            claimDeliveryConsumer(record);
        } else {
            releaseDeliveryConsumer(claim);
        }
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

    private void claimDeliveryConsumerIfCurrent(TransportRouteOwnerRecord record, TransportRouteOwnerClaim claim) {
        if (isCurrentOwner(record, claim)) {
            claimDeliveryConsumer(record);
        }
    }

    private void claimDeliveryConsumer(TransportRouteOwnerRecord record) {
        deliveryCommandConsumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                record.getDeliveryBucketId(),
                record.getWorkerId(),
                deliveryCommandConsumerKey,
                record.getConnectionId(),
                record.getAdapterId(),
                record.getLeaseExpireAtEpochMillis()
        ));
    }

    private void releaseDeliveryConsumer(TransportRouteOwnerClaim claim) {
        deliveryCommandConsumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                claim.deliveryBucketId(),
                claim.workerId(),
                deliveryCommandConsumerKey,
                claim.connectionId(),
                claim.adapterId(),
                0L
        ));
    }

    private static boolean isCurrentOwner(TransportRouteOwnerRecord owner, TransportRouteOwnerClaim claim) {
        return owner != null
                && claim != null
                && claim.workerId().equals(owner.getWorkerId())
                && claim.deliveryBucketId().equals(owner.getDeliveryBucketId())
                && claim.adapterId().equals(owner.getAdapterId())
                && claim.routeKey().equals(owner.getRouteKey())
                && claim.connectionId().equals(owner.getConnectionId());
    }

    private static TransportRouteOwnerClaim routeOwnerClaim(String workerId,
                                                           String deliveryBucketId,
                                                           String routeKey,
                                                           String connectionId,
                                                           String reason) {
        return new TransportRouteOwnerClaim(workerId, deliveryBucketId, PROTOCOL, routeKey, connectionId, reason);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

}
