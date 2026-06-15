package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;

import java.util.Objects;

/**
 * Runtime-owned pull transport binding resolved for one worker.
 */
public final class ResolvedPullWorkerTransport {

    private final String workerId;
    private final String workerGroupId;
    private final String adapterId;
    private final String transportHint;
    private final DeliveryPullChannel deliveryPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final TransportRouteOwnerStore routeOwnerStore;
    private final DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry;
    private final String deliveryCommandConsumerKey;

    public ResolvedPullWorkerTransport(String workerId,
                                       String workerGroupId,
                                       String adapterId,
                                       String transportHint,
                                       DeliveryPullChannel deliveryPullChannel,
                                       TaskResultIngestChannel taskResultIngestChannel,
                                       TransportRouteOwnerStore routeOwnerStore) {
        this(workerId,
                workerGroupId,
                adapterId,
                transportHint,
                deliveryPullChannel,
                taskResultIngestChannel,
                routeOwnerStore,
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                "local");
    }

    public ResolvedPullWorkerTransport(String workerId,
                                       String workerGroupId,
                                       String adapterId,
                                       String transportHint,
                                       DeliveryPullChannel deliveryPullChannel,
                                       TaskResultIngestChannel taskResultIngestChannel,
                                       TransportRouteOwnerStore routeOwnerStore,
                                       DeliveryCommandConsumerRegistry deliveryCommandConsumerRegistry,
                                       String deliveryCommandConsumerKey) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.workerGroupId = Objects.requireNonNull(workerGroupId, "workerGroupId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.transportHint = Objects.requireNonNull(transportHint, "transportHint");
        this.deliveryPullChannel = Objects.requireNonNull(deliveryPullChannel, "deliveryPullChannel");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.routeOwnerStore = Objects.requireNonNull(routeOwnerStore, "routeOwnerStore");
        this.deliveryCommandConsumerRegistry = deliveryCommandConsumerRegistry != null
                ? deliveryCommandConsumerRegistry
                : NoopDeliveryCommandConsumerRegistry.INSTANCE;
        this.deliveryCommandConsumerKey = requireText(deliveryCommandConsumerKey, "deliveryCommandConsumerKey");
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getWorkerGroupId() {
        return workerGroupId;
    }

    public String getAdapterId() {
        return adapterId;
    }

    public String getTransportHint() {
        return transportHint;
    }

    public DeliveryPullChannel getDeliveryPullChannel() {
        return deliveryPullChannel;
    }

    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    public TransportRouteOwnerStore getRouteOwnerStore() {
        return routeOwnerStore;
    }

    public DeliveryCommandConsumerRegistry getDeliveryCommandConsumerRegistry() {
        return deliveryCommandConsumerRegistry;
    }

    public String getDeliveryCommandConsumerKey() {
        return deliveryCommandConsumerKey;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
