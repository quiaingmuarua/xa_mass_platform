package com.xa.mass.starter;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.event.EventPrincipal;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.model.*;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import com.xa.mass.starter.transport.TransportRuntimeRegistry;
import com.xa.mass.starter.transport.WorkerTransportRuntimeFactoryContext;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

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
    private MassGateway massGateway;
    private DispatchRuntimeContext dispatcherContext;
    private TransportServer transportServer;
    private TransportRuntimeRegistry transportRuntimeRegistry;
    private MassMessageHandler workerControlEventBridgeHandler;
    private BiFunction<EventRequest, EventPrincipal, EventResponse> sdkEventDispatcher;

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
            WorkerEndpointRegistry endpointRegistry = gatewayConfig.resolveWorkerEndpointRegistry();
            logger.info("Worker endpoint registry initialized");

            MessageTransporter messageTransporter = gatewayConfig.createMessageTransporter();
            logger.info("Message transporter created");

            MessageCodec messageCodec = gatewayConfig.createMessageCodec();
            logger.info("Message codec created");

            dispatcherContext = new DispatcherContext(messageTransporter, endpointRegistry, messageCodec);
            logger.info("Dispatcher context created");

            com.xa.mass.transport.channel.WorkerSystemEventChannel systemEventChannel =
                    gatewayConfig.resolveSystemEventChannel(endpointRegistry);

            MessageHandlerRegistry messageHandlerRegistry =
                    new MessageHandlerRegistry(systemEventChannel);
            TaskMsgDispatchListener taskMsgDispatchListener = null;
            if (engineConfig.isEnabled() && engineConfig.getTaskManager() != null) {
                transportRuntimeRegistry = gatewayConfig.resolveWorkerTransportRuntimeFactory().create(
                        new WorkerTransportRuntimeFactoryContext(
                                engineConfig.getTaskManager(),
                                engineConfig.getWorkerManager(),
                                dispatcherContext,
                                systemEventChannel,
                                gatewayConfig.isEnabled()
                        )
                );
                transportRuntimeRegistry.registerInboundHandlers(messageHandlerRegistry);
                taskMsgDispatchListener = transportRuntimeRegistry.createDispatchListener();
            }
            messageHandlerRegistry.registerWorkerControlEventResponseHandler(new WorkerControlEventResponseHandler());
            if (workerControlEventBridgeHandler != null) {
                messageHandlerRegistry.registerWorkerControlEventBridge(workerControlEventBridgeHandler);
            }
            dispatcherContext.setMessageHandlerRegistry(messageHandlerRegistry);
            logger.info("Message handler registry initialized");

            logger.info("Middleware registry initialized");

            if (gatewayConfig.isEnabled()) {
                massGateway = new MassGateway(gatewayConfig, dispatcherContext);
                logger.info("MassGateway built");
            } else {
                logger.info("MassGateway is disabled, skipping build");
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
        transportServer = gatewayConfig.createTransportServer(dispatcherContext, serverPort);
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

    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
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
        return transportRuntimeRegistry.openPullWorkerSession(workerId);
    }

    public void publishTaskEvents() {
        requireConfiguredEngine().publishTaskEvents();
    }

    public MassEngine getEngine() {
        return engine;
    }

    public MassEventRuntime getEventRuntime() {
        return eventRuntime;
    }

    public void setWorkerControlEventBridgeHandler(MassMessageHandler workerControlEventBridgeHandler) {
        this.workerControlEventBridgeHandler = workerControlEventBridgeHandler;
    }

    public void setSdkEventDispatcher(BiFunction<EventRequest, EventPrincipal, EventResponse> sdkEventDispatcher) {
        this.sdkEventDispatcher = sdkEventDispatcher;
    }

    private MassEngine requireConfiguredEngine() {
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this application");
        }
        return engine;
    }

    private BiFunction<EventRequest, EventPrincipal, EventResponse> requireSdkEventDispatcher() {
        if (sdkEventDispatcher == null) {
            throw new IllegalStateException("SDK event dispatcher is unavailable for this application");
        }
        return sdkEventDispatcher;
    }

    private MassRuntimeControl runtimeControl() {
        return new MassRuntimeControl() {
            @Override
            public EventResponse dispatchEvent(EventRequest request, EventPrincipal principal) {
                return requireSdkEventDispatcher().apply(request, principal);
            }

            @Override
            public com.xa.mass.base.model.Task createTask(MassTaskCreateRequest request) {
                return requireConfiguredEngine().createTask(SdkResourceMapper.toEngineRequest(request));
            }

            @Override
            public com.xa.mass.base.model.Task createTask(MassTaskRequest request) {
                return requireConfiguredEngine().createTask(MassTaskRequestMapper.toEngineRequest(request));
            }

            @Override
            public com.xa.mass.base.model.Task getTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().getTask(taskId);
            }

            @Override
            public java.util.List<com.xa.mass.base.model.Task> getAllTasks() {
                return requireConfiguredEngine().getTaskManager().getAllTasks();
            }

            @Override
            public boolean approveTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().approveTask(taskId);
            }

            @Override
            public boolean rejectTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().rejectTask(taskId);
            }

            @Override
            public boolean blockTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().blockTask(taskId);
            }

            @Override
            public boolean pauseTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().pauseTask(taskId);
            }

            @Override
            public boolean resumeTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().resumeTask(taskId);
            }

            @Override
            public boolean cancelTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().cancelTask(taskId);
            }

            @Override
            public boolean terminateTask(String taskId, com.xa.mass.base.enums.task.TaskTerminalReason reason) {
                return requireConfiguredEngine().getTaskManager().terminateTask(taskId, reason);
            }

            @Override
            public int appendTaskItems(String taskId, java.util.List<java.util.Map<String, Object>> inputs) {
                return requireConfiguredEngine().getTaskManager().appendTaskItems(taskId, inputs);
            }

            @Override
            public boolean sealTask(String taskId) {
                return requireConfiguredEngine().getTaskManager().sealTask(taskId);
            }

            @Override
            public java.util.List<com.xa.mass.base.model.TaskMsg> getTaskMessages(String taskId) {
                return requireConfiguredEngine().getTaskManager().getTaskMessages(taskId);
            }

            @Override
            public void registerWorker(WorkerRegistration request) {
                requireConfiguredEngine().addWorker(SdkResourceMapper.toWorker(request));
            }

            @Override
            public void registerWorkerContext(WorkerContextRegistration request) {
                requireConfiguredEngine().addWorkerContext(SdkResourceMapper.toWorkerContext(request));
            }

            @Override
            public void addWorker(com.xa.mass.base.model.Worker worker) {
                requireConfiguredEngine().addWorker(worker);
            }

            @Override
            public void addWorkerContext(com.xa.mass.base.model.WorkerContext workerContext) {
                requireConfiguredEngine().addWorkerContext(workerContext);
            }

            @Override
            public void replaceDefaultRules(Collection<RuleDefinition> rules) {
                var ruleManager = requireConfiguredEngine().getConfig().getRuleManager();
                ruleManager.clear();
                ruleManager.addDefaultRules(java.util.List.copyOf(rules));
            }

            @Override
            public void publishTaskEvents() {
                MassApplication.this.publishTaskEvents();
            }
        };
    }
}
