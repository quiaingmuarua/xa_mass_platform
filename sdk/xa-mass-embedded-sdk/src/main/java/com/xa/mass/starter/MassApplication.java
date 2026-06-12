package com.xa.mass.starter;

import com.google.gson.Gson;
import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchDeliveryFailure;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.model.Task;
import com.xa.mass.command.event.BoundedMassEventRuntime;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.command.event.MassEventRuntime;
import com.xa.mass.worker.runtime.command.WorkerCommandDeliveryResult;
import com.xa.mass.worker.runtime.command.WorkerCommandRecord;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.RedisTaskResultIngestChannel;
import com.xa.mass.transport.runtime.ResolvedPullWorkerTransport;
import com.xa.mass.transport.runtime.TracingWorkerSystemEventChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.BufferedTaskResultIngestChannel;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.TaskResultIngestInboxPump;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandHandoffPump;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryCommandListener;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureInboxPump;
import com.xa.mass.transport.runtime.delivery.TransportDirectDeliveryStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryServiceStats;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;
import com.xa.mass.transport.runtime.node.TransportNodeRegistryHeartbeat;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.route.TransportRouteOwnerStore;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main runtime composition entry for engine plus embedded transport adapter
 * startup.
 */
public class MassApplication {

    private static final Logger logger = LoggerFactory.getLogger(MassApplication.class);
    private static final Gson TRANSPORT_JSON = new Gson();
    private static final int DEFAULT_DISPATCH_HANDOFF_CAPACITY =
            Integer.getInteger("xa.mass.engine.dispatchHandoffCapacity", 10_000);

    private final TransportRuntimeComposition transportRuntimeComposition;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime;
    private final List<ManagedTransportAdapter> managedTransportAdapters = new ArrayList<>();
    private final Map<String, RawWorkerMessageChannel> rawWorkerMessageChannelsByAdapterId = new LinkedHashMap<>();
    private final List<TransportServer> transportServers = new ArrayList<>();

    private final MassEngine engine;
    private MessageTransporter<String, TransportOutboundMessage> messageTransporter;
    private WorkerEndpointRegistry endpointRegistry;
    private TransportRuntimeRegistry transportRuntimeRegistry;
    private TransportRouteOwnerStore routeOwnerStore;
    private WorkerDispatchRouteOwnerView workerRouteOwnerView;
    private TransportDeliveryService transportDeliveryService;
    private TransportDeliveryCommandHandoff transportDeliveryCommandHandoff;
    private TransportDeliveryCommandHandoffPump transportDeliveryCommandHandoffPump;
    private RedisTaskResultIngestChannel taskResultInbox;
    private TaskResultIngestInboxPump taskResultInboxPump;
    private RedisTransportDeliveryFailureChannel deliveryFailureInbox;
    private TransportDeliveryFailureInboxPump deliveryFailureInboxPump;
    private TransportNodeRegistry transportNodeRegistry;
    private TransportNodeRegistryHeartbeat transportNodeHeartbeat;
    private RuntimeTaskExecutor transportRuntimeTaskExecutor;
    private RuntimeTaskExecutor eventRuntimeTaskExecutor;
    private BufferedTaskResultIngestChannel bufferedResultIngestChannel;
    private WorkerSystemEventChannel workerSystemEventChannel;

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

