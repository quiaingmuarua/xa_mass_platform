package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.transport.websocket.queue.OutboundDelivery;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.WebSocketConfig;
import com.xa.mass.starter.config.WebSocketRuntimeComposition;
import com.xa.mass.starter.transport.ManagedTransportAdapter;
import com.xa.mass.starter.transport.RawWorkerMessageChannel;
import com.xa.mass.starter.transport.ResolvedPullWorkerTransport;
import com.xa.mass.starter.transport.TransportAdapterBootstrapContext;
import com.xa.mass.starter.transport.TransportAdapterContribution;
import com.xa.mass.starter.transport.TransportBinding;
import com.xa.mass.starter.transport.RuntimeTaskResultIngestChannel;
import com.xa.mass.starter.transport.TransportRuntimeRegistry;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main runtime composition entry for engine plus embedded transport adapter
 * startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    private final int serverPort;
    private final String transportEndpointPath;
    private final WebSocketRuntimeComposition transportRuntimeComposition;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime = new InMemoryMassEventRuntime();
    private final List<ManagedTransportAdapter> managedTransportAdapters = new ArrayList<>();
    private final List<RawWorkerMessageChannel> rawWorkerMessageChannels = new ArrayList<>();
    private final List<TransportServer> transportServers = new ArrayList<>();

    private final MassEngine engine;
    private MessageTransporter<String, OutboundDelivery> messageTransporter;
    private WorkerEndpointRegistry endpointRegistry;
    private TransportRuntimeRegistry transportRuntimeRegistry;

    public MassApplication(MassEngine engine, int serverPort, String transportEndpointPath,
                           WebSocketConfig webSocketConfig, EngineConfig engineConfig) {
        this.engine = engine;
        this.serverPort = serverPort;
        this.transportEndpointPath = transportEndpointPath;
        this.transportRuntimeComposition = webSocketConfig.snapshotRuntimeComposition();
        this.engineConfig = engineConfig;
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
            initializeComponents();

            if (transportRuntimeComposition.isEnabled()) {
                startManagedTransportAdapters();
            } else {
                logger.info("WebSocket transport adapter is disabled, skipping managed adapter start");
            }

            if (transportRuntimeComposition.isTransportServerEnabled()) {
                startTransportServer();
            } else {
                logger.info("Transport server is disabled, skipping start");
            }

            LogUtils.clearMdc();
            logger.info("Mass Application started successfully");
        } catch (Exception e) {
            running.set(false);
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
            stopManagedTransportAdapters();

            if (engine != null && engineConfig.isEnabled()) {
                engine.stop();
            }

            stopTransportServers();

            LogUtils.clearMdc();
            logger.info("Mass Application stopped successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Error stopping Mass Application", e);
        }
    }

    private void initializeComponents() {
        logger.info("Initializing core components");

        try {
            managedTransportAdapters.clear();
            rawWorkerMessageChannels.clear();
            transportServers.clear();
            endpointRegistry = transportRuntimeComposition.resolveWorkerEndpointRegistry();
            logger.info("Worker endpoint registry initialized");

            messageTransporter = transportRuntimeComposition.createMessageTransporter();
            logger.info("Message transporter created");

            WorkerSystemEventChannel systemEventChannel = transportRuntimeComposition.resolveSystemEventChannel();
            TaskMsgDispatchListener taskMsgDispatchListener = null;
            TaskResultIngestChannel taskResultIngestChannel = null;
            List<TransportBinding> adapterBindings = List.of();
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                taskResultIngestChannel = new RuntimeTaskResultIngestChannel(engineConfig.getTaskManager());
                logger.info("Task result ingest channel initialized");
            }

            TransportAdapterContribution webSocketContribution = transportRuntimeComposition.resolveTransportAdapterBootstrap().create(
                    new TransportAdapterBootstrapContext<>(
                            serverPort,
                            messageTransporter,
                            endpointRegistry,
                            taskResultIngestChannel,
                            systemEventChannel
                    )
            );
            adapterBindings = collectAdapterBindings(webSocketContribution);
            registerManagedTransportAdapter(webSocketContribution.getManagedTransportAdapter());
            registerRawWorkerMessageChannel(webSocketContribution.getRawWorkerMessageChannel());
            registerTransportServer(webSocketContribution.getTransportServer());

            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                transportRuntimeRegistry = transportRuntimeComposition.resolveWorkerTransportRuntimeFactory().create(
                        new WorkerTransportRuntimeFactoryContext(
                                engineConfig.getTaskManager(),
                                engineConfig.getWorkerManager(),
                                taskResultIngestChannel,
                                systemEventChannel,
                                adapterBindings
                        )
                );
                taskMsgDispatchListener = transportRuntimeRegistry.createDispatchListener();
            }

            if (engineConfig.isEnabled()) {
                startEngine(taskMsgDispatchListener);
            } else {
                logger.info("MassEngine is disabled, skipping start");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize core components", e);
            throw new RuntimeException("Failed to initialize core components", e);
        }

        logger.info("Core components initialized");
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
                transportServer.start(serverPort);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start transport server", e);
            }
        }
        logger.info("Transport servers started on port {} (current adapter path={})", serverPort, transportEndpointPath);
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

    private List<TransportBinding> collectAdapterBindings(TransportAdapterContribution contribution) {
        if (contribution == null || contribution.getTransportBinding() == null) {
            return List.of();
        }
        return List.of(contribution.getTransportBinding());
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

    public boolean isRunning() {
        return running.get()
                && (!transportRuntimeComposition.isTransportServerEnabled()
                || transportServers.stream().allMatch(TransportServer::isRunning));
    }

    public PullWorkerSession openPullWorkerSession(String workerId) {
        if (transportRuntimeRegistry == null) {
            throw new IllegalStateException("Pull worker transport is unavailable for this runtime");
        }
        ResolvedPullWorkerTransport resolved = transportRuntimeRegistry.resolvePullWorkerTransport(workerId);
        return new PullWorkerSession(
                resolved.getWorkerId(),
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
        if (rawWorkerMessageChannels.isEmpty()) {
            return false;
        }
        if (rawWorkerMessageChannels.size() == 1) {
            rawWorkerMessageChannels.get(0).send(workerId, rawJson, traceId);
            return true;
        }
        for (RawWorkerMessageChannel rawWorkerMessageChannel : rawWorkerMessageChannels) {
            if (rawWorkerMessageChannel.supports(workerId)) {
                rawWorkerMessageChannel.send(workerId, rawJson, traceId);
                return true;
            }
        }
        return false;
    }

    public MessageTransporter<String, OutboundDelivery> getMessageTransporter() {
        return messageTransporter;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
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
