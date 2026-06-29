package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.command.event.BoundedMassEventRuntime;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSessions;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.runtime.InMemoryTransportResultIngressQueue;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.ResolvedPullWorkerTransport;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRegistrationResolver;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeEnvironment;
import com.xa.mass.transport.runtime.embedded.EmbeddedAdapterRuntimeSpec;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.starter.EmbeddedAdapterRuntimeFactoryRegistry;
import com.xa.mass.transport.starter.EmbeddedAdapterStarter;
import com.xa.mass.transport.starter.EmbeddedAdapterStarterDefaults;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.TRANSPORT_DISCONNECTED;

/**
 * Main runtime composition entry for engine plus embedded transport adapter
 * startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);
    private static final int DEFAULT_TRANSPORT_QUEUE_CAPACITY =
            Integer.getInteger("xa.mass.transport.queueCapacity", 10_000);

    private final TransportRuntimeComposition transportRuntimeComposition;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime;

    private final MassEngine engine;
    private TransportEndpointLeaseStore endpointLeaseStore;
    private TransportDispatchQueue transportDispatchQueue;
    private EmbeddedAdapterStarter embeddedAdapterStarter;
    private TransportRegistrationResolver embeddedAdapterRegistrationResolver;
    private TransportResultIngressQueue resultIngressQueue;
    private RedisTransportResultIngressChannel taskResultIngressQueue;
    private TaskResultIngressQueueDrain taskResultIngressQueueDrain;
    private RuntimeTaskExecutor transportRuntimeTaskExecutor;
    private RuntimeTaskExecutor eventRuntimeTaskExecutor;

    public MassApplication(MassEngine engine,
                           TransportConfig transportConfig,
                           EngineConfig engineConfig) {
        this.engine = engine;
        this.transportRuntimeComposition = transportConfig.snapshotRuntimeComposition();
        this.engineConfig = engineConfig;
        this.eventRuntime = createEventRuntime();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            MDC.clear();
            logger.info("Mass Application is already running, skipping duplicate start");
            return;
        }
        MDC.clear();
        logger.info("Starting Mass Application");

        try {
            TaskDispatchBatchListener taskDispatchListener = initializeComponents();

            startEmbeddedAdapterStarter();
            startEngine(taskDispatchListener);

            MDC.clear();
            logger.info("Mass Application started successfully");
        } catch (Exception e) {
            running.set(false);
            cleanupAfterFailedStart(e);
            MDC.clear();
            logger.error("Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            MDC.clear();
            logger.info("Mass Application is not running, skipping stop");
            return;
        }
        MDC.clear();
        logger.info("Stopping Mass Application");

        try {
            try {
                stopEmbeddedAdapterStarter();
                TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
                if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                    stopDispatchQueue();
                    stopDistributedTransportChannels();
                } else if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    stopDistributedTransportChannels();
                }
                if (engine != null && engineConfig.isEnabled()) {
                    engine.stop();
                }
            } finally {
                stopDistributedTransportQueueDrains();
                stopDispatchQueue();
                closeDistributedTransportChannels();
                stopEndpointLeaseStore();
                stopTransportRuntimeTaskExecutor();
                stopEventRuntimeTaskExecutor();
            }

            MDC.clear();
            logger.info("Mass Application stopped successfully");
        } catch (Exception e) {
            MDC.clear();
            logger.error("Error stopping Mass Application", e);
        }
    }

    private void cleanupAfterFailedStart(Exception startupFailure) {
        try {
            if (engine != null && engineConfig.isEnabled()) {
                engine.stop();
            }
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop engine after startup failure", cleanupError);
        }
        try {
            stopEmbeddedAdapterStarter();
            stopDispatchQueue();
            stopDistributedTransportChannels();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop embedded adapter runtime after startup failure", cleanupError);
        }
        try {
            stopEndpointLeaseStore();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop transport endpoint lease store after startup failure", cleanupError);
        }
        try {
            stopTransportRuntimeTaskExecutor();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop transport runtime executor after startup failure", cleanupError);
        }
        try {
            stopEventRuntimeTaskExecutor();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop event runtime executor after startup failure", cleanupError);
        }
    }

    private TaskDispatchBatchListener initializeComponents() {
        logger.info("Initializing core components");

        try {
            embeddedAdapterStarter = null;
            embeddedAdapterRegistrationResolver = null;
            resultIngressQueue = null;
            startEventRuntimeTaskExecutor();
            endpointLeaseStore = resolveTransportEndpointLeaseStore();
            engineConfig.setWorkerReachabilityLookup(this::resolveWorkerReachabilityFromEndpointLease);
            CurrentSessionDisconnectSink currentSessionDisconnectSink = createCurrentSessionDisconnectSink();
            transportRuntimeTaskExecutor = new VirtualThreadRuntimeTaskExecutor(
                    "transport-runtime-",
                    transportRuntimeComposition.getTransportRuntimeMaxPendingTasks()
            );
            TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
            validateWorkerDeliveryTargetResolverConfiguration(runtimeRole);
            if (requiresDispatchQueue(runtimeRole)) {
                transportDispatchQueue =
                        transportRuntimeComposition.resolveTransportDispatchQueue(DEFAULT_TRANSPORT_QUEUE_CAPACITY);
            }
            TaskDispatchBatchListener taskDispatchListener = null;
            if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                taskResultIngressQueue = transportRuntimeComposition.resolveTaskResultIngressQueue();
                resultIngressQueue = taskResultIngressQueue;
                logger.info("Task result ingest channel initialized (redis ingress queue producer)");
            } else if (engineConfig.isEnabled()) {
                TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
                TransportResultIngressQueue resolvedResultQueue = new InMemoryTransportResultIngressQueue(
                        DEFAULT_TRANSPORT_QUEUE_CAPACITY
                );
                if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    taskResultIngressQueue = transportRuntimeComposition.resolveTaskResultIngressQueue();
                    resolvedResultQueue = taskResultIngressQueue;
                    logger.info("Distributed transport result queue drain started for engine-producer role");
                }
                resultIngressQueue = resolvedResultQueue;
                taskResultIngressQueueDrain = new TaskResultIngressQueueDrain(
                        resultIngressQueue,
                        new RuntimeTaskResultIngestChannel(taskResultIngestFacade),
                        transportRuntimeTaskExecutor
                );
                taskResultIngressQueueDrain.start();
                logger.info("Task result ingest queue drain started");
            }
            if (resultIngressQueue == null && runtimeRole == TransportRuntimeRole.EMBEDDED) {
                resultIngressQueue = new InMemoryTransportResultIngressQueue(1);
                logger.info("Task result ingest channel initialized (noop because engine is disabled)");
            }

            List<EmbeddedAdapterRuntimeSpec> embeddedAdapterSpecs =
                    transportRuntimeComposition.resolveEmbeddedAdapterRuntimeSpecs();
            EmbeddedAdapterRuntimeFactoryRegistry embeddedAdapterFactoryRegistry =
                    EmbeddedAdapterStarterDefaults.createRegistry(
                            transportRuntimeComposition.resolvePollingPendingDeliveryBufferFactory(),
                            transportRuntimeComposition.resolveWebSocketServerFactoriesByAdapterId()
                    );
            embeddedAdapterRegistrationResolver =
                    embeddedAdapterFactoryRegistry.registrationResolver(embeddedAdapterSpecs);
            if (runtimeRole != TransportRuntimeRole.ENGINE_PRODUCER) {
                embeddedAdapterStarter = EmbeddedAdapterStarterDefaults.createStarter(
                        new EmbeddedAdapterRuntimeEnvironment(
                                transportDispatchQueue,
                                resultIngressQueue,
                                endpointLeaseStore,
                                currentSessionDisconnectSink,
                                transportRuntimeTaskExecutor
                        ),
                        embeddedAdapterFactoryRegistry
                );
                embeddedAdapterStarter.create(embeddedAdapterSpecs);
            }
            if (engineConfig.isEnabled() && runtimeRole != TransportRuntimeRole.TRANSPORT_CONSUMER) {
                taskDispatchListener = createDispatchSubmitter(transportDispatchQueue);
            }
            return taskDispatchListener;
        } catch (Exception e) {
            try {
                stopDispatchQueue();
                stopDistributedTransportChannels();
                stopTransportRuntimeTaskExecutor();
                stopEventRuntimeTaskExecutor();
            } catch (Exception stopError) {
                logger.warn("Failed to stop transport runtime executor after initialization failure", stopError);
            }
            logger.error("Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }
    }

    private boolean requiresDispatchQueue(TransportRuntimeRole runtimeRole) {
        return runtimeRole == TransportRuntimeRole.EMBEDDED
                || runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER
                || runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER;
    }

    private void validateWorkerDeliveryTargetResolverConfiguration(TransportRuntimeRole runtimeRole) {
        if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER
                && engineConfig.isEnabled()
                && !engineConfig.isWorkerDeliveryTargetResolverExplicitlyConfigured()) {
            throw new IllegalStateException(
                    "engine-producer runtime requires an explicit worker delivery target resolver; "
                            + "local transport bindings are only a valid default for embedded runtime"
            );
        }
    }

    private TaskDispatchBatchListener createDispatchSubmitter(TransportDispatchQueue dispatchQueue) {
        TransportAssignedDeliverySubmitter assignedDeliverySubmitter = new TransportAssignedDeliverySubmitter(dispatchQueue);
        return new TaskDispatchRoutingSubmitter(
                assignedDeliverySubmitter,
                createWorkerDeliveryTargetResolver()
        );
    }

    private java.util.function.Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>>
    createWorkerDeliveryTargetResolver() {
        if (engineConfig.isWorkerDeliveryTargetResolverExplicitlyConfigured()) {
            return engineConfig::resolveWorkerDeliveryTarget;
        }
        return this::resolveWorkerDeliveryTargetFromBinding;
    }

    private Optional<SelectedWorkerDeliveryTargetEvidence> resolveWorkerDeliveryTargetFromBinding(String selectedWorkerId) {
        if (selectedWorkerId == null || selectedWorkerId.isBlank() || embeddedAdapterStarter == null) {
            return Optional.empty();
        }
        WorkerResourceRecord worker;
        try {
            worker = requireWorkerResource(selectedWorkerId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        TransportBinding binding = resolveTransportBinding(worker);
        return Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                worker.workerId(),
                binding.getAdapterMailboxKey(),
                Long.MAX_VALUE
        ));
    }

    private WorkerResourceRecord requireWorkerResource(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        WorkerResourceRecord worker = engineConfig.getWorkerResourceQueryRuntime()
                .worker(workerId.trim())
                .orElse(null);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + workerId.trim());
        }
        return worker;
    }

    private TransportBinding resolveTransportBinding(WorkerResourceRecord worker) {
        Optional<TransportBinding> sessionBinding = resolveCurrentEndpointBinding(worker);
        if (sessionBinding.isPresent()) {
            return sessionBinding.get();
        }
        try {
            return requireEmbeddedAdapterStarter().resolveBinding(null, worker.transportHint());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + worker.workerId()
                    + ": " + e.getMessage(), e);
        }
    }

    private Optional<TransportBinding> resolveCurrentEndpointBinding(WorkerResourceRecord worker) {
        EmbeddedAdapterStarter starter = embeddedAdapterStarter;
        if (worker == null || starter == null || endpointLeaseStore == null
                || worker.workerId() == null || worker.workerId().isBlank()
                || worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return Optional.empty();
        }
        Optional<TransportEndpointLeaseViewRecord> endpoint =
                endpointLeaseStore.currentEndpointLease(worker.workerGroupId(), worker.workerId());
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        String endpointDriverId = endpoint.get().endpointDriverId();
        try {
            TransportBinding binding = starter.resolveBindingByAdapterId(endpointDriverId);
            validateEndpointBindingTransportHint(worker, binding);
            return Optional.of(binding);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + worker.workerId()
                    + " from current endpoint driver '" + endpointDriverId + "': " + e.getMessage(), e);
        }
    }

    private static void validateEndpointBindingTransportHint(WorkerResourceRecord worker, TransportBinding binding) {
        String declaredHint = WorkerTransportHints.normalize(worker.transportHint());
        String bindingHint = binding == null ? null : WorkerTransportHints.normalize(binding.getTransportHint());
        if (declaredHint == null || bindingHint == null || declaredHint.equals(bindingHint)) {
            return;
        }
        throw new IllegalStateException("Current endpoint binding for worker " + worker.workerId()
                + " uses transportHint '" + bindingHint + "' but worker declares transportHint '" + declaredHint + "'");
    }

    private WorkerReachabilityState resolveWorkerReachabilityFromEndpointLease(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        TransportEndpointLeaseStore store = endpointLeaseStore;
        if (store == null) {
            return WorkerReachabilityState.UNKNOWN;
        }
        WorkerResourceRecord worker;
        try {
            worker = requireWorkerResource(workerId.trim());
        } catch (IllegalArgumentException e) {
            return WorkerReachabilityState.UNKNOWN;
        }
        if (worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return WorkerReachabilityState.UNKNOWN;
        }
        return store.currentEndpointLease(worker.workerGroupId(), worker.workerId()).isPresent()
                ? WorkerReachabilityState.ONLINE
                : WorkerReachabilityState.OFFLINE;
    }

    private void startEmbeddedAdapterStarter() {
        if (embeddedAdapterStarter != null) {
            embeddedAdapterStarter.startAll();
        }
    }

    private void startEngine(TaskDispatchBatchListener taskDispatchListener) {
        if (transportRuntimeComposition.getRuntimeRole() == TransportRuntimeRole.TRANSPORT_CONSUMER) {
            logger.info("MassEngine is skipped for transport-consumer runtime role");
            return;
        }
        if (!engineConfig.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }
        logger.info("Starting MassEngine");
        if (engine != null) {
            engine.start(taskDispatchListener);
            logger.info("MassEngine started");
        } else {
            logger.error("MassEngine is null - check if engine is enabled in config");
        }
    }

    private void stopEmbeddedAdapterStarter() {
        EmbeddedAdapterStarter starter = embeddedAdapterStarter;
        embeddedAdapterStarter = null;
        if (starter != null) {
            starter.close();
        }
    }

    private void stopEndpointLeaseStore() throws Exception {
        TransportEndpointLeaseStore store = endpointLeaseStore;
        endpointLeaseStore = null;
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private void stopDispatchQueue() {
        TransportDispatchQueue dispatchQueue = transportDispatchQueue;
        transportDispatchQueue = null;
        if (dispatchQueue != null) {
            dispatchQueue.shutdown();
        }
    }

    private TransportEndpointLeaseStore resolveTransportEndpointLeaseStore() {
        java.util.function.Supplier<TransportEndpointLeaseStore> factory =
                transportRuntimeComposition.endpointLeaseStoreFactory();
        TransportEndpointLeaseStore store = factory != null
                ? factory.get()
                : new InMemoryTransportEndpointLeaseStore(transportRuntimeComposition.getEndpointLeaseMillis());
        if (store == null) {
            throw new IllegalStateException("Transport endpoint lease store factory returned null");
        }
        return store;
    }

    private void stopDistributedTransportChannels() {
        stopDistributedTransportQueueDrains();
        closeDistributedTransportChannels();
    }

    private void stopDistributedTransportQueueDrains() {
        TaskResultIngressQueueDrain resultDrain = taskResultIngressQueueDrain;
        taskResultIngressQueueDrain = null;
        if (resultDrain != null) {
            resultDrain.stop();
        }
    }

    private void closeDistributedTransportChannels() {
        RedisTransportResultIngressChannel resultIngressQueue = taskResultIngressQueue;
        taskResultIngressQueue = null;
        if (resultIngressQueue != null) {
            resultIngressQueue.shutdown();
        }
    }

    private void stopTransportRuntimeTaskExecutor() throws Exception {
        RuntimeTaskExecutor executor = transportRuntimeTaskExecutor;
        transportRuntimeTaskExecutor = null;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void startEventRuntimeTaskExecutor() {
        if (transportRuntimeComposition.getEventHandlerTimeoutMillis() <= 0 || eventRuntimeTaskExecutor != null) {
            return;
        }
        eventRuntimeTaskExecutor = new VirtualThreadRuntimeTaskExecutor(
                "runtime-event-handler-",
                transportRuntimeComposition.getEventRuntimeMaxPendingTasks()
        );
    }

    private void stopEventRuntimeTaskExecutor() throws Exception {
        RuntimeTaskExecutor executor = eventRuntimeTaskExecutor;
        eventRuntimeTaskExecutor = null;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private MassEventRuntime createEventRuntime() {
        InMemoryMassEventRuntime inMemoryRuntime = new InMemoryMassEventRuntime();
        long timeoutMillis = transportRuntimeComposition.getEventHandlerTimeoutMillis();
        if (timeoutMillis <= 0) {
            return inMemoryRuntime;
        }
        return new BoundedMassEventRuntime(inMemoryRuntime, () -> eventRuntimeTaskExecutor, timeoutMillis);
    }

    private CurrentSessionDisconnectSink createCurrentSessionDisconnectSink() {
        if (!engineConfig.isEnabled()) {
            return CurrentSessionDisconnectSink.NOOP;
        }
        return (deliveryBucketId, workerId, reason, observedAtMillis) ->
                engineConfig.getWorkerDispatchBlockRuntime().blockWorkerDispatch(
                        deliveryBucketId,
                        workerId,
                        new WorkerDispatchBlockSignal(
                                WorkerDispatchBlockSource.TRANSPORT_DISCONNECTED,
                                firstNonBlank(reason, "transport session disconnected"),
                                observedAtMillis,
                                0L
                        )
                );
    }

    public boolean isRunning() {
        boolean engineExpected = engineConfig.isEnabled()
                && transportRuntimeComposition.getRuntimeRole() != TransportRuntimeRole.TRANSPORT_CONSUMER;
        return running.get()
                && (embeddedAdapterStarter == null || embeddedAdapterStarter.isRunning())
                && (!engineExpected || engine == null || engine.isRunning());
    }

    public EmbeddedPullWorkerSession openEmbeddedPullWorkerSession(String workerId) {
        return openEmbeddedPullWorkerSession(workerId, UUID.randomUUID().toString());
    }

    public EmbeddedPullWorkerSession openEmbeddedPullWorkerSession(String workerId, String sessionToken) {
        EmbeddedAdapterStarter starter = embeddedAdapterStarter;
        if (starter == null) {
            throw new IllegalStateException("Pull worker transport is unavailable for this runtime");
        }
        WorkerResourceRecord worker = requireWorkerResource(workerId);
        ResolvedPullWorkerTransport resolved = starter.resolvePullWorkerTransport(
                worker.workerId(),
                worker.workerGroupId(),
                null,
                worker.transportHint()
        );
        return EmbeddedPullWorkerSessions.open(
                resolved.getWorkerId(),
                resolved.getWorkerGroupId(),
                requireText(sessionToken, "sessionToken"),
                resolved.getDeliveryPullChannel(),
                resolved.getResultIngressChannel(),
                resolved.getPullSessionEvidenceDriver(),
                engineConfig.getWorkerHeartbeatRuntime(),
                resolved.getTransportHint()
        );
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return null;
    }

    public String resolveWorkerAdapterId(String workerId) {
        return resolveTransportBinding(requireWorkerResource(workerId)).getAdapterId();
    }

    public String resolveWorkerTransportHint(String workerId) {
        return resolveTransportBinding(requireWorkerResource(workerId)).getTransportHint();
    }

    public Map<String, Object> getTransportQueueDetail() {
        return TransportQueueDiagnosticsMapper.toQueueDetail(
                transportRuntimeTaskExecutor,
                eventRuntimeTaskExecutor
        );
    }

    /**
     * Internal registration helper used by SDK/starter compatibility paths
     * when worker registration input must be normalized before the live
     * transport runtime registry is assembled.
     */
    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        EmbeddedAdapterStarter starter = embeddedAdapterStarter;
        if (starter != null) {
            return starter.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
        }
        TransportRegistrationResolver resolver = embeddedAdapterRegistrationResolver;
        if (resolver == null) {
            throw new IllegalStateException("Embedded adapter registration resolver is unavailable");
        }
        return resolver.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    private EmbeddedAdapterStarter requireEmbeddedAdapterStarter() {
        EmbeddedAdapterStarter starter = embeddedAdapterStarter;
        if (starter == null) {
            throw new IllegalStateException("Embedded adapter runtime is unavailable for this application");
        }
        return starter;
    }

    public MassEngine getEngine() {
        return engine;
    }

    public MassEventRuntime getEventRuntime() {
        return eventRuntime;
    }

    private MassEngine requireConfiguredEngine() {
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this application");
        }
        return engine;
    }

}
