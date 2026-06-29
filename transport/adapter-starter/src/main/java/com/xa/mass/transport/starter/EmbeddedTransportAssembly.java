package com.xa.mass.transport.starter;

import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.polling.delivery.InMemoryPollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.RedisPollingPendingDeliveryBuffer;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.ResolvedPullWorkerTransport;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDispatchBatch;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.RedisTransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.lease.RedisTransportEndpointLeaseStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter-starter-owned assembly for embedded transport runtime primitives.
 */
public final class EmbeddedTransportAssembly implements AutoCloseable {

    private final TransportDispatchQueue dispatchQueue;
    private final TransportResultIngressQueue resultQueue;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final EmbeddedAdapterStarter adapterStarter;
    private final AssignedDeliverySink assignedDeliverySink;
    private final ResultIngressSource resultIngressSource;

    private EmbeddedTransportAssembly(TransportDispatchQueue dispatchQueue,
                                      TransportResultIngressQueue resultQueue,
                                      TransportEndpointLeaseStore endpointLeaseStore,
                                      EmbeddedAdapterStarter adapterStarter) {
        this.dispatchQueue = Objects.requireNonNull(dispatchQueue, "dispatchQueue");
        this.resultQueue = Objects.requireNonNull(resultQueue, "resultQueue");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.adapterStarter = Objects.requireNonNull(adapterStarter, "adapterStarter");
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(dispatchQueue);
        this.assignedDeliverySink = batches -> submitter.submit(toRuntimeBatches(batches));
        this.resultIngressSource = timeoutMillis ->
                resultQueue.poll(TransportResultIngressQueue.DEFAULT_RESULT_QUEUE_KEY, timeoutMillis);
    }

    public static EmbeddedTransportAssembly create(EmbeddedTransportAssemblyConfig config) {
        Objects.requireNonNull(config, "config");
        EmbeddedTransportBackendDeclaration backend = config.backend();
        TransportDispatchQueue dispatchQueue = createDispatchQueue(backend);
        TransportResultIngressQueue resultQueue = createResultQueue(backend);
        TransportEndpointLeaseStore endpointLeaseStore = createEndpointLeaseStore(backend);
        EmbeddedAdapterStarter starter = EmbeddedAdapterStarterDefaults.createStarter(
                new EmbeddedAdapterRuntimeEnvironment(
                        dispatchQueue,
                        resultQueue,
                        endpointLeaseStore,
                        config.currentSessionDisconnectHandler()::currentSessionDisconnected,
                        config.executor()
                ),
                createPollingBufferFactory(backend)
        );
        starter.create(config.adapterDeclarations());
        return new EmbeddedTransportAssembly(dispatchQueue, resultQueue, endpointLeaseStore, starter);
    }

    public void startAllAdapters() {
        adapterStarter.startAll();
    }

    public boolean isRunning() {
        return adapterStarter.isRunning();
    }

    public AssignedDeliverySink assignedDeliverySink() {
        return assignedDeliverySink;
    }

    public ResultIngressSource resultIngressSource() {
        return resultIngressSource;
    }

    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        return adapterStarter.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public EmbeddedTransportBindingView resolveBinding(String requestedAdapterId, String transportHint) {
        return view(adapterStarter.resolveBinding(requestedAdapterId, transportHint));
    }

    public EmbeddedTransportBindingView resolveBindingByAdapterId(String adapterId) {
        return view(adapterStarter.resolveBindingByAdapterId(adapterId));
    }

    public EmbeddedPullWorkerTransport resolvePullWorkerTransport(String workerId,
                                                                  String workerGroupId,
                                                                  String requestedAdapterId,
                                                                  String transportHint) {
        ResolvedPullWorkerTransport resolved = adapterStarter.resolvePullWorkerTransport(
                workerId,
                workerGroupId,
                requestedAdapterId,
                transportHint
        );
        return new EmbeddedPullWorkerTransport(
                resolved.getWorkerId(),
                resolved.getWorkerGroupId(),
                resolved.getAdapterId(),
                resolved.getTransportHint(),
                resolved.getDeliveryPullChannel(),
                resolved.getResultIngressChannel(),
                evidencePort(resolved.getPullSessionEvidenceDriver())
        );
    }

    public Optional<String> currentEndpointDriverId(String deliveryBucketId, String workerId) {
        return endpointLeaseStore.currentEndpointLease(deliveryBucketId, workerId)
                .map(TransportEndpointLeaseViewRecord::endpointDriverId);
    }

