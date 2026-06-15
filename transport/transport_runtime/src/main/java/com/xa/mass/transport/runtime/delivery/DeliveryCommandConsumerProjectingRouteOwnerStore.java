package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.route.RouteConsumerEndpoint;
import com.xa.mass.transport.route.SelectedWorkerDeliveryTarget;
import com.xa.mass.transport.route.TransportRouteOwnerClaim;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Projects route-owner session evidence into the assigned-delivery consumer
 * registry without making producer-side delivery depend on route lookup.
 */
public final class DeliveryCommandConsumerProjectingRouteOwnerStore implements TransportRouteOwnerStore,
        WorkerDispatchRouteOwnerView,
        AutoCloseable {

    private final TransportRouteOwnerStore delegate;
    private final WorkerDispatchRouteOwnerView ownerView;
    private final DeliveryCommandConsumerRegistry consumerRegistry;

    public DeliveryCommandConsumerProjectingRouteOwnerStore(TransportRouteOwnerStore delegate,
                                                            DeliveryCommandConsumerRegistry consumerRegistry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (!(delegate instanceof WorkerDispatchRouteOwnerView view)) {
            throw new IllegalArgumentException("delegate must implement WorkerDispatchRouteOwnerView");
        }
        this.ownerView = view;
        this.consumerRegistry = Objects.requireNonNull(consumerRegistry, "consumerRegistry");
    }

    @Override
    public TransportRouteOwnerRecord claimRouteOwner(TransportRouteOwnerClaim claim) {
        TransportRouteOwnerRecord record = delegate.claimRouteOwner(claim);
        project(record);
        return record;
    }

    @Override
    public TransportRouteOwnerRecord refreshHeartbeat(TransportRouteOwnerClaim claim) {
        TransportRouteOwnerRecord record = delegate.refreshHeartbeat(claim);
        refreshOrRelease(claim, record);
        return record;
    }

    @Override
    public TransportRouteOwnerRecord releaseRouteOwner(TransportRouteOwnerClaim claim) {
        TransportRouteOwnerRecord previous = delegate.releaseRouteOwner(claim);
        refreshOrRelease(claim, previous);
        return previous;
    }

    @Override
    public int pruneExpired() {
        return delegate.pruneExpired();
    }

    @Override
    public long getLeaseMillis() {
        return delegate.getLeaseMillis();
    }

    @Override
    public List<WorkerDispatchRouteOwner> currentOwners(String routeKey) {
        return ownerView.currentOwners(routeKey);
    }

    @Override
    public Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(String deliveryBucketId,
                                                                          String selectedWorkerId) {
        return ownerView.targetForSelectedWorker(deliveryBucketId, selectedWorkerId);
    }

    @Override
    public Optional<RouteConsumerEndpoint> endpointForSelectedWorker(String deliveryBucketId,
                                                                     String selectedWorkerId) {
        return ownerView.endpointForSelectedWorker(deliveryBucketId, selectedWorkerId);
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    public TransportRouteOwnerStore delegate() {
        return delegate;
    }

    private void refreshOrRelease(TransportRouteOwnerClaim claim, TransportRouteOwnerRecord previous) {
        if (claim == null) {
            return;
        }
        Optional<RouteConsumerEndpoint> current = ownerView.endpointForSelectedWorker(
                claim.deliveryBucketId(),
                claim.workerId()
        );
        if (current.isPresent()) {
            project(current.get());
            return;
        }
        if (previous != null) {
            consumerRegistry.releaseConsumer(new DeliveryCommandConsumerClaim(
                    previous.getDeliveryBucketId(),
                    previous.getWorkerId(),
                    previous.getTransportNodeId(),
                    previous.getAdapterId(),
                    0L
            ));
        }
    }

    private void project(TransportRouteOwnerRecord record) {
        if (record == null) {
            return;
        }
        consumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                record.getDeliveryBucketId(),
                record.getWorkerId(),
                record.getTransportNodeId(),
                record.getAdapterId(),
                record.getLeaseExpireAtEpochMillis()
        ));
    }

    private void project(RouteConsumerEndpoint endpoint) {
        consumerRegistry.claimConsumer(new DeliveryCommandConsumerClaim(
                endpoint.deliveryBucketId(),
                endpoint.selectedWorkerId(),
                endpoint.transportNodeId(),
                endpoint.adapterId(),
                endpoint.leaseExpireAtEpochMillis()
        ));
    }

}
