package com.xa.mass.starter;

import com.google.gson.Gson;
import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
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
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSessions;
import com.xa.mass.sdk.worker.EmbeddedPullWorkerSession;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.runtime.EmbeddedAdapterHostSet;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.RedisTransportResultIngressChannel;
import com.xa.mass.transport.runtime.ResolvedPullWorkerTransport;
import com.xa.mass.transport.runtime.TransportAdapterContribution;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.BufferedTransportResultIngressChannel;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.TransportResultIngressInboxPump;
import com.xa.mass.transport.runtime.embedded.TransportDeliveryFailureEvidenceSink;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopAdapterMailboxConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.RedisTransportDeliveryFailureChannel;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureInboxPump;
import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Gson TRANSPORT_JSON = new Gson();
    private static final int DEFAULT_DISPATCH_HANDOFF_CAPACITY =
            Integer.getInteger("xa.mass.engine.dispatchHandoffCapacity", 10_000);

    private final TransportRuntimeComposition transportRuntimeComposition;
    private final EngineConfig engineConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final MassEventRuntime eventRuntime;
    private final Map<String, RawWorkerMessageChannel> rawWorkerMessageChannelsByAdapterId = new LinkedHashMap<>();

    private final MassEngine engine;
    private TransportRuntimeRegistry transportRuntimeRegistry;
    private TransportEndpointLeaseStore endpointLeaseStore;
    private TransportDispatchHandoff transportDispatchHandoff;
    private EmbeddedAdapterHostSet embeddedAdapterHostSet = EmbeddedAdapterHostSet.empty();
    private RedisTransportResultIngressChannel taskResultInbox;
    private TransportResultIngressInboxPump taskResultInboxPump;
    private RedisTransportDeliveryFailureChannel deliveryFailureInbox;
    private TransportDeliveryFailureInboxPump deliveryFailureInboxPump;
    private RuntimeTaskExecutor transportRuntimeTaskExecutor;
    private RuntimeTaskExecutor eventRuntimeTaskExecutor;
    private BufferedTransportResultIngressChannel bufferedResultIngestChannel;
    private WorkerPresenceIngress workerPresenceIngress;

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

            startEmbeddedAdapterHostSet();
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
                stopEmbeddedAdapterHostSet();
                TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
                if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                    stopDispatchHandoff();
                    stopDistributedTransportInboxes();
                } else if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    stopDistributedTransportInboxes();
                }
                drainResultIngestBuffer();

                if (engine != null && engineConfig.isEnabled()) {
                    engine.stop();
                }
            } finally {
                stopDispatchHandoff();
                closeDistributedTransportInboxes();
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
            stopEmbeddedAdapterHostSet();
            stopDispatchHandoff();
            stopDistributedTransportInboxes();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to stop embedded adapter runtime after startup failure", cleanupError);
        }
        try {
            drainResultIngestBuffer();
        } catch (Exception cleanupError) {
            startupFailure.addSuppressed(cleanupError);
            logger.warn("Failed to drain result ingest buffer after startup failure", cleanupError);
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
            rawWorkerMessageChannelsByAdapterId.clear();
            embeddedAdapterHostSet = EmbeddedAdapterHostSet.empty();
            startEventRuntimeTaskExecutor();
            WorkerPresenceIngress presenceIngress = resolveWorkerPresenceIngress();
            workerPresenceIngress = presenceIngress;
            endpointLeaseStore = transportRuntimeComposition.resolveTransportEndpointLeaseStore();
            transportRuntimeTaskExecutor = new VirtualThreadRuntimeTaskExecutor(
                    "transport-runtime-",
                    transportRuntimeComposition.getTransportRuntimeMaxPendingTasks()
            );
            TransportRuntimeRole runtimeRole = transportRuntimeComposition.getRuntimeRole();
            validateWorkerDeliveryTargetResolverConfiguration(runtimeRole);
            AdapterMailboxConsumerRegistry mailboxConsumerRegistry = NoopAdapterMailboxConsumerRegistry.INSTANCE;
            if (requiresDispatchHandoff(runtimeRole)) {
                transportDispatchHandoff =
                        transportRuntimeComposition.resolveTransportDispatchHandoff(DEFAULT_DISPATCH_HANDOFF_CAPACITY);
                if (transportDispatchHandoff instanceof AdapterMailboxConsumerRegistry registry) {
                    mailboxConsumerRegistry = registry;
                }
            }
            TaskDispatchBatchListener taskDispatchListener = null;
            TransportResultIngressChannel resultIngressChannel = null;
            TransportDeliveryFailureHandler adapterHostFailureHandler = null;
            List<TransportBinding> adapterBindings = new ArrayList<>();
            List<TransportAdapterContribution> adapterContributions = new ArrayList<>();
            if (runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER) {
                taskResultInbox = transportRuntimeComposition.resolveTaskResultInbox();
                resultIngressChannel = taskResultInbox;
                deliveryFailureInbox = transportRuntimeComposition.resolveDeliveryFailureInbox();
                adapterHostFailureHandler = deliveryFailureInbox;
                logger.info("Task result ingest channel initialized (redis inbox producer)");
            } else if (engineConfig.isEnabled()) {
                TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
                BufferedTransportResultIngressChannel buffer = new BufferedTransportResultIngressChannel(
                        new RuntimeTaskResultIngestChannel(taskResultIngestFacade));
                bufferedResultIngestChannel = buffer;
                resultIngressChannel = buffer;
                adapterHostFailureHandler = createTransportDeliveryFailureHandler();
                logger.info("Task result ingest channel initialized (buffered async)");
                if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER) {
                    taskResultInbox = transportRuntimeComposition.resolveTaskResultInbox();
                    taskResultInboxPump = new TransportResultIngressInboxPump(
                            taskResultInbox,
                            new RuntimeTaskResultIngestChannel(taskResultIngestFacade),
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
            if (resultIngressChannel == null && runtimeRole == TransportRuntimeRole.EMBEDDED) {
                resultIngressChannel = envelope -> false;
                logger.info("Task result ingest channel initialized (noop because engine is disabled)");
            }

            if (runtimeRole != TransportRuntimeRole.ENGINE_PRODUCER) {
                int bootstrapIndex = 0;
                for (TransportAdapterBootstrap transportAdapterBootstrap
                        : transportRuntimeComposition.resolveTransportAdapterBootstraps()) {
                    TransportAdapterDescriptor descriptor = transportAdapterBootstrap.descriptor();
                    String assignedMailboxKey = assignedAdapterMailboxKey(descriptor, bootstrapIndex++);
                    TransportAdapterBootstrapContext bootstrapContext = new TransportAdapterBootstrapContext(
                            descriptor,
                            assignedMailboxKey,
                            resultIngressChannel,
                            presenceIngress,
                            endpointLeaseStore,
                            transportRuntimeTaskExecutor,
                            transportDispatchHandoff == null ? null : transportDispatchHandoff::poll,
                            new TransportDeliveryFailureEvidenceSink(adapterHostFailureHandler),
                            mailboxConsumerRegistry,
                            transportRuntimeComposition.getAdapterMailboxConsumerAvailabilityMillis()
                    );
                    TransportAdapterContribution contribution = transportAdapterBootstrap.contribute(bootstrapContext);
                    if (contribution == null) {
                        contribution = TransportAdapterContribution.empty();
                    }
                    contribution.validateAgainst(descriptor, assignedMailboxKey);
                    registerTransportAdapterContribution(contribution, adapterBindings, adapterContributions);
                }
            }

            if (runtimeRole != TransportRuntimeRole.ENGINE_PRODUCER) {
                transportRuntimeRegistry = transportRuntimeComposition.resolveWorkerTransportRuntimeFactory().create(
                        resultIngressChannel,
                        endpointLeaseStore,
                        adapterBindings
                );
                embeddedAdapterHostSet = EmbeddedAdapterHostSet.fromContributions(
                        adapterContributions
                );
                configureRealtimeWorkerCommandDelivery();
            }
            if (engineConfig.isEnabled() && runtimeRole != TransportRuntimeRole.TRANSPORT_CONSUMER) {
                taskDispatchListener = createDispatchSubmitter(transportDispatchHandoff);
            }
            return taskDispatchListener;
        } catch (Exception e) {
            try {
                stopDispatchHandoff();
                stopDistributedTransportInboxes();
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
        TaskDispatchDeliveryCorrelationCodec correlationCodec = new TaskDispatchDeliveryCorrelationCodec();
        return event -> {
            if (event == null || event.outcome() == null) {
                return false;
            }
            DispatchOutcome outcome = event.outcome();
            TaskDispatchDeliveryCorrelation correlation;
            try {
                correlation = correlationCodec.decode(outcome.getCorrelationRef());
            } catch (RuntimeException e) {
                logger.error("Cannot compensate delivery failure because correlation is incomplete: deliveryId={}, reason={}",
                        outcome.getDeliveryId(), e.getMessage());
                return false;
            }
            String taskId = correlation.taskId();
            Task storedTask = engineConfig.getTaskShellStore().getTask(taskId).orElse(null);
            if (storedTask == null) {
                logger.error("Cannot compensate delivery failure because task {} is missing", taskId);
                return false;
            }
            TaskDispatchDeliveryFailure failure;
            try {
                failure = toDeliveryFailure(event);
            } catch (RuntimeException e) {
                logger.error("Cannot compensate delivery failure because failure record is incomplete: deliveryId={}, reason={}",
                        outcome.getDeliveryId(), e.getMessage());
                return false;
            }
            return engineConfig.getTaskAssignmentRuntimePort()
                    .compensateDispatchDeliveryFailure(storedTask, List.of(failure));
        };
    }

    private boolean requiresDispatchHandoff(TransportRuntimeRole runtimeRole) {
        return runtimeRole == TransportRuntimeRole.TRANSPORT_CONSUMER
                || (engineConfig.isEnabled() && runtimeRole != TransportRuntimeRole.TRANSPORT_CONSUMER);
    }

    private void validateWorkerDeliveryTargetResolverConfiguration(TransportRuntimeRole runtimeRole) {
        if (runtimeRole == TransportRuntimeRole.ENGINE_PRODUCER
                && engineConfig.isEnabled()
                && !engineConfig.isWorkerDeliveryTargetResolverExplicitlyConfigured()) {
            throw new IllegalStateException(
                    "engine-producer runtime requires an explicit worker delivery target resolver; "
                            + "local worker presence is only a valid default for embedded runtime"
            );
        }
    }

    private TaskDispatchDeliveryFailure toDeliveryFailure(TransportDeliveryFailureEvent event) {
        DispatchOutcome outcome = event.outcome();
        TaskDispatchDeliveryCorrelation correlation =
                new TaskDispatchDeliveryCorrelationCodec().decode(outcome.getCorrelationRef());
        return new TaskDispatchDeliveryFailure(
                correlation.taskId(),
                correlation.messageId(),
                correlation.attemptId(),
                correlation.attemptNo(),
                outcome.getSelectedWorkerId(),
                firstNonBlank(event.detail(), outcome.getReason())
        );
    }

    private TaskDispatchBatchListener createDispatchSubmitter(TransportDispatchHandoff handoff) {
        TransportAssignedDeliverySubmitter assignedDeliverySubmitter = new TransportAssignedDeliverySubmitter(
                handoff,
                createTransportDeliveryFailureHandler()
        );
        return new TaskDispatchRoutingSubmitter(
                assignedDeliverySubmitter,
                createTransportDeliveryFailureHandler(),
                engineConfig::resolveWorkerDeliveryTarget
        );
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
        try {
            return transportRuntimeRegistry.resolveBinding(null, worker.transportHint());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot resolve transport binding for worker " + worker.workerId()
                    + ": " + e.getMessage(), e);
        }
    }

    private void startEmbeddedAdapterHostSet() {
        embeddedAdapterHostSet.start();
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

    private void stopEmbeddedAdapterHostSet() {
        EmbeddedAdapterHostSet runtimeSet = embeddedAdapterHostSet;
        embeddedAdapterHostSet = EmbeddedAdapterHostSet.empty();
        runtimeSet.stop();
    }

    private void drainResultIngestBuffer() {
        BufferedTransportResultIngressChannel buffer = bufferedResultIngestChannel;
        bufferedResultIngestChannel = null;
        if (buffer != null) {
            logger.info("Draining result ingest buffer");
            buffer.shutdown();
            logger.info("Result ingest buffer drained");
        }
    }

    private void stopEndpointLeaseStore() throws Exception {
        TransportEndpointLeaseStore store = endpointLeaseStore;
        endpointLeaseStore = null;
        if (store instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }

    private void stopDispatchHandoff() {
        TransportDispatchHandoff handoff = transportDispatchHandoff;
        transportDispatchHandoff = null;
        if (handoff != null) {
            handoff.shutdown();
        }
    }

    private void stopDistributedTransportInboxes() {
        stopDistributedTransportInboxPumps();
        closeDistributedTransportInboxes();
    }

    private void stopDistributedTransportInboxPumps() {
        TransportResultIngressInboxPump resultPump = taskResultInboxPump;
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
        RedisTransportResultIngressChannel resultInbox = taskResultInbox;
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
        workerPresenceIngress = null;
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

    private WorkerPresenceIngress resolveWorkerPresenceIngress() {
        WorkerPresenceIngress configuredIngress = transportRuntimeComposition.resolveWorkerPresenceIngress();
        if (configuredIngress != null && configuredIngress != NoopWorkerPresenceIngress.INSTANCE) {
            return configuredIngress;
        }
        if (!engineConfig.isEnabled()) {
            return NoopWorkerPresenceIngress.INSTANCE;
        }
        return new WorkerRuntimePresenceIngress(
                engineConfig.getWorkerPresenceRuntime(),
                engineConfig.getWorkerDispatchBlockRuntime(),
                engineConfig.getExecutionEventSink()
        );
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

    private void registerTransportAdapterContribution(TransportAdapterContribution contribution,
                                                      List<TransportBinding> adapterBindings,
                                                      List<TransportAdapterContribution> adapterContributions) {
        TransportAdapterContribution next = contribution != null
                ? contribution
                : TransportAdapterContribution.empty();
        adapterContributions.add(next);
        adapterBindings.addAll(next.getTransportBindings());
        for (RawWorkerMessageChannel rawWorkerMessageChannel : next.getRawWorkerMessageChannels()) {
            registerRawWorkerMessageChannel(rawWorkerMessageChannel);
        }
    }

    private static String assignedAdapterMailboxKey(TransportAdapterDescriptor descriptor, int index) {
        if (descriptor != null) {
            return descriptor.getAdapterId();
        }
        return "bootstrap-" + index;
    }

    public boolean isRunning() {
        boolean engineExpected = engineConfig.isEnabled()
                && transportRuntimeComposition.getRuntimeRole() != TransportRuntimeRole.TRANSPORT_CONSUMER;
        return running.get()
                && embeddedAdapterHostSet.isRunning()
                && (!engineExpected || engine == null || engine.isRunning());
    }

    public EmbeddedPullWorkerSession openEmbeddedPullWorkerSession(String workerId) {
        return openEmbeddedPullWorkerSession(workerId, UUID.randomUUID().toString());
    }

    public EmbeddedPullWorkerSession openEmbeddedPullWorkerSession(String workerId, String sessionToken) {
        if (transportRuntimeRegistry == null) {
            throw new IllegalStateException("Pull worker transport is unavailable for this runtime");
        }
        WorkerResourceRecord worker = requireWorkerResource(workerId);
        ResolvedPullWorkerTransport resolved = transportRuntimeRegistry.resolvePullWorkerTransport(
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
        if (!rawWorkerMessageChannel.sendToWorker(normalizedWorkerId, rawJson, traceId)) {
            logger.debug("Skip raw transport side-channel because no active worker session is available: workerId={}, adapterId={}",
                    normalizedWorkerId, workerAdapterId);
            return false;
        }
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

    public Map<String, Object> getTransportQueueDetail() {
        return TransportQueueDiagnosticsMapper.toQueueDetail(
                transportRuntimeTaskExecutor,
                eventRuntimeTaskExecutor
        );
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