            startManagedTransportAdapters();
            startTransportServer();
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
                stopTransportServers();
                stopManagedTransportAdapters();
                stopTransportNodeHeartbeat();
                TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
                if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                    stopTaskDispatchHandoff();
                    stopDistributedTransportInboxes();
                } else if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    stopDistributedTransportInboxes();
                }
                drainResultIngestBuffer();

                if (engine != null && engineConfig.isEnabled()) {
                    engine.stop();
                }
            } finally {
                stopTaskDispatchHandoff();
                closeDistributedTransportInboxes();
                stopTransportNodeHeartbeat();
                closeTransportNodeRegistry();
                stopTransportDeliveryService();
                stopRouteOwnerStore();
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
            stopTaskDispatchHandoff();
            stopDistributedTransportInboxes();
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
            stopRouteOwnerStore();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop transport route-owner store after startup failure", cleanupError);
        }
        try {
            stopTransportNodeHeartbeat();
            closeTransportNodeRegistry();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop transport node registry after startup failure", cleanupError);
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
            managedTransportAdapters.clear();
            rawWorkerMessageChannelsByAdapterId.clear();
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

            WorkerSystemEventChannel systemEventChannel = new TracingWorkerSystemEventChannel(
                    transportRuntimeComposition.resolveSystemEventChannel(),
                    engineConfig.getExecutionEventSink()
            );
            workerSystemEventChannel = systemEventChannel;
            routeOwnerStore = transportRuntimeComposition.resolveTransportRouteOwnerStore();
            workerRouteOwnerView = requireWorkerRouteOwnerView(routeOwnerStore);
            transportNodeRegistry = transportRuntimeComposition.resolveTransportNodeRegistry();
            TransportDeliveryStore deliveryStore = transportRuntimeComposition.resolveTransportDeliveryStore();
            TransportDeliveryService deliveryService = new TransportDeliveryService(deliveryStore);
            transportDeliveryService = deliveryService;
            transportRuntimeTaskExecutor = new VirtualThreadRuntimeTaskExecutor(
                    "transport-runtime-",
                    transportRuntimeComposition.getTransportRuntimeMaxPendingTasks()
            );
            TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
            TaskDispatchBatchListener taskDispatchListener = null;
            TaskResultIngestChannel taskResultIngestChannel = null;
            List<TransportBinding> adapterBindings = new ArrayList<>();
            if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                taskResultInbox = transportRuntimeComposition.resolveTaskResultInbox();
                taskResultIngestChannel = taskResultInbox;
                logger.info("Task result ingest channel initialized (redis inbox producer)");
            } else if (engineConfig.isEnabled()) {
                TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
                BufferedTaskResultIngestChannel buffer = new BufferedTaskResultIngestChannel(
                        new RuntimeTaskResultIngestChannel(taskResultIngestFacade));
                bufferedResultIngestChannel = buffer;
                taskResultIngestChannel = buffer;
                logger.info("Task result ingest channel initialized (buffered async)");
                if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    taskResultInbox = transportRuntimeComposition.resolveTaskResultInbox();
                    taskResultInboxPump = new TaskResultIngestInboxPump(
                            taskResultInbox,
                            buffer,
                            transportRuntimeTaskExecutor
                    );
                    taskResultInboxPump.start();
                    deliveryFailureInbox = transportRuntimeComposition.resolveDeliveryFailureInbox();
                    deliveryFailureInboxPump = new TransportDeliveryFailureInboxPump(
                            deliveryFailureInbox,
                            createTransportDeliveryFailureHandler(),
                            transportRuntimeTaskExecutor
                    );
                    deliveryFailureInboxPump.start();
                    logger.info("Distributed transport inbox pumps started for engine-producer role");
                }
            }
            if (taskResultIngestChannel == null && runtimeRole == TransportRuntimeRole.EMBEDDED) {
                taskResultIngestChannel = report -> false;
                logger.info("Task result ingest channel initialized (noop because engine is disabled)");
            }

            if (runtimeRole != TransportRuntimeRole.ENGINE_PRODUCER) {
                for (TransportAdapterBootstrap transportAdapterBootstrap
                        : transportRuntimeComposition.resolveTransportAdapterBootstraps()) {
                    TransportAdapterBootstrapContext bootstrapContext = new TransportAdapterBootstrapContext(
                            endpointRegistry,
                            taskResultIngestChannel,
                            systemEventChannel,
                            routeOwnerStore,
                            deliveryService,
                            transportRuntimeTaskExecutor
                    );
                    transportAdapterBootstrap.contribute(bootstrapContext);
                    registerTransportBootstrapContext(bootstrapContext, adapterBindings);
                }
            }

            if (runtimeRole != TransportRuntimeRole.ENGINE_PRODUCER) {
                transportRuntimeRegistry = transportRuntimeComposition.resolveWorkerTransportRuntimeFactory().create(
                        taskResultIngestChannel,
                        systemEventChannel,
                        routeOwnerStore,
                        deliveryService,
                        adapterBindings
                );
                configureRealtimeWorkerCommandDelivery();
            }
            if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                startTransportNodeHeartbeat(adapterBindings);
            }
            if (engineConfig.isEnabled() && runtimeRole != TransportRuntimeRole.TRANSPORT_CONSUMER) {
                transportDeliveryCommandHandoff =
                        transportRuntimeComposition.resolveTransportDeliveryCommandHandoff(DEFAULT_DISPATCH_HANDOFF_CAPACITY);
                if (runtimeRole == TransportRuntimeRole.EMBEDDED) {
                    TransportDeliveryCommandListener batchListener = new TransportDeliveryCommandListener(
                            transportRuntimeRegistry,
                            createTransportDeliveryFailureHandler(),
                            transportRuntimeTaskExecutor
                    );
                    transportDeliveryCommandHandoffPump = new TransportDeliveryCommandHandoffPump(
                            transportDeliveryCommandHandoff,
                            batchListener,
                            transportRuntimeTaskExecutor
                    );
                    transportDeliveryCommandHandoffPump.start();
                }
                taskDispatchListener = createDispatchSubmitter(transportDeliveryCommandHandoff);
            } else if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                transportDeliveryCommandHandoff =
                        transportRuntimeComposition.resolveTransportDeliveryCommandHandoff(DEFAULT_DISPATCH_HANDOFF_CAPACITY);
                deliveryFailureInbox = transportRuntimeComposition.resolveDeliveryFailureInbox();
                TransportDeliveryCommandListener batchListener = new TransportDeliveryCommandListener(
                        transportRuntimeRegistry,
                        deliveryFailureInbox,
                        transportRuntimeTaskExecutor
                );
                transportDeliveryCommandHandoffPump = new TransportDeliveryCommandHandoffPump(
                        transportDeliveryCommandHandoff,
                        batchListener,
                        transportRuntimeTaskExecutor
                );
                transportDeliveryCommandHandoffPump.start();
            }
            return taskDispatchListener;
        } catch (Exception e) {
            try {
                stopTaskDispatchHandoff();
                stopDistributedTransportInboxes();
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

    private TransportDeliveryFailureHandler createTransportDeliveryFailureHandler() {
        return (command, outcome, detail) -> {
            if (command == null) {
                return false;
            }
            String taskId = firstNonBlank(command.getPayload().taskId(), command.getCorrelation().get("taskId"));
            if (taskId == null) {
                logger.error("Cannot compensate delivery failure because task id is missing: deliveryId={}",
                        outcome != null ? outcome.getDeliveryId() : command.getCommandId());
                return false;
            }
            Task storedTask = engineConfig.getTaskShellStore().getTask(taskId).orElse(null);
            if (storedTask == null) {
                logger.error("Cannot compensate delivery failure because task {} is missing", taskId);
                return false;
            }
            TaskDispatchDeliveryFailure failure;
            try {
                failure = toDeliveryFailure(command, outcome, detail);
            } catch (RuntimeException e) {
                logger.error("Cannot compensate delivery failure because failure record is incomplete: deliveryId={}, reason={}",
                        outcome != null ? outcome.getDeliveryId() : command.getCommandId(), e.getMessage());
                return false;
            }
            return engineConfig.getTaskAssignmentRuntimePort()
                    .compensateDispatchDeliveryFailure(storedTask, List.of(failure));
        };
    }

    private TaskDispatchDeliveryFailure toDeliveryFailure(com.xa.mass.transport.model.DeliveryCommand command,
                                                          com.xa.mass.transport.model.DispatchOutcome outcome,
                                                          String detail) {
        String taskId = firstNonBlank(command.getPayload().taskId(), command.getCorrelation().get("taskId"));
        String messageId = firstNonBlank(command.getPayload().messageId(), command.getCorrelation().get("messageId"));
        String attemptId = firstNonBlank(command.getPayload().attemptId(), command.getCorrelation().get("attemptId"));
        String attemptNo = command.getCorrelation().get("attemptNo");
        return new TaskDispatchDeliveryFailure(
                taskId,
                messageId,
                attemptId,
                parseAttemptNo(attemptNo),
                command.getSelectedWorkerId(),
                firstNonBlank(detail, outcome != null ? outcome.getReason() : null)
        );
    }

    private TaskDispatchBatchListener createDispatchSubmitter(TransportDeliveryCommandHandoff handoff) {
        return new TaskDispatchDeliveryCommandSubmitter(
                handoff,
                this::mintTransportRouteKey,
                workerRouteOwnerView,
                transportNodeRegistry,
                createTransportDeliveryFailureHandler()
        );
    }

    private WorkerResourceRecord requireWorkerResource(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        WorkerResourceRecord worker = engineConfig.getWorkerResourceRuntime()
                .worker(workerId.trim())
                .orElse(null);
        if (worker == null) {
            throw new IllegalArgumentException("Worker not found: " + workerId.trim());
        }
        return worker;
    }

    private TransportBinding resolveTransportBinding(WorkerResourceRecord worker) {
        try {
            return transportRuntimeRegistry.resolveBinding(worker.adapterId(), worker.onlineStrategy());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + worker.workerId()
                    + ": " + e.getMessage(), e);
        }
    }

    private String mintTransportRouteKey(TaskDispatchContext task, TaskDispatchBinding dispatchBinding) {
        if (task == null || dispatchBinding == null) {
            throw new IllegalArgumentException("task and dispatchBinding must not be null");
        }
        String workerGroupId = dispatchBinding.workerGroupId();
        if ((workerGroupId == null || workerGroupId.isBlank()) && dispatchBinding.workerId() != null) {
            WorkerResourceRecord worker = engineConfig.getWorkerResourceRuntime()
                    .worker(dispatchBinding.workerId().trim())
                    .orElse(null);
            workerGroupId = worker != null ? worker.workerGroupId() : null;
        }
        return CanonicalWorkerGroupRouteKeyCodec.encode(workerGroupId);
    }

    private void startTransportNodeHeartbeat(List<TransportBinding> adapterBindings) {
        TransportNodeRegistry registry = transportNodeRegistry;
        if (registry == null) {
            return;
        }
        String transportNodeId = transportRuntimeComposition.getTransportNodeId();
        List<String> adapterIds = adapterBindings == null
                ? List.of()
                : adapterBindings.stream()
                .map(TransportBinding::getAdapterId)
                .filter(adapterId -> adapterId != null && !adapterId.isBlank())
                .map(adapterId -> adapterId.trim().toLowerCase(java.util.Locale.ROOT))
                .distinct()
                .toList();
        transportNodeHeartbeat = new TransportNodeRegistryHeartbeat(
                registry,
                transportNodeId,
                adapterIds,
                () -> endpointRegistry != null ? endpointRegistry.getActiveConnectionCount() : 0L,
                5_000L
        );
        transportNodeHeartbeat.start();
        logger.info("Transport node heartbeat started: transportNodeId={}, adapters={}", transportNodeId, adapterIds);
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

    private void stopRouteOwnerStore() throws Exception {
        TransportRouteOwnerStore store = routeOwnerStore;
        routeOwnerStore = null;
        workerRouteOwnerView = null;
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private static WorkerDispatchRouteOwnerView requireWorkerRouteOwnerView(TransportRouteOwnerStore store) {
        if (store instanceof WorkerDispatchRouteOwnerView routeOwnerView) {
            return routeOwnerView;
        }
        throw new IllegalStateException("TransportRouteOwnerStore must also implement WorkerDispatchRouteOwnerView");
    }

    private void stopTaskDispatchHandoff() {
        TransportDeliveryCommandHandoffPump pump = transportDeliveryCommandHandoffPump;
        transportDeliveryCommandHandoffPump = null;
        if (pump != null) {
            pump.stop();
        }
        TransportDeliveryCommandHandoff handoff = transportDeliveryCommandHandoff;
        transportDeliveryCommandHandoff = null;
        if (handoff != null) {
            handoff.shutdown();
        }
    }

    private void stopDistributedTransportInboxes() {
        stopDistributedTransportInboxPumps();
        closeDistributedTransportInboxes();
    }

    private void stopDistributedTransportInboxPumps() {
        TaskResultIngestInboxPump resultPump = taskResultInboxPump;
        taskResultInboxPump = null;
        if (resultPump != null) {
            resultPump.stop();
        }
        TransportDeliveryFailureInboxPump failurePump = deliveryFailureInboxPump;
        deliveryFailureInboxPump = null;
        if (failurePump != null) {
            failurePump.stop();
        }
    }

    private void closeDistributedTransportInboxes() {
        RedisTaskResultIngestChannel resultInbox = taskResultInbox;
        taskResultInbox = null;
        if (resultInbox != null) {
            resultInbox.shutdown();
        }
        RedisTransportDeliveryFailureChannel failureInbox = deliveryFailureInbox;
        deliveryFailureInbox = null;
        if (failureInbox != null) {
            failureInbox.shutdown();
        }
    }

    private void stopTransportNodeHeartbeat() {
        TransportNodeRegistryHeartbeat heartbeat = transportNodeHeartbeat;
        transportNodeHeartbeat = null;
        if (heartbeat != null) {
            heartbeat.stop();
        }
    }

    private void closeTransportNodeRegistry() throws Exception {
        TransportNodeRegistry registry = transportNodeRegistry;
        transportNodeRegistry = null;
        if (registry instanceof AutoCloseable closeable) {
            closeable.close();
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
        workerSystemEventChannel = null;
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
            String adapterId = requireRawWorkerMessageAdapterId(rawWorkerMessageChannel);
            RawWorkerMessageChannel existing = rawWorkerMessageChannelsByAdapterId.putIfAbsent(adapterId, rawWorkerMessageChannel);
            if (existing != null && existing != rawWorkerMessageChannel) {
                throw new IllegalStateException("Duplicate raw worker message channel configured for adapterId '" + adapterId + "'");
            }
        }
    }

    private void registerTransportBootstrapContext(TransportAdapterBootstrapContext bootstrapContext,
                                                   List<TransportBinding> adapterBindings) {
        if (bootstrapContext.getTransportBinding() != null) {
            adapterBindings.add(bootstrapContext.getTransportBinding());
        }
        registerManagedTransportAdapter(bootstrapContext.getManagedTransportAdapter());
        registerRawWorkerMessageChannel(bootstrapContext.getRawWorkerMessageChannel());
        registerTransportServer(bootstrapContext.getTransportServer());
    }

    public boolean isRunning() {
        boolean engineExpected = engineConfig.isEnabled()
                && transportRuntimeComposition.getRuntimeRole() != TransportRuntimeRole.TRANSPORT_CONSUMER;
        return running.get()
                && managedTransportAdapters.stream().allMatch(ManagedTransportAdapter::isRunning)
                && transportServers.stream().allMatch(TransportServer::isRunning)
                && (!engineExpected || engine == null || engine.isRunning());
    }

    public PullWorkerSession openPullWorkerSession(String workerId) {
        return openPullWorkerSession(workerId, UUID.randomUUID().toString());
    }

    public PullWorkerSession openPullWorkerSession(String workerId, String sessionToken) {
        if (transportRuntimeRegistry == null) {
            throw new IllegalStateException("Pull worker transport is unavailable for this runtime");
        }
        WorkerResourceRecord worker = requireWorkerResource(workerId);
        ResolvedPullWorkerTransport resolved = transportRuntimeRegistry.resolvePullWorkerTransport(
                worker.workerId(),
                worker.workerGroupId(),
                worker.adapterId(),
                worker.onlineStrategy()
        );
        return new PullWorkerSession(
                resolved.getWorkerId(),
                resolved.getWorkerGroupId(),
                resolved.getAdapterId(),
                requireText(sessionToken, "sessionToken"),
                resolved.getTaskPullChannel(),
                resolved.getTaskResultIngestChannel(),
                resolved.getRouteOwnerStore(),
                resolved.getTransportHint()
        );
    }

    public void publishWorkerOnline(String workerId, String reason, String traceId) {
        requireWorkerSystemEventChannel().publishWorkerOnline(
                requireText(workerId, "workerId"),
                normalizeNullableReason(reason),
                traceId
        );
    }

    public void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
        requireWorkerSystemEventChannel().publishWorkerHeartbeat(
                requireText(workerId, "workerId"),
                normalizeNullableReason(reason),
                traceId
        );
    }

    public void publishWorkerOffline(String workerId, String reason, String traceId) {
        requireWorkerSystemEventChannel().publishWorkerOffline(
                requireText(workerId, "workerId"),
                normalizeNullableReason(reason),
                traceId
        );
    }

    private WorkerSystemEventChannel requireWorkerSystemEventChannel() {
        WorkerSystemEventChannel channel = workerSystemEventChannel;
        if (channel == null) {
            throw new IllegalStateException("Worker system event channel is unavailable before transport runtime start");
        }
        return channel;
    }

    private static String normalizeNullableReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
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

    private static int parseAttemptNo(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    public boolean sendRawTransportMessage(String workerId, String rawJson, String traceId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (rawJson == null) {
            throw new IllegalArgumentException("rawJson must not be null");
        }
        if (rawWorkerMessageChannelsByAdapterId.isEmpty() || transportRuntimeRegistry == null) {
            return false;
        }
        String normalizedWorkerId = workerId.trim();
        String workerAdapterId = resolveWorkerAdapterId(normalizedWorkerId);
        RawWorkerMessageChannel rawWorkerMessageChannel = rawWorkerMessageChannelsByAdapterId.get(
                workerAdapterId == null ? null : workerAdapterId.trim().toLowerCase(java.util.Locale.ROOT)
        );
        if (rawWorkerMessageChannel == null) {
            return false;
        }
        String routeKey = resolveRawMessageRouteKey(normalizedWorkerId, workerAdapterId);
        if (routeKey == null) {
            logger.debug("Skip raw transport side-channel because no unique active route is available: workerId={}, adapterId={}",
                    normalizedWorkerId, workerAdapterId);
            return false;
        }
        rawWorkerMessageChannel.sendToAdapterRoute(routeKey, rawJson, traceId);
        return true;
    }

    private void configureRealtimeWorkerCommandDelivery() {
        if (!engineConfig.isEnabled()
                || transportRuntimeComposition.getRuntimeRole() == TransportRuntimeRole.TRANSPORT_CONSUMER
                || rawWorkerMessageChannelsByAdapterId.isEmpty()) {
            return;
        }
        engineConfig.getWorkerControlRuntime().setCommandDeliveryPort(
                this::deliverRealtimeWorkerCommand,
                task -> {
                    if (transportRuntimeTaskExecutor == null) {
                        task.run();
                    } else {
                        transportRuntimeTaskExecutor.submit(task);
                    }
                }
        );
    }

    private WorkerCommandDeliveryResult deliverRealtimeWorkerCommand(WorkerCommandRecord command) {
        if (command == null || command.workerId() == null || command.workerId().isBlank()) {
            return WorkerCommandDeliveryResult.rejected("worker command missing workerId");
        }
        String normalizedWorkerId = command.workerId().trim();
        RawWorkerMessageChannel rawWorkerMessageChannel;
        try {
            rawWorkerMessageChannel = resolveRawWorkerMessageChannel(normalizedWorkerId);
        } catch (RuntimeException e) {
            return WorkerCommandDeliveryResult.workerUnavailable("worker command route resolution failed: " + e.getMessage());
        }
        if (rawWorkerMessageChannel == null) {
            return WorkerCommandDeliveryResult.deferred("worker has no realtime command carrier");
        }
        boolean sent = sendRawTransportMessage(
                normalizedWorkerId,
                encodeWorkerCommandFrame(command),
                "worker-command-" + command.commandId()
        );
        return sent
                ? WorkerCommandDeliveryResult.accepted("command sent to realtime worker route")
                : WorkerCommandDeliveryResult.workerUnavailable("worker realtime route unavailable for command delivery");
    }

    private RawWorkerMessageChannel resolveRawWorkerMessageChannel(String workerId) {
        if (transportRuntimeRegistry == null || workerId == null || workerId.isBlank()) {
            return null;
        }
        String workerAdapterId = resolveWorkerAdapterId(workerId.trim());
        if (workerAdapterId == null || workerAdapterId.isBlank()) {
            return null;
        }
        return rawWorkerMessageChannelsByAdapterId.get(workerAdapterId.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public String resolveWorkerAdapterId(String workerId) {
        return resolveTransportBinding(requireWorkerResource(workerId)).getAdapterId();
    }

    public String resolveWorkerTransportHint(String workerId) {
        return resolveTransportBinding(requireWorkerResource(workerId)).getTransportHint();
    }

    public WorkerDispatchRouteOwnerView getWorkerRouteOwnerView() {
        return workerRouteOwnerView;
    }

    private String encodeWorkerCommandFrame(WorkerCommandRecord command) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "worker.command");
        frame.put("commandId", command.commandId());
        frame.put("workerId", command.workerId());
        frame.put("commandType", command.commandType());
        frame.put("payload", command.payload());
        if (command.deadlineEpochMillis() != null) {
            frame.put("deadlineEpochMillis", command.deadlineEpochMillis());
        }
        if (command.createdAt() != null) {
            frame.put("requestedAt", command.createdAt().toString());
        }
        return TRANSPORT_JSON.toJson(frame);
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

    private static String requireRawWorkerMessageAdapterId(RawWorkerMessageChannel rawWorkerMessageChannel) {
        String adapterId = rawWorkerMessageChannel.adapterId();
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalStateException("Raw worker message channel must declare a non-blank adapterId");
        }
        return adapterId.trim().toLowerCase(java.util.Locale.ROOT);
    }

}
