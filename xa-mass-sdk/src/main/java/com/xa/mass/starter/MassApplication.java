package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import com.xa.mass.transport.websocket.runtime.WebSocketEmbeddedRuntimeSupport;
import com.xa.mass.transport.websocket.queue.OutboundDelivery;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.WebSocketConfig;
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
 * Main runtime composition entry for engine, WebSocket adapter, dispatcher, and server startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);

    private final int serverPort;
    private final String transportEndpointPath;
    private final WebSocketConfig webSocketConfig;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime = new InMemoryMassEventRuntime();

    private final MassEngine engine;
    private MessageTransporter<String, OutboundDelivery> messageTransporter;
    private WorkerEndpointRegistry endpointRegistry;
    private MassWebSocketAdapter massWebSocketAdapter;
    private TransportServer transportServer;
    private TransportRuntimeRegistry transportRuntimeRegistry;

    public MassApplication(MassEngine engine, int serverPort, String transportEndpointPath,
                           WebSocketConfig webSocketConfig, EngineConfig engineConfig) {
        this.engine = engine;
        this.serverPort = serverPort;
        this.transportEndpointPath = transportEndpointPath;
        this.webSocketConfig = webSocketConfig;
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

            if (webSocketConfig.isEnabled()) {
                startWebSocketAdapter();
            } else {
                logger.info("MassWebSocketAdapter is disabled, skipping start");
            }

            startMessageDispatcher();
            if (webSocketConfig.isTransportServerEnabled()) {
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
            if (massWebSocketAdapter != null && webSocketConfig.isEnabled()) {
                massWebSocketAdapter.stop();
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
            endpointRegistry = webSocketConfig.resolveWorkerEndpointRegistry();
            logger.info("Worker endpoint registry initialized");

            messageTransporter = webSocketConfig.createMessageTransporter();
            logger.info("Message transporter created");

            WorkerSystemEventChannel systemEventChannel = webSocketConfig.resolveSystemEventChannel();
            TaskMsgDispatchListener taskMsgDispatchListener = null;
            TaskResultIngestChannel taskResultIngestChannel = null;
            WebSocketDispatchRuntimeContext webSocketRuntimeContext = WebSocketEmbeddedRuntimeSupport.createDispatcherContext(
                    messageTransporter,
                    endpointRegistry,
                    taskResultIngestChannel,
                    systemEventChannel
            );
            logger.info("Dispatcher context created");
            logger.info("WebSocket frame codec resolved");
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                taskResultIngestChannel = new RuntimeTaskResultIngestChannel(engineConfig.getTaskManager());
                webSocketRuntimeContext = WebSocketEmbeddedRuntimeSupport.createDispatcherContext(
                        messageTransporter,
                        endpointRegistry,
                        taskResultIngestChannel,
                        systemEventChannel
                );
                logger.info("Dispatcher context refreshed with task result ingest channel");
                TaskDispatchChannel webSocketTaskDispatchChannel = webSocketConfig.isEnabled()
                        ? new WebSocketTaskDispatchChannel(webSocketRuntimeContext)
                        : null;
                transportRuntimeRegistry = webSocketConfig.resolveWorkerTransportRuntimeFactory().create(
                        new WorkerTransportRuntimeFactoryContext<>(
                                engineConfig.getTaskManager(),
                                engineConfig.getWorkerManager(),
                                messageTransporter,
                                endpointRegistry,
                                webSocketTaskDispatchChannel,
                                taskResultIngestChannel,
                                systemEventChannel,
                                webSocketConfig.isEnabled()
                        )
                );
                taskMsgDispatchListener = transportRuntimeRegistry.createDispatchListener();
            }

            if (webSocketConfig.isEnabled()) {
                massWebSocketAdapter = new MassWebSocketAdapter(webSocketConfig, webSocketRuntimeContext);
                logger.info("MassWebSocketAdapter built");
            } else {
                logger.info("MassWebSocketAdapter is disabled, skipping build");
            }

            if (webSocketConfig.isTransportServerEnabled()) {
                transportServer = createTransportServer(webSocketRuntimeContext);
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

    private void startWebSocketAdapter() {
        logger.info("Starting MassWebSocketAdapter");
        if (massWebSocketAdapter != null) {
            massWebSocketAdapter.start();
            logger.info("MassWebSocketAdapter started");
        } else {
            logger.error("MassWebSocketAdapter is null");
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
        logger.info("WebSocket message dispatcher is managed by MassWebSocketAdapter");
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

    private TransportServer createTransportServer(WebSocketDispatchRuntimeContext webSocketRuntimeContext) {
        if (webSocketConfig.getTransportServerFactory() == null) {
            return WebSocketEmbeddedRuntimeSupport.createTransportServer(
                    transportEndpointPath,
                    webSocketRuntimeContext,
                    endpointRegistry
            );
        }
        return webSocketConfig.getTransportServerFactory().create(new TransportServerFactoryContext(
                endpointRegistry,
                messageTransporter::sendInput,
                serverPort,
                transportEndpointPath
        ));
    }

    public boolean isRunning() {
        return running.get()
                && (!webSocketConfig.isTransportServerEnabled()
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
