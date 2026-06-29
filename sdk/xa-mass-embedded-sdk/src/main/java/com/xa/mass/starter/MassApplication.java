package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.command.event.BoundedMassEventRuntime;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSessions;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.starter.AssignedDeliverySink;
import com.xa.mass.transport.starter.CurrentSessionDisconnectHandler;
import com.xa.mass.transport.starter.EmbeddedPullWorkerTransport;
import com.xa.mass.transport.starter.EmbeddedTransportAssembly;
import com.xa.mass.transport.starter.EmbeddedTransportAssemblyConfig;
import com.xa.mass.transport.starter.EmbeddedTransportBindingView;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSignal;
import com.xa.mass.worker.runtime.control.WorkerDispatchBlockSource;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main runtime composition entry for engine plus embedded transport adapter
 * startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    private final TransportRuntimeComposition transportRuntimeComposition;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime;

    private final MassEngine engine;
    private EmbeddedTransportAssembly transportAssembly;
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
                if (engine != null && engineConfig.isEnabled()) {
                    engine.stop();
                }
            } finally {
                stopDistributedTransportQueueDrains();
                closeDistributedTransportChannels();
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
            stopDistributedTransportChannels();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop embedded adapter runtime after startup failure", cleanupError);
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
            transportAssembly = null;
            startEventRuntimeTaskExecutor();
            engineConfig.setWorkerReachabilityLookup(this::resolveWorkerReachabilityFromEndpointLease);
            transportRuntimeTaskExecutor = new VirtualThreadRuntimeTaskExecutor(
                    "transport-runtime-",
                    transportRuntimeComposition.getTransportRuntimeMaxPendingTasks()
            );
            TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
            validateWorkerDeliveryTargetResolverConfiguration(runtimeRole);
            transportAssembly = EmbeddedTransportAssembly.create(new EmbeddedTransportAssemblyConfig(
                    transportRuntimeComposition.getBackendDeclaration(),
                    transportRuntimeComposition.resolveEmbeddedAdapterDeclarations(),
                    transportRuntimeTaskExecutor,
                    createCurrentSessionDisconnectHandler()
            ));
            TaskDispatchBatchListener taskDispatchListener = null;
            if (runtimeRole != TransportRuntimeRole.TRANSPORT_CONSUMER && engineConfig.isEnabled()) {
                TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
                if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    logger.info("Distributed transport result queue drain started for engine-producer role");
                }
                taskResultIngressQueueDrain = new TaskResultIngressQueueDrain(
                        transportAssembly.resultIngressSource(),
                        new RuntimeTaskResultIngestChannel(taskResultIngestFacade),
                        transportRuntimeTaskExecutor
                );
                taskResultIngressQueueDrain.start();
                logger.info("Task result ingest queue drain started");
            }
            if (engineConfig.isEnabled() && runtimeRole != TransportRuntimeRole.TRANSPORT_CONSUMER) {
                taskDispatchListener = createDispatchSubmitter(transportAssembly.assignedDeliverySink());
            }
            return taskDispatchListener;
        } catch (Exception e) {
            try {
                stopDistributedTransportQueueDrains();
                closeTransportAssembly();
                stopTransportRuntimeTaskExecutor();
                stopEventRuntimeTaskExecutor();
            } catch (Exception stopError) {
                logger.warn("Failed to stop transport runtime executor after initialization failure", stopError);
            }
            logger.error("Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }
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

    private TaskDispatchBatchListener createDispatchSubmitter(AssignedDeliverySink assignedDeliverySink) {
        return new TaskDispatchRoutingSubmitter(
                assignedDeliverySink,
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
        if (selectedWorkerId == null || selectedWorkerId.isBlank() || transportAssembly == null) {
            return Optional.empty();
        }
        WorkerResourceRecord worker;
        try {
            worker = requireWorkerResource(selectedWorkerId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        EmbeddedTransportBindingView binding = resolveTransportBinding(worker);
        return Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                worker.workerId(),
                binding.adapterMailboxKey(),
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

    private EmbeddedTransportBindingView resolveTransportBinding(WorkerResourceRecord worker) {
        Optional<EmbeddedTransportBindingView> sessionBinding = resolveCurrentEndpointBinding(worker);
        if (sessionBinding.isPresent()) {
            return sessionBinding.get();
        }
        try {
            return requireTransportAssembly().resolveBinding(null, worker.transportHint());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + worker.workerId()
                    + ": " + e.getMessage(), e);
        }
    }

    private Optional<EmbeddedTransportBindingView> resolveCurrentEndpointBinding(WorkerResourceRecord worker) {
        EmbeddedTransportAssembly assembly = transportAssembly;
        if (worker == null || assembly == null
                || worker.workerId() == null || worker.workerId().isBlank()
                || worker.workerGroupId() == null || worker.workerGroupId().isBlank()) {
            return Optional.empty();
        }
        Optional<String> endpointDriverId = assembly.currentEndpointDriverId(worker.workerGroupId(), worker.workerId());
        if (endpointDriverId.isEmpty()) {
            return Optional.empty();
        }
        try {
            EmbeddedTransportBindingView binding = assembly.resolveBindingByAdapterId(endpointDriverId.get());
            validateEndpointBindingTransportHint(worker, binding);
            return Optional.of(binding);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + worker.workerId()
                    + " from current endpoint driver '" + endpointDriverId.get() + "': " + e.getMessage(), e);
        }
    }

    private static void validateEndpointBindingTransportHint(WorkerResourceRecord worker,
                                                             EmbeddedTransportBindingView binding) {
        String declaredHint = WorkerTransportHints.normalize(worker.transportHint());
        String bindingHint = binding == null ? null : WorkerTransportHints.normalize(binding.transportHint());
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
        EmbeddedTransportAssembly assembly = transportAssembly;
        if (assembly == null) {
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
        return assembly.hasCurrentEndpointLease(worker.workerGroupId(), worker.workerId())
                ? WorkerReachabilityState.ONLINE
                : WorkerReachabilityState.OFFLINE;
    }

    private void startEmbeddedAdapterStarter() {
        if (transportAssembly != null
                && transportRuntimeComposition.getRuntimeRole() != TransportRuntimeRole.ENGINE_PRODUCER) {
            transportAssembly.startAllAdapters();
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
        closeTransportAssembly();
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
        closeTransportAssembly();
    }

    private void closeTransportAssembly() {
        EmbeddedTransportAssembly assembly = transportAssembly;
        transportAssembly = null;
        if (assembly != null) {
            assembly.close();
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

    private CurrentSessionDisconnectHandler createCurrentSessionDisconnectHandler() {
        if (!engineConfig.isEnabled()) {
            return CurrentSessionDisconnectHandler.NOOP;
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
                && (transportAssembly == null || transportAssembly.isRunning())
                && (!engineExpected || engine == null || engine.isRunning());
    }

    public EmbeddedPullWorkerSession openEmbeddedPullWorkerSession(String workerId) {
        return openEmbeddedPullWorkerSession(workerId, UUID.randomUUID().toString());
    }

    public EmbeddedPullWorkerSession openEmbeddedPullWorkerSession(String workerId, String sessionToken) {
        EmbeddedTransportAssembly assembly = transportAssembly;
        if (assembly == null) {
            throw new IllegalStateException("Pull worker transport is unavailable for this runtime");
        }
        WorkerResourceRecord worker = requireWorkerResource(workerId);
        EmbeddedPullWorkerTransport resolved = assembly.resolvePullWorkerTransport(
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
                resolved.getPullSessionEvidencePort(),
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
        return resolveTransportBinding(requireWorkerResource(workerId)).adapterId();
    }

    public String resolveWorkerTransportHint(String workerId) {
        return resolveTransportBinding(requireWorkerResource(workerId)).transportHint();
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
        return requireTransportAssembly().resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    private EmbeddedTransportAssembly requireTransportAssembly() {
        EmbeddedTransportAssembly assembly = transportAssembly;
        if (assembly == null) {
            throw new IllegalStateException("Embedded adapter runtime is unavailable for this application");
        }
        return assembly;
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
