package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.gateway.runtime.GatewayEmbeddedRuntimeSupport;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import com.xa.mass.starter.transport.ResolvedPullWorkerTransport;
import com.xa.mass.starter.transport.RuntimeTaskResultIngestChannel;
import com.xa.mass.starter.transport.TransportServerFactoryContext;
import com.xa.mass.starter.transport.TransportRuntimeRegistry;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main runtime composition entry for engine, gateway, dispatcher, and server startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    private final int serverPort;
    private final String transportEndpointPath;
    private final GatewayConfig gatewayConfig;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime = new InMemoryMassEventRuntime();

    private final MassEngine engine;
    private MessageTransporter<String, OutboundDelivery> messageTransporter;
    private WorkerEndpointRegistry endpointRegistry;
    private MassGateway massGateway;
    private TransportServer transportServer;
    private TransportRuntimeRegistry transportRuntimeRegistry;

    public MassApplication(MassEngine engine, int serverPort, String transportEndpointPath,
                           GatewayConfig gatewayConfig, EngineConfig engineConfig) {
        this.engine = engine;
        this.serverPort = serverPort;
        this.transportEndpointPath = transportEndpointPath;
        this.gatewayConfig = gatewayConfig;
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

            if (gatewayConfig.isEnabled()) {
                startGateway();
            } else {
                logger.info("MassGateway is disabled, skipping start");
            }

            startMessageDispatcher();
            if (gatewayConfig.isTransportServerEnabled()) {
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
            if (massGateway != null && gatewayConfig.isEnabled()) {
                massGateway.stop();
            }

            if (engine != null && engineConfig.isEnabled()) {
                engine.stop();
            }

            if (transportServer != null) {
                transportServer.stop();
            }

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
            endpointRegistry = gatewayConfig.resolveWorkerEndpointRegistry();
            logger.info("Worker endpoint registry initialized");

            messageTransporter = gatewayConfig.createMessageTransporter();
            logger.info("Message transporter created");

            WorkerSystemEventChannel systemEventChannel = gatewayConfig.resolveSystemEventChannel();
            TaskMsgDispatchListener taskMsgDispatchListener = null;
            TaskResultIngestChannel taskResultIngestChannel = null;
            DispatchRuntimeContext gatewayRuntimeContext = GatewayEmbeddedRuntimeSupport.createDispatcherContext(
                    messageTransporter,
                    endpointRegistry,
                    taskResultIngestChannel,
                    systemEventChannel
            );
            logger.info("Dispatcher context created");
            logger.info("Gateway frame codec resolved");
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                taskResultIngestChannel = new RuntimeTaskResultIngestChannel(engineConfig.getTaskManager());
                gatewayRuntimeContext = GatewayEmbeddedRuntimeSupport.createDispatcherContext(
                        messageTransporter,
                        endpointRegistry,
                        taskResultIngestChannel,
                        systemEventChannel
                );
                logger.info("Dispatcher context refreshed with task result ingest channel");
                TaskDispatchChannel gatewayTaskDispatchChannel = gatewayConfig.isEnabled()
                        ? new WebSocketTaskDispatchChannel(gatewayRuntimeContext)
                        : null;
                transportRuntimeRegistry = gatewayConfig.resolveWorkerTransportRuntimeFactory().create(
                        new WorkerTransportRuntimeFactoryContext<>(
                                engineConfig.getTaskManager(),
                                engineConfig.getWorkerManager(),
                                messageTransporter,
                                endpointRegistry,
                                gatewayTaskDispatchChannel,
                                taskResultIngestChannel,
                                systemEventChannel,
                                gatewayConfig.isEnabled()
                        )
                );
                taskMsgDispatchListener = transportRuntimeRegistry.createDispatchListener();
            }

            if (gatewayConfig.isEnabled()) {
                massGateway = new MassGateway(gatewayConfig, gatewayRuntimeContext);
                logger.info("MassGateway built");
            } else {
                logger.info("MassGateway is disabled, skipping build");
            }

            if (gatewayConfig.isTransportServerEnabled()) {
                transportServer = createTransportServer(gatewayRuntimeContext);
            } else {
                transportServer = null;
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

    private void startGateway() {
        logger.info("Starting MassGateway");
        if (massGateway != null) {
            massGateway.start();
            logger.info("MassGateway started");
        } else {
            logger.error("MassGateway is null");
        }
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

    private void startMessageDispatcher() {
        logger.info("Message Dispatcher is managed by MassGateway");
    }

    private void startTransportServer() {
        logger.info("Starting transport server");
        if (transportServer == null) {
            logger.info("No transport server configured for current runtime");
            return;
        }
        try {
            transportServer.start(serverPort);
        } catch (Exception e) {
            throw new RuntimeException("Failed to start transport server", e);
        }
        logger.info("Transport server started on port {} (current adapter path={})", serverPort, transportEndpointPath);
    }

    private TransportServer createTransportServer(DispatchRuntimeContext gatewayRuntimeContext) {
        if (gatewayConfig.getTransportServerFactory() == null) {
            return GatewayEmbeddedRuntimeSupport.createTransportServer(
                    transportEndpointPath,
                    gatewayRuntimeContext,
                    endpointRegistry
            );
        }
        return gatewayConfig.getTransportServerFactory().create(new TransportServerFactoryContext(
                endpointRegistry,
                messageTransporter::sendInput,
                serverPort,
                transportEndpointPath
        ));
    }

    public boolean isRunning() {
        return running.get()
                && (!gatewayConfig.isTransportServerEnabled()
                || (transportServer != null && transportServer.isRunning()));
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