    public boolean hasCurrentEndpointLease(String deliveryBucketId, String workerId) {
        return endpointLeaseStore.currentEndpointLease(deliveryBucketId, workerId).isPresent();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            adapterStarter.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            dispatchQueue.shutdown();
        } catch (RuntimeException e) {
            failure = suppress(failure, e);
        }
        try {
            closeResultQueue(resultQueue);
        } catch (Exception e) {
            failure = suppress(failure, e);
        }
        try {
            closeEndpointLeaseStore(endpointLeaseStore);
        } catch (Exception e) {
            failure = suppress(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static List<AdapterMailboxDispatchBatch> toRuntimeBatches(List<AssignedDeliveryBatch> batches) {
        return List.copyOf(Objects.requireNonNull(batches, "batches")).stream()
                .map(batch -> new AdapterMailboxDispatchBatch(
                        batch.adapterMailboxKey(),
                        toRuntimeMessages(batch.messages())
                ))
                .toList();
    }

    private static List<DispatchMessage> toRuntimeMessages(List<AssignedDeliveryMessage> messages) {
        return List.copyOf(Objects.requireNonNull(messages, "messages")).stream()
                .map(message -> new DispatchMessage(
                        message.deliveryId(),
                        message.selectedWorkerId(),
                        message.payload(),
                        message.correlationRef(),
                        message.deadlineEpochMillis(),
                        message.createdAtEpochMillis()
                ))
                .toList();
    }

    private static EmbeddedTransportBindingView view(TransportBinding binding) {
        return new EmbeddedTransportBindingView(
                binding.getAdapterId(),
                binding.getAdapterMailboxKey(),
                binding.getTransportHint()
        );
    }

    private static PullSessionEvidencePort evidencePort(PullSessionEvidenceDriver driver) {
        Objects.requireNonNull(driver, "driver");
        return new PullSessionEvidencePort() {
            @Override
            public boolean connect(String workerId, String workerGroupId, String sessionToken, String reason) {
                return driver.connect(workerId, workerGroupId, sessionToken, reason);
            }

            @Override
            public boolean heartbeat(String workerId, String workerGroupId, String sessionToken, String reason) {
                return driver.heartbeat(workerId, workerGroupId, sessionToken, reason);
            }

            @Override
            public boolean disconnect(String workerId, String workerGroupId, String sessionToken, String reason) {
                return driver.disconnect(workerId, workerGroupId, sessionToken, reason);
            }
        };
    }

    static EmbeddedAdapterRuntimeSpec runtimeSpec(EmbeddedAdapterDeclaration declaration) {
        return new EmbeddedAdapterRuntimeSpec(
                declaration.type(),
                declaration.adapterId(),
                declaration.dispatchQueueKey(),
                declaration.resultQueueKey(),
                declaration.options()
        );
    }

    private static TransportDispatchQueue createDispatchQueue(EmbeddedTransportBackendDeclaration backend) {
        if (backend.hasDispatchRedis()) {
            return new RedisTransportDispatchHandoff(
                    backend.dispatchRedisUri(),
                    backend.dispatchNamespace(),
                    backend.maxDispatchItemsPerQueue()
            );
        }
        return new InMemoryTransportDispatchHandoff(backend.maxDispatchItemsPerQueue());
    }

    private static TransportResultIngressQueue createResultQueue(EmbeddedTransportBackendDeclaration backend) {
        if (backend.hasResultIngressRedis()) {
            return new RedisTransportResultIngressChannel(
                    backend.resultIngressRedisUri(),
                    backend.resultIngressNamespace(),
                    backend.maxResultIngressItems()
            );
        }
        return new InMemoryTransportResultIngressQueue(backend.maxResultIngressItems());
    }

    private static TransportEndpointLeaseStore createEndpointLeaseStore(EmbeddedTransportBackendDeclaration backend) {
        if (backend.hasEndpointLeaseRedis()) {
            return new RedisTransportEndpointLeaseStore(
                    backend.endpointLeaseRedisUri(),
                    backend.endpointLeaseNamespace(),
                    backend.endpointLeaseMillis()
            );
        }
        return new InMemoryTransportEndpointLeaseStore(backend.endpointLeaseMillis());
    }

    private static Supplier<PollingPendingDeliveryBuffer> createPollingBufferFactory(
            EmbeddedTransportBackendDeclaration backend) {
        if (backend.hasPollingDeliveryRedis()) {
            return () -> new RedisPollingPendingDeliveryBuffer(
                    backend.pollingDeliveryRedisUri(),
                    backend.pollingDeliveryNamespace(),
                    backend.maxPollingPendingDeliveryItems(),
                    backend.maxPollingPendingDeliveryItemsPerWorker()
            );
        }
        return () -> new InMemoryPollingPendingDeliveryBuffer(
                backend.maxPollingPendingDeliveryItems(),
                backend.maxPollingPendingDeliveryItemsPerWorker()
        );
    }

    private static void closeResultQueue(TransportResultIngressQueue queue) throws Exception {
        if (queue instanceof AutoCloseable closeable) {
            closeable.close();
            return;
        }
        if (queue instanceof InMemoryTransportResultIngressQueue inMemory) {
            inMemory.shutdown();
        }
    }

    private static void closeEndpointLeaseStore(TransportEndpointLeaseStore store) throws Exception {
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static RuntimeException suppress(RuntimeException current, Exception next) {
        RuntimeException runtimeNext = next instanceof RuntimeException runtime
                ? runtime
                : new RuntimeException(next);
        if (current == null) {
            return runtimeNext;
        }
        current.addSuppressed(runtimeNext);
        return current;
    }
}
