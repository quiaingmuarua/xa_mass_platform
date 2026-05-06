package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.command.event.BoundedMassEventRuntime;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.engine.TaskResultIngestFacade;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.ResolvedPullWorkerTransport;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.BufferedTaskResultIngestChannel;
import com.xa.mass.transport.runtime.RuntimeTaskResultIngestChannel;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactoryContext;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDirectDeliveryStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryServiceStats;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final List<ManagedTransportAdapter> managedTransportAdapters = new ArrayList<>();
    private final List<RawWorkerMessageChannel> rawWorkerMessageChannels = new ArrayList<>();
    private final List<TransportServer> transportServers = new ArrayList<>();

    private final MassEngine engine;
    private MessageTransporter<String, TransportOutboundMessage> messageTransporter;
    private WorkerEndpointRegistry endpointRegistry;
    private TransportRuntimeRegistry transportRuntimeRegistry;
    private TransportDeliveryService transportDeliveryService;
    private RuntimeTaskExecutor transportRuntimeTaskExecutor;
    private RuntimeTaskExecutor eventRuntimeTaskExecutor;
    private BufferedTaskResultIngestChannel bufferedResultIngestChannel;

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
            LogUtils.clearMdc();
            logger.info("Mass Application is already running, skipping duplicate start");
            return;
        }
        LogUtils.clearMdc();
        logger.info("Starting Mass Application");

        try {
            TaskMsgDispatchListener taskMsgDispatchListener = initializeComponents();

            startManagedTransportAdapters();
            startTransportServer();
            startEngine(taskMsgDispatchListener);

            LogUtils.clearMdc();
            logger.info("Mass Application started successfully");
        } catch (Exception e) {
            running.set(false);
            cleanupAfterFailedStart(e);
            LogUtils.clearMdc();
            logger.error("Failed to start Mass Application", e);
            throw new RuntimeException("Failed to start Mass Application", e);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            LogUtils.clearMdc();
            logger.info("Mass Application is not running, skipping stop");
            return;
        }
        LogUtils.clearMdc();
        logger.info("Stopping Mass Application");

        try {
            try {
                stopTransportServers();
                stopManagedTransportAdapters();
                drainResultIngestBuffer();

                if (engine != null && engineConfig.isEnabled()) {
                    engine.stop();
                }
            } finally {
                stopTransportDeliveryService();
                stopTransportRuntimeTaskExecutor();
                stopEventRuntimeTaskExecutor();
            }

            LogUtils.clearMdc();
            logger.info("Mass Application stopped successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
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
            stopTransportServers();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop transport servers after startup failure", cleanupError);
        }
        try {
            stopManagedTransportAdapters();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop managed transport adapters after startup failure", cleanupError);
        }
        try {
            drainResultIngestBuffer();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to drain result ingest buffer after startup failure", cleanupError);
        }
        try {
            stopTransportDeliveryService();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop transport delivery service after startup failure", cleanupError);
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

    private TaskMsgDispatchListener initializeComponents() {
        logger.info("Initializing core components");

        try {
            managedTransportAdapters.clear();
            rawWorkerMessageChannels.clear();
            transportServers.clear();
            startEventRuntimeTaskExecutor();
            endpointRegistry = transportRuntimeComposition.resolveWorkerEndpointRegistry();
            logger.info("Worker endpoint registry initialized");

            messageTransporter = transportRuntimeComposition.createMessageTransporterIfConfigured();
            if (messageTransporter != null) {
                logger.info("Message transporter created");
            } else {
                logger.info("No shared message transporter configured; continuing with adapter-native transport runtime");
            }

            WorkerSystemEventChannel systemEventChannel = transportRuntimeComposition.resolveSystemEventChannel();
            TransportDeliveryService deliveryService =
                    new TransportDeliveryService(new InMemoryTransportDeliveryStore(
                            transportRuntimeComposition.getMaxDeliveryQueuedItems()
                    ));
            transportDeliveryService = deliveryService;
            transportRuntimeTaskExecutor = new VirtualThreadRuntimeTaskExecutor(
                    "transport-runtime-",
                    transportRuntimeComposition.getTransportRuntimeMaxPendingTasks()
            );
            TaskMsgDispatchListener taskMsgDispatchListener = null;
            TaskResultIngestChannel taskResultIngestChannel = null;
            List<TransportBinding> adapterBindings = new ArrayList<>();
            if (engineConfig.isEnabled()) {
                TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
                BufferedTaskResultIngestChannel buffer = new BufferedTaskResultIngestChannel(
                        new RuntimeTaskResultIngestChannel(taskResultIngestFacade));
                bufferedResultIngestChannel = buffer;
                taskResultIngestChannel = buffer;
                logger.info("Task result ingest channel initialized (buffered async)");
            }

            for (TransportAdapterBootstrap<TransportOutboundMessage> transportAdapterBootstrap
                    : transportRuntimeComposition.resolveTransportAdapterBootstraps()) {
                TransportAdapterContribution contribution = transportAdapterBootstrap.create(
                        new TransportAdapterBootstrapContext<>(
                                endpointRegistry,
                                taskResultIngestChannel,
                                systemEventChannel,
                                deliveryService,
                                transportRuntimeTaskExecutor
                        )
                );
                registerTransportContribution(contribution, adapterBindings);
            }

            if (engineConfig.isEnabled()) {
                transportRuntimeRegistry = transportRuntimeComposition.resolveWorkerTransportRuntimeFactory().create(
                        new WorkerTransportRuntimeFactoryContext(
                                engineConfig.getWorkerManager(),
                                taskResultIngestChannel,
                                systemEventChannel,
                                deliveryService,
                                adapterBindings
                        )
                );
                taskMsgDispatchListener = transportRuntimeRegistry.createDispatchListener();
            }
            return taskMsgDispatchListener;
        } catch (Exception e) {
            try {
                stopTransportDeliveryService();
                stopTransportRuntimeTaskExecutor();
                stopEventRuntimeTaskExecutor();
            } catch (Exception stopError) {
                logger.warn("Failed to stop transport runtime executor after initialization failure", stopError);
            }
            logger.error("Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }
    }

    private void startManagedTransportAdapters() {
        logger.info("Starting managed transport adapters");
        if (managedTransportAdapters.isEmpty()) {
            logger.info("No managed transport adapters configured for current runtime");
            return;
        }
        for (ManagedTransportAdapter managedTransportAdapter : managedTransportAdapters) {
            managedTransportAdapter.start();
        }
        logger.info("Managed transport adapters started");
    }

    private void startEngine(TaskMsgDispatchListener taskMsgDispatchListener) {
        if (!engineConfig.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }
        logger.info("Starting MassEngine");
        if (engine != null) {
            engine.start(taskMsgDispatchListener);
            logger.info("MassEngine started");
        } else {
            logger.error("MassEngine is null - check if engine is enabled in config");
        }
    }

    private void startTransportServer() {
        logger.info("Starting transport servers");
        if (transportServers.isEmpty()) {
            logger.info("No transport server configured for current runtime");
            return;
        }
        for (TransportServer transportServer : transportServers) {
            try {
                transportServer.start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start transport server", e);
            }
        }
        logger.info("Transport servers started: count={}", transportServers.size());
    }

    private void stopManagedTransportAdapters() throws Exception {
        for (ManagedTransportAdapter managedTransportAdapter : managedTransportAdapters) {
            managedTransportAdapter.stop();
        }
    }

    private void stopTransportServers() throws Exception {
        for (TransportServer transportServer : transportServers) {
            transportServer.stop();
        }
    }

    private void drainResultIngestBuffer() {
        BufferedTaskResultIngestChannel buffer = bufferedResultIngestChannel;
        bufferedResultIngestChannel = null;
        if (buffer != null) {
            logger.info("Draining result ingest buffer");
            buffer.shutdown();
            logger.info("Result ingest buffer drained");
        }
    }

    private void stopTransportDeliveryService() {
        TransportDeliveryService deliveryService = transportDeliveryService;
        transportDeliveryService = null;
        if (deliveryService != null) {
            deliveryService.shutdown();
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

    private void registerManagedTransportAdapter(ManagedTransportAdapter managedTransportAdapter) {
        if (managedTransportAdapter != null) {
            managedTransportAdapters.add(managedTransportAdapter);
        }
    }

    private void registerTransportServer(TransportServer transportServer) {
        if (transportServer != null) {
            transportServers.add(transportServer);
        }
    }

    private void registerRawWorkerMessageChannel(RawWorkerMessageChannel rawWorkerMessageChannel) {
        if (rawWorkerMessageChannel != null) {
            rawWorkerMessageChannels.add(rawWorkerMessageChannel);
        }
    }

    private void registerTransportContribution(TransportAdapterContribution contribution,
                                               List<TransportBinding> adapterBindings) {
        if (contribution == null) {
            return;
        }
        if (contribution.getTransportBinding() != null) {
            adapterBindings.add(contribution.getTransportBinding());
        }
        registerManagedTransportAdapter(contribution.getManagedTransportAdapter());
        registerRawWorkerMessageChannel(contribution.getRawWorkerMessageChannel());
        registerTransportServer(contribution.getTransportServer());
    }

    public boolean isRunning() {
        return running.get()
                && managedTransportAdapters.stream().allMatch(ManagedTransportAdapter::isRunning)
                && transportServers.stream().allMatch(TransportServer::isRunning)
                && (!engineConfig.isEnabled() || engine == null || engine.isRunning());
    }

    public PullWorkerSession openPullWorkerSession(String workerId) {
        if (transportRuntimeRegistry == null) {
            throw new IllegalStateException("Pull worker transport is unavailable for this runtime");
        }
        ResolvedPullWorkerTransport resolved = transportRuntimeRegistry.resolvePullWorkerTransport(workerId);
        return new PullWorkerSession(
                resolved.getWorkerId(),
                resolved.getAdapterId(),
                resolved.getTaskPullChannel(),
                resolved.getTaskResultIngestChannel(),
                resolved.getSystemEventChannel(),
                resolved.getTransportHint()
        );
    }

    public void publishTaskEvents() {
        requireConfiguredEngine().publishTaskEvents();
    }

    public boolean sendRawTransportMessage(String workerId, String rawJson, String traceId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (rawJson == null) {
            throw new IllegalArgumentException("rawJson must not be null");
        }
        if (rawWorkerMessageChannels.isEmpty() || transportRuntimeRegistry == null) {
            return false;
        }
        String normalizedWorkerId = workerId.trim();
        String workerAdapterId = transportRuntimeRegistry.resolveWorkerAdapterId(normalizedWorkerId);
        String routeKey = resolveRawMessageRouteKey(normalizedWorkerId, workerAdapterId);
        if (routeKey == null) {
            logger.debug("Skip raw transport side-channel because no unique active route is available: workerId={}, adapterId={}",
                    normalizedWorkerId, workerAdapterId);
            return false;
        }
        for (RawWorkerMessageChannel rawWorkerMessageChannel : rawWorkerMessageChannels) {
            if (rawWorkerMessageChannel.supportsRoute(routeKey, workerAdapterId)) {
                rawWorkerMessageChannel.sendToRoute(routeKey, rawJson, traceId);
                return true;
            }
        }
        return false;
    }

    private String resolveRawMessageRouteKey(String workerId, String adapterId) {
        WorkerEndpointInspector inspector = endpointRegistry instanceof WorkerEndpointInspector endpointInspector
                ? endpointInspector
                : null;
        if (inspector == null || adapterId == null || adapterId.isBlank()) {
            return null;
        }
        Set<String> routeKeys = new LinkedHashSet<>();
        for (WorkerEndpointSnapshot snapshot : inspector.listWorkerEndpoints()) {
            if (snapshot == null || !snapshot.isActive()) {
                continue;
            }
            if (!workerId.equals(snapshot.getWorkerId())) {
                continue;
            }
            if (!adapterId.equalsIgnoreCase(snapshot.getAdapterId())) {
                continue;
            }
            String routeKey = snapshot.getRouteKey();
            if (routeKey != null && !routeKey.isBlank()) {
                routeKeys.add(routeKey.trim());
            }
        }
        return routeKeys.size() == 1 ? routeKeys.iterator().next() : null;
    }

    public Map<String, Object> getTransportQueueDetail() {
        int inputSize = safeInputQueueSize(messageTransporter);
        int outputSize = safeOutputQueueSize(messageTransporter);
        TransportDeliveryService deliveryService = transportDeliveryService;
        TransportDeliveryServiceStats stats = deliveryService != null ? deliveryService.stats() : null;
        Map<String, TransportDirectDeliveryStats> directByAdapter =
                deliveryService != null ? deliveryService.directStatsByAdapter() : Map.of();
        return TransportQueueDiagnosticsMapper.toQueueDetail(
                inputSize,
                outputSize,
                messageTransporter != null,
                deliveryService != null,
                stats,
                directByAdapter,
                transportRuntimeTaskExecutor,
                eventRuntimeTaskExecutor
        );
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public TransportRuntimeRegistry getTransportRuntimeRegistry() {
        return transportRuntimeRegistry;
    }

    /**
     * Internal registration helper used by SDK/starter compatibility paths
     * when worker registration input must be normalized before the live
     * transport runtime registry is assembled.
     */
    public String resolveRegistrationAdapterId(String requestedAdapterId, String transportHint) {
        if (transportRuntimeRegistry != null) {
            return transportRuntimeRegistry.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
        }
        return transportRuntimeComposition.resolveRegistrationAdapterId(requestedAdapterId, transportHint);
    }

    public MassEngine getEngine() {
        return engine;
    }

    public MassEventRuntime getEventRuntime() {
        return eventRuntime;
    }

    private int safeInputQueueSize(MessageTransporter<?, ?> transporter) {
        try {
            return transporter != null ? transporter.inputQueueSize() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private int safeOutputQueueSize(MessageTransporter<?, ?> transporter) {
        try {
            return transporter != null ? transporter.outputQueueSize() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private MassEngine requireConfiguredEngine() {
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this application");
        }
        return engine;
    }

}

