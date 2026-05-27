package com.xa.mass.sdk;

import com.xa.mass.base.channel.messaging.api.MessageQueue;
import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.event.DeliveryAcknowledgementMode;
import com.xa.mass.base.event.EventConvergenceMode;
import com.xa.mass.base.event.PriorityClass;
import com.xa.mass.base.event.ResponseMode;
import com.xa.mass.base.event.TargetScope;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.command.event.CoreEventDescriptor;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskWorkLogicallyFinalEvent;
import com.xa.mass.engine.TaskWorkLogicallyFinalListener;
import com.xa.mass.engine.TaskWorkProjectionState;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskAppendReceipt;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.runtime.worker.EventBinding;
import com.xa.mass.runtime.worker.WorkerGroupRecord;
import com.xa.mass.runtime.worker.WorkerResourceRecord;
import com.xa.mass.engine.watchdog.PollingIdleBackoffPolicy;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.runtime.worker.WorkerResourceRuntime;
import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.storage.rule.RuleType;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.transport.socket.runtime.SocketAdapterConfig;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import com.xa.mass.transport.websocket.session.ServerSessionManager;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterProfile;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.event.PlatformEventCodes;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.internal.DefaultTransportDebugOperations;
import com.xa.mass.sdk.internal.TransportDebugOperations;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.MassTaskUpdateRequest;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskWorkFinalNotification;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.sdk.authz.TaskOwnershipSupport;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.builder.MassApplicationBuilder;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.config.TransportRuntimeComposition;
import com.xa.mass.starter.config.TransportRuntimeRole;
import com.xa.mass.transport.runtime.CompositeWorkerEndpointRegistry;
import com.xa.mass.transport.runtime.RuntimeEventBusWorkerSystemEventChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRegistrationResolver;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.runtime.TransportServerFactoryContext;
import com.xa.mass.transport.runtime.WorkerTransportRuntimeFactory;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollResult;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStoreStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.presence.WorkerPresenceStore;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import com.xa.mass.transport.worker.WorkerAdapter;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.TransportServerFactory;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MassSdkTest {

    @Test
    void builderCreatesConsumerFacingApplicationHandle() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(19090, "/sdk-transport")
                                .enabled(false)
                                .serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void engineOptionsExposeChaosTuningKnobs() {
        PollingIdleBackoffPolicy idleBackoffPolicy = decision -> 250L;
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine
                        .enabled(true)
                        .assignmentRetryDelayMillis(125L)
                        .runtimeReadyDispatchIdleBackoffMaxMillis(2_000L)
                        .runtimeReadyDispatchIdleBackoffPolicy(idleBackoffPolicy)
                        .leaseWatchdogIntervalSeconds(3L)
                        .taskMessageLeaseSeconds(7L))
                .build();

        MassEngine engine = requireDelegate(app).getEngine();

        assertNotNull(engine);
        assertEquals(125L, engine.getConfig().getAssignmentRetryDelayMillis());
        assertEquals(2_000L, engine.getConfig().getRuntimeReadyDispatchIdleBackoffMaxMillis());
        assertEquals(idleBackoffPolicy, engine.getConfig().getRuntimeReadyDispatchIdleBackoffPolicy());
        assertEquals(3L, engine.getConfig().getLeaseWatchdogIntervalSeconds());
        assertEquals(7L, engine.getConfig().getTaskMessageLeaseSeconds());
    }

    @Test
    void builderProjectCatalogBootstrapSeedsProjects() {
        ProjectEventCatalogRegistry bootstrapRegistry = new ProjectEventCatalogRegistry();
        bootstrapRegistry.registerProject(ProjectDefinition.builder()
                .code("seedApp")
                .name("Seed App")
                .description("builder bootstrap project")
                .build());

        MassSdkApplication app = MassSdk.builder()
                .projectCatalogBootstrap(bootstrapRegistry)
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app.getProject("seedApp"));
        assertEquals("Seed App", app.getProject("seedApp").getName());
    }

    @Test
    void customTransportServerFactoryOverridesBundledWebSocketAdapter() {
        AtomicReference<TransportServerFactoryContext> capturedContext = new AtomicReference<>();
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean stopped = new AtomicBoolean(false);
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("transport-input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("transport-output", TransportOutboundMessage.class);

        TransportServerFactory<TransportServerFactoryContext> factory = context -> {
            capturedContext.set(context);
            return new TransportServer() {
                @Override
                public void start() {
                    started.set(true);
                }

                @Override
                public void stop() {
                    stopped.set(true);
                }

                @Override
                public boolean isRunning() {
                    return started.get() && !stopped.get();
                }
            };
        };

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(19092, "/custom-transport")
                                .enabled(false)
                                .serverEnabled(true)
                                .transportServerFactory(factory))
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(false))
                .build();

        try {
            app.start();
            assertTrue(app.isRunning());
            assertNotNull(capturedContext.get());
            Assertions.assertEquals(19092, capturedContext.get().getPort());
            Assertions.assertEquals("/custom-transport", capturedContext.get().getEndpointPath());
            assertNotNull(capturedContext.get().getEndpointRegistry());
        } finally {
            app.stop();
        }

        assertTrue(started.get());
        assertTrue(stopped.get());
    }

    @Test
    void additionalAdapterBootstrapCanStartServerOnDedicatedPort() {
        AtomicInteger startedPort = new AtomicInteger(-1);
        AtomicBoolean stopped = new AtomicBoolean(false);
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("transport-input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("transport-output", TransportOutboundMessage.class);

        TransportServer dedicatedServer = new TransportServer() {
            private boolean running;

            @Override
            public void start() {
                startedPort.set(19193);
                running = true;
            }

            @Override
            public void stop() {
                running = false;
                stopped.set(true);
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(19092, "/default-transport")
                                .enabled(false)
                                .serverEnabled(false))
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .addSupplementalTransportAdapterBootstrap(new StaticDedicatedServerBootstrap(dedicatedServer)))
                .engine(engine -> engine.enabled(false))
                .build();

        try {
            app.start();
            assertTrue(app.isRunning());
            assertEquals(19193, startedPort.get());
        } finally {
            app.stop();
        }

        assertTrue(stopped.get());
    }

    @Test
    void bundledWebSocketEndpointRegistryIsMemoizedPerRuntimeCompositionAndIsolatedAcrossSnapshots() {
        TransportConfig config = new TransportConfig();
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        TransportRuntimeComposition secondSnapshot = config.snapshotRuntimeComposition();

        WorkerEndpointRegistry first = runtimeComposition.resolveWorkerEndpointRegistry();
        WorkerEndpointRegistry second = runtimeComposition.resolveWorkerEndpointRegistry();
        WorkerEndpointRegistry snapshotRegistry = secondSnapshot.resolveWorkerEndpointRegistry();

        assertSame(first, second);
        assertInstanceOf(CompositeWorkerEndpointRegistry.class, first);
        assertInstanceOf(CompositeWorkerEndpointRegistry.class, snapshotRegistry);
        assertNotSame(first, snapshotRegistry);
    }

    @Test
    void bundledWebSocketSystemEventChannelSharesRuntimeOwnedEndpointRegistry() {
        TransportConfig config = new TransportConfig();
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        WorkerEndpointRegistry endpointRegistry = runtimeComposition.resolveWorkerEndpointRegistry();
        WorkerSystemEventChannel systemEventChannel = runtimeComposition.resolveSystemEventChannel();

        assertInstanceOf(CompositeWorkerEndpointRegistry.class, endpointRegistry);
        assertInstanceOf(RuntimeEventBusWorkerSystemEventChannel.class, systemEventChannel);
    }

    @Test
    void runtimeCompositionExposesAdapterOwnedConfigSnapshots() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setServerPort(19095);
        config.getBundledWebSocketAdapterConfig().setEndpointPath("/runtime-ws");
        config.getBundledSocketAdapterConfig().setEnabled(true);
        config.getBundledSocketAdapterConfig().setServerEnabled(true);
        config.getBundledSocketAdapterConfig().setServerPort(18123);
        config.getBundledSocketAdapterConfig().setBindHost("127.0.0.1");

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        WebSocketAdapterConfig webSocketConfig = runtimeComposition.getBundledWebSocketAdapterConfig();
        SocketAdapterConfig socketConfig = runtimeComposition.getBundledSocketAdapterConfig();

        assertEquals(19095, webSocketConfig.getServerPort());
        assertEquals("/runtime-ws", webSocketConfig.getEndpointPath());
        assertTrue(socketConfig.isEnabled());
        assertTrue(socketConfig.isServerEnabled());
        assertEquals(18123, socketConfig.getServerPort());
        assertEquals("127.0.0.1", socketConfig.getBindHost());

        webSocketConfig.setServerPort(19999);
        socketConfig.setServerPort(19998);
        assertEquals(19095, runtimeComposition.getBundledWebSocketAdapterConfig().getServerPort());
        assertEquals(18123, runtimeComposition.getBundledSocketAdapterConfig().getServerPort());
    }

    @Test
    void sdkBundledSocketServerRegistersHelloSession() throws Exception {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .enabled(false)
                                .serverEnabled(false))
                        .socketAdapter(socket -> socket
                                .server(0)
                                .enabled(true)
                                .serverEnabled(true))
                        .inputQueue(new InMemoryMessageQueue<>("socket-hello-input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>("socket-hello-output", TransportOutboundMessage.class))
                        .queueMode())
                .engine(engine -> engine.enabled(true))
                .build();

        try {
            app.start();
            int port = Integer.parseInt(System.getProperty(
                    com.xa.mass.transport.socket.server.SocketTransportServer.BOUND_PORT_PROPERTY));
            try (Socket socket = new Socket("127.0.0.1", port);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader ignoredReader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write("{\"type\":\"hello\",\"workerId\":\"sdk-socket-worker\"}");
                writer.newLine();
                writer.flush();

                waitUntil(() -> runtimeDiagnostics(app).listSessions().stream().anyMatch(MassSdkTest::hasActiveSocketConnection),
                        "sdk socket hello should register an active socket session");
            }
        } finally {
            app.stop();
            System.clearProperty(com.xa.mass.transport.socket.server.SocketTransportServer.BOUND_PORT_PROPERTY);
        }
    }

    @Test
    void runtimeCompositionResolvesRuntimeOwnedCollaborators() {
        TransportConfig config = new TransportConfig();
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        WorkerEndpointRegistry firstRegistry = runtimeComposition.resolveWorkerEndpointRegistry();
        WorkerEndpointRegistry secondRegistry = runtimeComposition.resolveWorkerEndpointRegistry();
        assertSame(firstRegistry, secondRegistry);

        WorkerEndpointRegistry overriddenRegistry = mock(WorkerEndpointRegistry.class);
        config.setWorkerEndpointRegistry(overriddenRegistry);
        WorkerSystemEventChannel customSystemEventChannel = mock(WorkerSystemEventChannel.class);
        config.setCustomSystemEventChannel(customSystemEventChannel);
        WorkerTransportRuntimeFactory customFactory = (workerResourceRuntime,
                                                     taskResultIngestChannel,
                                                     systemEventChannel,
                                                     workerPresenceStore,
                                                     deliveryService,
                                                     adapterBindings) -> mock(TransportRuntimeRegistry.class);
        config.setWorkerTransportRuntimeFactory(customFactory);

        TransportRuntimeComposition customizedRuntimeComposition = config.snapshotRuntimeComposition();

        assertSame(overriddenRegistry, customizedRuntimeComposition.resolveWorkerEndpointRegistry());
        assertSame(customSystemEventChannel, customizedRuntimeComposition.resolveSystemEventChannel());
        assertSame(customFactory, customizedRuntimeComposition.resolveWorkerTransportRuntimeFactory());
    }

    @Test
    void runtimeCompositionResolvesCustomTransportDeliveryStoreFactory() {
        TransportConfig config = new TransportConfig();
        StubTransportDeliveryStore store = new StubTransportDeliveryStore();
        config.setDeliveryStoreFactory(() -> store);

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        assertSame(store, runtimeComposition.resolveTransportDeliveryStore());
    }

    @Test
    void transportRuntimeRoleDefaultsToEmbeddedAndSnapshotsConfiguredValue() {
        TransportConfig config = new TransportConfig();
        assertEquals(TransportRuntimeRole.EMBEDDED, config.snapshotRuntimeComposition().getRuntimeRole());

        config.setRuntimeRole(TransportRuntimeRole.ENGINE_PRODUCER);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        config.setRuntimeRole(TransportRuntimeRole.TRANSPORT_CONSUMER);

        assertEquals(TransportRuntimeRole.ENGINE_PRODUCER, runtimeComposition.getRuntimeRole());
        assertEquals(TransportRuntimeRole.TRANSPORT_CONSUMER, config.snapshotRuntimeComposition().getRuntimeRole());

        config.setRuntimeRole(null);
        assertEquals(TransportRuntimeRole.EMBEDDED, config.snapshotRuntimeComposition().getRuntimeRole());
    }

    @Test
    void sdkTransportOptionsExposeDistributedRuntimeRoleAndRedisChannels() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .transportRuntimeRole(TransportRuntimeRole.TRANSPORT_CONSUMER)
                        .redisDistributedChannels("redis://localhost:6379", "test:xa:distributed")
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(true))
                .build();

        TransportRuntimeComposition runtimeComposition = readField(
                requireDelegate(app),
                "transportRuntimeComposition",
                TransportRuntimeComposition.class
        );

        assertEquals(TransportRuntimeRole.TRANSPORT_CONSUMER, runtimeComposition.getRuntimeRole());
        assertNull(requireDelegate(app).getEngine());
    }

    @Test
    void runtimeCompositionWebSocketBootstrapReflectsCurrentNestedAdapterConfig() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        assertEquals(
                List.of(),
                runtimeComposition.resolveTransportAdapterBootstraps().stream()
                        .map(TransportAdapterBootstrap::descriptor)
                        .filter(Objects::nonNull)
                        .map(TransportAdapterDescriptor::getAdapterId)
                        .filter("websocket"::equals)
                        .toList()
        );
    }

    @Test
    void runtimeCompositionSocketBootstrapReflectsCurrentNestedAdapterConfig() {
        TransportConfig config = new TransportConfig();
        config.getBundledSocketAdapterConfig().setEnabled(true);
        config.getBundledSocketAdapterConfig().setServerEnabled(true);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        TransportAdapterBootstrapContext bootstrapContext;
        try {
            bootstrapContext = new TransportAdapterBootstrapContext(
                    new CompositeWorkerEndpointRegistry(),
                    mock(TaskResultIngestChannel.class),
                    new RuntimeEventBusWorkerSystemEventChannel(),
                    new InMemoryWorkerPresenceStore(),
                    deliveryService(),
                    runtimeTaskExecutor
            );
            adapterBootstrap(runtimeComposition, "socket").contribute(bootstrapContext);
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertNotNull(bootstrapContext.getTransportBinding());
        assertNotNull(bootstrapContext.getTransportServer());
        assertNotNull(bootstrapContext.getRawWorkerMessageChannel());
    }

    @Test
    void runtimeCompositionWebSocketBootstrapNoLongerContributesManagedAdapter() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(true);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(true);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        TransportAdapterBootstrapContext bootstrapContext;
        try {
            bootstrapContext = new TransportAdapterBootstrapContext(
                    new CompositeWorkerEndpointRegistry(),
                    mock(TaskResultIngestChannel.class),
                    new RuntimeEventBusWorkerSystemEventChannel(),
                    new InMemoryWorkerPresenceStore(),
                    deliveryService(),
                    runtimeTaskExecutor
            );
            adapterBootstrap(runtimeComposition, "websocket").contribute(bootstrapContext);
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertNotNull(bootstrapContext.getTransportBinding());
        assertNull(bootstrapContext.getManagedTransportAdapter());
        assertNotNull(bootstrapContext.getTransportServer());
        assertNotNull(bootstrapContext.getRawWorkerMessageChannel());
    }

    @Test
    void runtimeCompositionWebSocketBootstrapUsesConfiguredAdapterId() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setAdapterId("ws-public");
        config.getBundledWebSocketAdapterConfig().setEnabled(true);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        TransportAdapterBootstrapContext bootstrapContext;
        try {
            bootstrapContext = new TransportAdapterBootstrapContext(
                    new CompositeWorkerEndpointRegistry(),
                    mock(TaskResultIngestChannel.class),
                    new RuntimeEventBusWorkerSystemEventChannel(),
                    new InMemoryWorkerPresenceStore(),
                    deliveryService(),
                    runtimeTaskExecutor
            );
            adapterBootstrap(runtimeComposition, "ws-public").contribute(bootstrapContext);
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertEquals("ws-public", adapterBootstrap(runtimeComposition, "ws-public").descriptor().getAdapterId());
        assertNotNull(bootstrapContext.getTransportBinding());
        assertEquals("ws-public", bootstrapContext.getTransportBinding().getWorkerAdapter().protocol());
        assertEquals("ws-public", bootstrapContext.getRawWorkerMessageChannel().adapterId());
        assertEquals("ws-public",
                runtimeComposition.resolveRegistrationAdapterId("ws-public", WorkerTransportHints.REALTIME));
    }

    @Test
    void runtimeCompositionSocketBootstrapUsesConfiguredAdapterId() {
        TransportConfig config = new TransportConfig();
        config.getBundledSocketAdapterConfig().setAdapterId("socket-edge");
        config.getBundledSocketAdapterConfig().setEnabled(true);
        config.getBundledSocketAdapterConfig().setServerEnabled(false);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        TransportAdapterBootstrapContext bootstrapContext;
        try {
            bootstrapContext = new TransportAdapterBootstrapContext(
                    new CompositeWorkerEndpointRegistry(),
                    mock(TaskResultIngestChannel.class),
                    new RuntimeEventBusWorkerSystemEventChannel(),
                    new InMemoryWorkerPresenceStore(),
                    deliveryService(),
                    runtimeTaskExecutor
            );
            adapterBootstrap(runtimeComposition, "socket-edge").contribute(bootstrapContext);
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertNotNull(bootstrapContext.getTransportBinding());
        assertEquals("socket-edge", bootstrapContext.getTransportBinding().getWorkerAdapter().protocol());
        assertEquals("socket-edge", bootstrapContext.getRawWorkerMessageChannel().adapterId());
        assertEquals("socket-edge",
                runtimeComposition.resolveRegistrationAdapterId("socket-edge", WorkerTransportHints.REALTIME));
    }

    @Test
    void bundledWebSocketTransportBootstrapRejectsNonSessionRegistry() {
        TransportConfig config = new TransportConfig();
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        IllegalStateException error;
        try {
            error = assertThrows(
                    IllegalStateException.class,
                    () -> adapterBootstrap(runtimeComposition, config.getBundledWebSocketAdapterConfig().getAdapterId()).contribute(
                            new TransportAdapterBootstrapContext(
                                    endpointRegistry,
                                    null,
                                    runtimeComposition.resolveSystemEventChannel(),
                                    new InMemoryWorkerPresenceStore(),
                                    deliveryService(),
                                    runtimeTaskExecutor
                            )
                    )
            );
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertTrue(error.getMessage().contains("WebSocket-managed endpoint registry"));
    }

    @Test
    void bundledWebSocketTransportBootstrapRejectsMismatchedSessionRegistryAdapterId() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setAdapterId("ws-public");
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        IllegalStateException error;
        try {
            error = assertThrows(
                    IllegalStateException.class,
                    () -> adapterBootstrap(runtimeComposition, "ws-public").contribute(
                            new TransportAdapterBootstrapContext(
                                    new ServerSessionManager("websocket"),
                                    mock(TaskResultIngestChannel.class),
                                    new RuntimeEventBusWorkerSystemEventChannel(),
                                    new InMemoryWorkerPresenceStore(),
                                    deliveryService(),
                                    runtimeTaskExecutor
                            )
                    )
            );
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertTrue(error.getMessage().contains("endpoint registry adapterId 'ws-public'"));
    }

    @Test
    void bundledSocketTransportBootstrapRejectsMismatchedSessionRegistryAdapterId() {
        TransportConfig config = new TransportConfig();
        config.getBundledSocketAdapterConfig().setAdapterId("socket-edge");
        config.getBundledSocketAdapterConfig().setEnabled(true);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();
        VirtualThreadRuntimeTaskExecutor runtimeTaskExecutor =
                new VirtualThreadRuntimeTaskExecutor("test-transport-runtime-", 10);

        IllegalStateException error;
        try {
            error = assertThrows(
                    IllegalStateException.class,
                    () -> adapterBootstrap(runtimeComposition, "socket-edge").contribute(
                            new TransportAdapterBootstrapContext(
                                    new com.xa.mass.transport.socket.session.SocketSessionManager("socket", null),
                                    mock(TaskResultIngestChannel.class),
                                    new RuntimeEventBusWorkerSystemEventChannel(),
                                    new InMemoryWorkerPresenceStore(),
                                    deliveryService(),
                                    runtimeTaskExecutor
                            )
                    )
            );
        } finally {
            shutdownRuntimeTaskExecutor(runtimeTaskExecutor);
        }

        assertTrue(error.getMessage().contains("endpoint registry adapterId 'socket-edge'"));
    }

    @Test
    void transportRuntimeCompositionResolvesRegistrationAdapterIdBeforeStart() {
        TransportConfig config = new TransportConfig();
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        assertEquals("polling", runtimeComposition.resolveRegistrationAdapterId(null, "polling"));
        assertEquals("websocket", runtimeComposition.resolveRegistrationAdapterId("websocket", "realtime"));
    }

    @Test
    void transportRuntimeCompositionRejectsRealtimeRegistrationWithoutExplicitAdapterIdBeforeStart() {
        TransportConfig config = new TransportConfig();
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeComposition.resolveRegistrationAdapterId(null, "realtime")
        );

        assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                error.getMessage());
    }

    @Test
    void transportRuntimeCompositionUsesCustomPrimaryBootstrapDescriptorEvenWhenWebsocketIsDisabled() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.setPrimaryTransportAdapterBootstrap(new DescriptorOnlyBootstrap(
                new TransportAdapterDescriptor("custom-rt", WorkerTransportHints.REALTIME)
        ));

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        assertEquals("custom-rt", runtimeComposition.resolveRegistrationAdapterId("custom-rt", "realtime"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeComposition.resolveRegistrationAdapterId(null, "realtime")
        );
        assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                error.getMessage());
    }

    @Test
    void transportRuntimeCompositionRequiresExplicitAdapterIdWhenCustomRuntimeFactoryHasNoRegistrationMetadata() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        config.setWorkerTransportRuntimeFactory((workerResourceRuntime,
                                                taskResultIngestChannel,
                                                systemEventChannel,
                                                workerPresenceStore,
                                                deliveryService,
                                                adapterBindings) -> mock(TransportRuntimeRegistry.class));

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> runtimeComposition.resolveRegistrationAdapterId(null, "polling")
        );
        assertEquals(
                "worker adapterId must be set before runtime start when transport registration metadata is unavailable",
                error.getMessage()
        );
        assertEquals("custom-polling",
                runtimeComposition.resolveRegistrationAdapterId(" custom-polling ", "polling"));
    }

    @Test
    void transportRuntimeCompositionUsesBootstrapDescriptorEvenWithCustomRuntimeFactory() {
        TransportConfig config = new TransportConfig();
        config.setWorkerTransportRuntimeFactory((workerResourceRuntime,
                                                taskResultIngestChannel,
                                                systemEventChannel,
                                                workerPresenceStore,
                                                deliveryService,
                                                adapterBindings) -> mock(TransportRuntimeRegistry.class));
        config.setPrimaryTransportAdapterBootstrap(new DescriptorOnlyBootstrap(
                new TransportAdapterDescriptor("custom-rt", WorkerTransportHints.REALTIME)
        ));

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        assertEquals("custom-rt", runtimeComposition.resolveRegistrationAdapterId("custom-rt", "realtime"));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> runtimeComposition.resolveRegistrationAdapterId(null, "realtime")
        );
        assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                error.getMessage());
    }

    @Test
    void transportRuntimeCompositionUsesRuntimeFactoryRegistrationMetadataWhenProvided() {
        TransportConfig config = new TransportConfig();
        config.getBundledWebSocketAdapterConfig().setEnabled(false);
        config.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        config.setWorkerTransportRuntimeFactory(new WorkerTransportRuntimeFactory() {
            @Override
            public TransportRuntimeRegistry create(WorkerResourceRuntime workerResourceRuntime,
                                                   TaskResultIngestChannel taskResultIngestChannel,
                                                   WorkerSystemEventChannel systemEventChannel,
                                                   com.xa.mass.transport.presence.WorkerPresenceStore workerPresenceStore,
                                                   TransportDeliveryService deliveryService,
                                                   List<TransportBinding> adapterBindings) {
                return mock(TransportRuntimeRegistry.class);
            }

            @Override
            public List<TransportAdapterDescriptor> registrationDescriptors() {
                return List.of(new TransportAdapterDescriptor("polling-http-v2", WorkerTransportHints.POLLING));
            }
        });

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        assertEquals("polling-http-v2", runtimeComposition.resolveRegistrationAdapterId(null, "polling"));
        assertEquals("polling-http-v2", runtimeComposition.resolveRegistrationAdapterId("polling-http-v2", "polling"));
    }

    @Test
    void runtimeCompositionCanAggregateAdditionalTransportAdapterBootstraps() {
        TransportConfig config = new TransportConfig();
        config.addSupplementalTransportAdapterBootstrap(context -> {
        });

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        Assertions.assertEquals(2, runtimeComposition.resolveTransportAdapterBootstraps().size());
    }

    @Test
    void runtimeCompositionCanAppendAdditionalBundledRealtimeAdapterInstances() {
        TransportConfig config = new TransportConfig();
        com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig extraWebSocket =
                new com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig();
        extraWebSocket.setAdapterId("ws-internal");
        extraWebSocket.setEnabled(true);
        extraWebSocket.setServerEnabled(false);
        config.addSupplementalWebSocketAdapterConfig(extraWebSocket);

        com.xa.mass.transport.socket.runtime.SocketAdapterConfig extraSocket =
                new com.xa.mass.transport.socket.runtime.SocketAdapterConfig();
        extraSocket.setAdapterId("socket-edge");
        extraSocket.setEnabled(true);
        extraSocket.setServerEnabled(false);
        config.addSupplementalSocketAdapterConfig(extraSocket);

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        assertNotNull(adapterBootstrap(runtimeComposition, "websocket"));
        assertThrows(AssertionError.class, () -> adapterBootstrap(runtimeComposition, "socket"));
        assertNotNull(adapterBootstrap(runtimeComposition, "ws-internal"));
        assertNotNull(adapterBootstrap(runtimeComposition, "socket-edge"));
        assertEquals(3, runtimeComposition.resolveTransportAdapterBootstraps().size());
    }

    @Test
    void runtimeCompositionRejectsDuplicateAdapterIdsAcrossBundledInstances() {
        TransportConfig config = new TransportConfig();
        com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig extraWebSocket =
                new com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig();
        extraWebSocket.setAdapterId("websocket");
        extraWebSocket.setEnabled(true);
        config.addSupplementalWebSocketAdapterConfig(extraWebSocket);

        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                runtimeComposition::resolveTransportAdapterBootstraps
        );
        assertTrue(error.getMessage().contains("Duplicate transport adapterId configured: websocket"));
    }

    @Test
    void transportRuntimeCompositionSnapshotsDeliveryQueueCapacity() {
        TransportConfig config = new TransportConfig();
        config.setMaxDeliveryQueuedItems(42);
        config.setMaxDeliveryItemsPerRoute(7);
        config.setEventHandlerTimeoutMillis(123);
        TransportRuntimeComposition runtimeComposition = config.snapshotRuntimeComposition();

        config.setMaxDeliveryQueuedItems(84);
        config.setMaxDeliveryItemsPerRoute(9);
        config.setEventHandlerTimeoutMillis(456);

        assertEquals(42, runtimeComposition.getMaxDeliveryQueuedItems());
        assertEquals(7, runtimeComposition.getMaxDeliveryItemsPerRoute());
        assertEquals(123, runtimeComposition.getEventHandlerTimeoutMillis());
        assertThrows(IllegalArgumentException.class, () -> config.setMaxDeliveryQueuedItems(0));
        assertThrows(IllegalArgumentException.class, () -> config.setMaxDeliveryItemsPerRoute(0));
        assertThrows(IllegalArgumentException.class, () -> config.setEventHandlerTimeoutMillis(-1));
    }

    @Test
    void massApplicationStopsCustomTransportDeliveryStore() {
        StubTransportDeliveryStore store = new StubTransportDeliveryStore();
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .deliveryStoreFactory(() -> store)
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        app.start();
        app.stop();

        assertTrue(store.shutdownCalled.get());
    }

    @Test
    void sdkBuilderAcceptsRedisDeliveryStoreNamespaceOverride() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .maxDeliveryItemsPerRoute(5)
                        .redisDeliveryStore("redis://127.0.0.1:6379/0", "xa:mass:test:transport:delivery")
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void massApplicationStopsCustomPresenceStore() {
        StubWorkerPresenceStore store = new StubWorkerPresenceStore();
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .presenceStoreFactory(() -> store)
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        app.start();
        app.stop();

        assertTrue(store.closed.get());
    }

    @Test
    void sdkBuilderAcceptsRedisPresenceStoreNamespaceOverride() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .workerPresenceLeaseMillis(5_000L)
                        .redisPresenceStore("redis://127.0.0.1:6379/0", "xa:mass:test:transport:presence")
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    void explicitRealtimeBuilderWrapsRuntimeApplication() {
        MassSdkApplication app = explicitRealtimeRuntime(18080, 8, 1000);

        assertNotNull(app);
        assertFalse(app.isRunning());
    }

    @Test
    void engineDependentHelpersFailFastWhenEngineIsUnavailable() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 19091, "/sdk-transport"))
                .engine(engine -> engine.enabled(false))
                .build();

        Assertions.assertThrows(IllegalStateException.class,
                () -> createShellWithOptionalItems(app, MassTaskShellCreateRequest.builder().build(), null, List.of(), false));
        assertEngineOperationsFailFast(app);
    }

    @Test
    void engineDependentHelpersFailFastBeforeStart() {
        MassSdkApplication app = explicitRealtimeRuntime(18081, 8, 1000);

        assertEngineOperationsFailFast(app);
    }

    @Test
    void transportOperationsUseDelegateTransportAccessors() {
        MassApplication delegate = mock(MassApplication.class);
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);

        when(delegate.getTransportQueueDetail()).thenReturn(Map.of(
                "inputQueueSize", 2,
                "outputQueueSize", 5,
                "transporterAvailable", true,
                "deliveryDiagnostics", Map.of(
                        "available", true,
                        "queuedItems", 1,
                        "queueCount", 1,
                        "waitingPollers", 0,
                        "maxQueuedItems", 100,
                        "queueByAdapter", Map.of(
                                "polling", Map.of(
                                        "queuedItems", 1,
                                        "queueCount", 1,
                                        "waitingPollers", 0,
                                        "oldestQueuedAgeMillis", 0L,
                                        "backpressureRejectedItems", 0L
                                )
                        ),
                        "directByAdapter", Map.of(
                                "websocket", Map.of(
                                        "sentItems", 2L,
                                        "offlineItems", 1L,
                                        "failedItems", 0L,
                                        "invalidItems", 0L,
                                        "unavailableItems", 0L
                                )
                        )
                ),
                "runtimeExecutors", Map.of(
                        "transport", Map.of("available", true, "pendingTasks", 0),
                        "event", Map.of("available", false, "pendingTasks", 0)
                )
        ));
        when(delegate.getEndpointRegistry()).thenReturn(endpointRegistry);
        when(delegate.sendRawTransportMessage(anyString(), anyString(), anyString())).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);

        Map<String, Object> queueDetail = runtimeDiagnostics(app).getQueueDetail();
        Map<String, Object> sessionStats = runtimeDiagnostics(app).getSessionStats();
        Map<String, Object> enqueueResult = rawTransportDebug(app).enqueueRawMessage(
                Map.of("workerId", "worker-debug-1", "rawJson", "{\"eventCode\":\"platform.test\"}")
        );

        assertEquals(2, queueDetail.get("inputQueueSize"));
        assertEquals(5, queueDetail.get("outputQueueSize"));
        assertEquals(true, queueDetail.get("transporterAvailable"));
        assertEquals(1, ((Map<?, ?>) queueDetail.get("deliveryDiagnostics")).get("queuedItems"));
        assertEquals(1, ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) queueDetail.get("deliveryDiagnostics"))
                .get("queueByAdapter")).get("polling")).get("queuedItems"));
        assertEquals(2L, ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) queueDetail.get("deliveryDiagnostics"))
                .get("directByAdapter")).get("websocket")).get("sentItems"));
        assertEquals(true, ((Map<?, ?>) ((Map<?, ?>) queueDetail.get("runtimeExecutors")).get("transport"))
                .get("available"));
        assertEquals(0, sessionStats.get("activeConnections"));
        assertEquals(0L, sessionStats.get("workerCount"));
        assertEquals(Map.of(), sessionStats.get("activeConnectionsByAdapter"));
        assertEquals(true, enqueueResult.get("success"));
        verify(delegate).getTransportQueueDetail();
        verify(delegate, atLeastOnce()).getEndpointRegistry();
        verify(delegate).sendRawTransportMessage(eq("worker-debug-1"), eq("{\"eventCode\":\"platform.test\"}"), anyString());
    }

    @Test
    void sdkWorkerOnlineReadsTransportPresenceBeforeWorkerModelStatus() {
        MassApplication delegate = mock(MassApplication.class);
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        presenceStore.markOnline("worker-1", "polling", "worker-1", "worker-1", "connected");
        when(delegate.getWorkerPresenceStore()).thenReturn(presenceStore);

        MassSdkApplication app = new MassSdkApplication(delegate);

        assertTrue(app.isWorkerOnline("worker-1"));

        presenceStore.markOffline("worker-1", "polling", "worker-1", "worker-1", "disconnect");

        assertFalse(app.isWorkerOnline("worker-1"));
    }

    @Test
    void sdkWorkerOnlineTreatsStalePresenceAsOffline() {
        MassApplication delegate = mock(MassApplication.class);
        WorkerPresenceStore presenceStore = new WorkerPresenceStore() {
            @Override
            public WorkerPresence markOnline(String workerId, String adapterId, String routeKey, String connectionId, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkerPresence refreshHeartbeat(String workerId, String adapterId, String routeKey, String connectionId, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkerPresence markOffline(String workerId, String adapterId, String routeKey, String connectionId, String reason) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WorkerPresence getPresence(String workerId) {
                return new WorkerPresence(
                        workerId,
                        "polling",
                        workerId,
                        WorkerPresenceState.STALE,
                        1L,
                        1L,
                        "runtime-a",
                        workerId,
                        1L,
                        null
                );
            }

            @Override
            public boolean isRouteOnline(String adapterId, String routeKey) {
                return false;
            }

            @Override
            public List<WorkerPresence> listActivePresences() {
                return List.of();
            }

            @Override
            public int pruneExpired() {
                return 0;
            }
        };
        when(delegate.getWorkerPresenceStore()).thenReturn(presenceStore);

        MassSdkApplication app = new MassSdkApplication(delegate);

        assertFalse(app.isWorkerOnline("worker-stale"));
    }

    @Test
    void sdkWorkerOnlineFollowsNewestPresenceOwnerAfterReconnect() {
        MassApplication delegate = mock(MassApplication.class);
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        when(delegate.getWorkerPresenceStore()).thenReturn(presenceStore);

        MassSdkApplication app = new MassSdkApplication(delegate);

        presenceStore.markOnline("worker-2", "websocket", "route-old", "conn-old", "connected");
        assertTrue(app.isWorkerOnline("worker-2"));

        presenceStore.markOnline("worker-2", "socket", "route-new", "conn-new", "reconnected");
        presenceStore.markOffline("worker-2", "websocket", "route-old", "conn-old", "late-disconnect");

        assertTrue(app.isWorkerOnline("worker-2"));
        assertEquals("conn-new", presenceStore.getPresence("worker-2").getConnectionId());
        assertEquals("socket", presenceStore.getPresence("worker-2").getAdapterId());
        assertEquals("route-new", presenceStore.getPresence("worker-2").getRouteKey());

        presenceStore.markOffline("worker-2", "socket", "route-new", "conn-new", "disconnect");

        assertFalse(app.isWorkerOnline("worker-2"));
    }

    @Test
    void sessionDiagnosticsExposeAdapterIdAndRouteKey() {
        MassApplication delegate = mock(MassApplication.class);
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class,
                withSettings().extraInterfaces(com.xa.mass.transport.WorkerEndpointInspector.class));
        com.xa.mass.transport.WorkerEndpointInspector inspector =
                (com.xa.mass.transport.WorkerEndpointInspector) endpointRegistry;

        when(delegate.getEndpointRegistry()).thenReturn(endpointRegistry);
        when(endpointRegistry.getActiveConnectionCount()).thenReturn(2);
        when(inspector.listWorkerEndpoints()).thenReturn(List.of(
                new com.xa.mass.transport.WorkerEndpointSnapshot(
                        "route-public",
                        "worker-1",
                        true,
                        "endpoint-1",
                        "ws-public"
                ),
                new com.xa.mass.transport.WorkerEndpointSnapshot(
                        "route-internal",
                        "worker-1",
                        true,
                        "endpoint-2",
                        "ws-internal"
                )
        ));

        MassSdkApplication app = new MassSdkApplication(delegate);

        List<Map<String, Object>> sessions = runtimeDiagnostics(app).listSessions();
        Map<String, Object> sessionStats = runtimeDiagnostics(app).getSessionStats();

        assertEquals(1, sessions.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connections = (List<Map<String, Object>>) sessions.get(0).get("connections");
        assertEquals("route-public", connections.get(0).get("routeKey"));
        assertEquals("ws-public", connections.get(0).get("adapterId"));
        assertEquals(2, sessionStats.get("activeConnections"));
        assertEquals(1L, sessionStats.get("workerCount"));
        assertEquals(Map.of("ws-public", 1L, "ws-internal", 1L), sessionStats.get("activeConnectionsByAdapter"));
    }

    @Test
    void enqueueRawMessageUsesTransportSideChannelEvenWithoutMessageTransporter() {
        MassApplication delegate = mock(MassApplication.class);
        when(delegate.sendRawTransportMessage(anyString(), anyString(), anyString())).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);

        Map<String, Object> enqueueResult = rawTransportDebug(app).enqueueRawMessage(Map.of(
                "workerId", "worker-debug-2",
                "rawJson", "{\"eventCode\":\"platform.direct\"}"
        ));

        assertEquals(true, enqueueResult.get("success"));
        verify(delegate).sendRawTransportMessage(eq("worker-debug-2"), eq("{\"eventCode\":\"platform.direct\"}"), anyString());
    }

    @Test
    void transportCanStartWithoutQueuesBecauseTransporterIsCompatibilityOnly() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(true).serverEnabled(false))
                        .socketAdapter(socket -> socket.enabled(false).serverEnabled(false)))
                .engine(engine -> engine.enabled(false))
                .build();

        try {
            app.start();

            assertTrue(app.isRunning());
            Map<String, Object> queueDetail = runtimeDiagnostics(app).getQueueDetail();
            assertEquals(true, queueDetail.get("transporterAvailable"));
            assertEquals(0, queueDetail.get("inputQueueSize"));
            assertEquals(0, queueDetail.get("outputQueueSize"));
            Map<?, ?> deliveryDiagnostics = (Map<?, ?>) queueDetail.get("deliveryDiagnostics");
            assertEquals(true, deliveryDiagnostics.get("available"));
            assertEquals(0, deliveryDiagnostics.get("queuedItems"));
            assertEquals(0, deliveryDiagnostics.get("queueCount"));
            assertEquals(0, deliveryDiagnostics.get("waitingPollers"));
            assertEquals(100_000, deliveryDiagnostics.get("maxQueuedItems"));
            assertEquals(0L, deliveryDiagnostics.get("oldestQueuedAgeMillis"));
            assertEquals(0L, deliveryDiagnostics.get("enqueuedItems"));
            assertEquals(0L, deliveryDiagnostics.get("drainedItems"));
            assertEquals(0L, deliveryDiagnostics.get("backpressureRejectedItems"));
            assertEquals(0L, deliveryDiagnostics.get("invalidItems"));
            assertEquals(0L, deliveryDiagnostics.get("unavailableItems"));
            assertEquals(0L, deliveryDiagnostics.get("shutdownClearedItems"));
            assertEquals(0L, deliveryDiagnostics.get("directSentItems"));
            assertEquals(0L, deliveryDiagnostics.get("directOfflineItems"));
            assertEquals(0L, deliveryDiagnostics.get("directFailedItems"));
            assertEquals(0L, deliveryDiagnostics.get("directInvalidItems"));
            assertEquals(0L, deliveryDiagnostics.get("directUnavailableItems"));
            assertEquals(Map.of(), deliveryDiagnostics.get("queueByAdapter"));
            assertEquals(Map.of(), deliveryDiagnostics.get("directByAdapter"));
            Map<?, ?> runtimeExecutors = (Map<?, ?>) queueDetail.get("runtimeExecutors");
            assertEquals(true, ((Map<?, ?>) runtimeExecutors.get("transport")).get("available"));
            assertEquals(10_000, ((Map<?, ?>) runtimeExecutors.get("transport")).get("maxPendingTasks"));
            assertEquals(false, ((Map<?, ?>) runtimeExecutors.get("event")).get("available"));
        } finally {
            app.stop();
        }
    }

    @Test
    void transportDeliveryQueueCapacityCanBeConfigured() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(true).serverEnabled(false))
                        .maxDeliveryQueuedItems(7))
                .engine(engine -> engine.enabled(false))
                .build();

        try {
            app.start();

        Map<?, ?> deliveryDiagnostics = (Map<?, ?>) runtimeDiagnostics(app).getQueueDetail().get("deliveryDiagnostics");
        assertEquals(true, deliveryDiagnostics.get("available"));
        assertEquals(7, deliveryDiagnostics.get("maxQueuedItems"));
        assertEquals(Map.of(), deliveryDiagnostics.get("queueByAdapter"));
        } finally {
            app.stop();
        }
    }

    @Test
    void runtimeExecutorPendingCapacityCanBeConfigured() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(true).serverEnabled(false))
                        .transportRuntimeMaxPendingTasks(17)
                        .eventRuntimeMaxPendingTasks(3)
                        .eventHandlerTimeoutMillis(1_000))
                .engine(engine -> engine.enabled(false))
                .build();

        try {
            app.start();

            Map<?, ?> runtimeExecutors = (Map<?, ?>) runtimeDiagnostics(app).getQueueDetail().get("runtimeExecutors");
            assertEquals(17, ((Map<?, ?>) runtimeExecutors.get("transport")).get("maxPendingTasks"));
            assertEquals(3, ((Map<?, ?>) runtimeExecutors.get("event")).get("maxPendingTasks"));
        } finally {
            app.stop();
        }
    }

    @Test
    void configuredEventHandlerTimeoutBoundsDirectRuntimeDispatch() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false))
                        .eventHandlerTimeoutMillis(50))
                .engine(engine -> engine.enabled(false))
                .build();
        app.registerEventDefinition(EventDefinition.builder()
                .code("sdk.event.slow")
                .name("Slow Event")
                .handler((request, principal) -> {
                    try {
                        Thread.sleep(5_000);
                        return EventResponse.success(Boolean.TRUE, request.getRequestId());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interrupted.countDown();
                        return EventResponse.failure("INTERRUPTED", "handler interrupted", request.getRequestId());
                    }
                })
                .build());
        try {
            app.start();

            EventResponse response = app.dispatchEvent(
                    EventRequest.builder()
                            .event("sdk.event.slow")
                            .requestId("req-slow")
                            .build(),
                    eventPrincipal("client-a", "user-a", "*", "sdk.event.slow")
            );

            assertFalse(response.isSuccess());
            assertEquals("EVENT_TIMEOUT", response.getCode());
            assertEquals("req-slow", response.getRequestId());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        } finally {
            app.stop();
        }
    }

    @Test
    void configuredEventRuntimeCanRestartWithApplication() {
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket.enabled(false).serverEnabled(false))
                        .eventHandlerTimeoutMillis(1_000))
                .engine(engine -> engine.enabled(false))
                .build();
        app.registerEventDefinition(EventDefinition.builder()
                .code("sdk.event.fast")
                .name("Fast Event")
                .handler((request, principal) -> EventResponse.success(
                        Map.of("virtualThread", Thread.currentThread().isVirtual()),
                        request.getRequestId()))
                .build());
        try {
            app.start();
            assertEventDispatchRunsOnVirtualThread(app, "req-fast-1");
            Map<?, ?> runtimeExecutors = (Map<?, ?>) runtimeDiagnostics(app).getQueueDetail().get("runtimeExecutors");
            assertEquals(true, ((Map<?, ?>) runtimeExecutors.get("event")).get("available"));
            assertEquals(1L, ((Map<?, ?>) runtimeExecutors.get("event")).get("completedTasks"));
            app.stop();

            app.start();
            assertEventDispatchRunsOnVirtualThread(app, "req-fast-2");
        } finally {
            app.stop();
        }
    }

    @Test
    void createTaskUsesSdkRequestAsPrimaryContract() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        Task createdTask = new Task();
        createdTask.setTid("task-001");
        Task hydratedTask = new Task();
        hydratedTask.setTid("task-001");
        hydratedTask.setProject("demoApp");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        when(config.getTaskQueryService()).thenReturn(taskQueryService);
        when(engine.createTaskShell(any(TaskShellCreateRequestDto.class))).thenReturn(createdTask);
        when(taskQueryService.getTask("task-001")).thenReturn(hydratedTask);
        stubAppendReceipts(taskCommandService);

        MassSdkApplication app = new MassSdkApplication(delegate);
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .sourceRef("sdk-task")
                .sharedConfig(Map.of("textContent", "hello", "routingCode", "us"))
                .executionSpec(taskExecutionOptions(null, 2, 600, 0))
                .build();

        TaskDetailSnapshot result = createShellWithOptionalItems(app, request, "demo.dispatch", List.of(
                Map.of("target", "target-a"),
                Map.of("target", "target-b")
        ), true);

        assertNotNull(result);
        Assertions.assertEquals("task-001", result.getTaskId());
        Assertions.assertEquals("demoApp", result.getProject());
        var captor = org.mockito.ArgumentCaptor.forClass(TaskShellCreateRequestDto.class);
        verify(engine).createTaskShell(captor.capture());
        TaskShellCreateRequestDto dto = captor.getValue();
        Assertions.assertEquals("agent", dto.getUserId());
        Assertions.assertEquals("demoApp", dto.getProject());
        Assertions.assertEquals("sdk-task", dto.getSourceRef());
        Assertions.assertEquals(
                TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("textContent", "hello", "routingCode", "us"),
                        new TaskOwnershipStamp("sdk-internal", PrincipalType.SERVICE)
                ),
                dto.getSharedConfig()
        );
        Assertions.assertEquals(2, dto.getExecutionSpec().getBatchSize());
        Assertions.assertEquals(600, dto.getExecutionSpec().getMaxRuntimeSeconds());
        verify(taskCommandService).appendTaskItemsWithReceipt("task-001", List.of(
                Map.of("target", "target-a", "eventCode", "demo.dispatch"),
                Map.of("target", "target-b", "eventCode", "demo.dispatch")
        ));
    }

    @Test
    void registerWorkerUsesSdkContractAndStartsOffline() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = new EngineConfig();
        WorkerStorage workerStorage = spy(config.getWorkerStorage());
        config.setWorkerStorage(workerStorage);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        stubDefaultTransportRegistrationResolution(delegate);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);
        declareSdkTestGroup(app, "crawler", List.of(WorkerEventBinding.builder()
                .eventCode("crawler.fetch-page")
                .projectCodes(List.of("crawlerApp"))
                .build()));
        bindSdkTestNode(app, "crawler");
        app.registerWorker(WorkerRegistration.builder()
                .workerId("crawler-worker-001")
                .adapterNodeId("sdk-test-node")
                .workerGroupId("crawler")
                .transportHint("polling")
                .maxConcurrentWork(4)
                .attributes(Map.of("type", "crawler"))
                .build());

        var captor = org.mockito.ArgumentCaptor.forClass(Worker.class);
        verify(workerStorage).addWorker(captor.capture());
        Worker worker = captor.getValue();
        Assertions.assertEquals("crawler-worker-001", worker.getWorkerId());
        Assertions.assertEquals("crawler", worker.getWorkerGroupId());
        Assertions.assertTrue(worker.getSupportedProjects().isEmpty());
        Assertions.assertTrue(worker.getSupportedEventCodes().isEmpty());
        Assertions.assertEquals("polling", worker.getOnlineStrategy());
        Assertions.assertEquals(4, worker.getMaxConcurrentWork());
        Assertions.assertEquals(Map.of("type", "crawler"), worker.getAttributes());
        Assertions.assertEquals(WorkerStatus.OFFLINE, worker.getStatus());
    }

    @Test
    void taskAdminCommandsUseSdkResumeAndUpdateSurface() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        TaskCommandService taskCommands = mock(TaskCommandService.class);
        TaskQueryService taskQueries = mock(TaskQueryService.class);
        EngineConfig config = mock(EngineConfig.class);
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskName("before");
        task.setProject("demoApp");
        task.setUser(UserRef.of("user-1"));

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommands);
        when(config.getTaskQueryService()).thenReturn(taskQueries);
        when(taskQueries.getTask("task-1")).thenReturn(task);
        when(taskCommands.resumeTaskDetailed("task-1")).thenReturn(TaskResumeResult.resumedToReady());
        when(taskCommands.updateTask(task)).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);

        SdkTaskResumeResult resumeResult = app.resumeTaskDetailed("task-1");
        boolean updated = app.updateTaskDefinition("task-1", MassTaskUpdateRequest.builder()
                .project("testApp")
                .userId("user-2")
                .sharedConfig(Map.of("routingCode", "us"))
                .build());

        assertTrue(resumeResult.success());
        assertEquals("READY", resumeResult.status());
        assertTrue(updated);
        assertEquals("before", task.getTaskName());
        assertEquals("testApp", task.getProject());
        assertEquals("user-2", task.getUser().getUserId());
        assertEquals(Map.of("routingCode", "us"), task.getSharedConfig());
        verify(taskCommands).resumeTaskDetailed("task-1");
        verify(taskCommands).updateTask(task);
    }

    @Test
    void taskWorkFinalListenerUsesSdkOwnedNotificationSurface() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        TaskEventService taskEvents = mock(TaskEventService.class);
        EngineConfig config = mock(EngineConfig.class);
        java.util.concurrent.atomic.AtomicReference<TaskWorkFinalNotification> capturedNotification =
                new java.util.concurrent.atomic.AtomicReference<>();
        TaskWorkFinalListener listener = capturedNotification::set;
        Task task = new Task();
        task.setSharedConfig(Map.of("route", "alpha"));

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskEventService()).thenReturn(taskEvents);

        MassSdkApplication app = new MassSdkApplication(delegate);

        app.addTaskWorkFinalListener(listener);

        org.mockito.ArgumentCaptor<TaskWorkLogicallyFinalListener> captor =
                org.mockito.ArgumentCaptor.forClass(TaskWorkLogicallyFinalListener.class);
        verify(taskEvents).addTaskWorkLogicallyFinalListener(captor.capture());

        captor.getValue().onTaskWorkLogicallyFinal(task, new TaskWorkLogicallyFinalEvent(
                "task-1",
                "msg-1",
                TaskWorkProjectionState.MessageStatus.SUCCESS,
                TaskWorkProjectionState.MessageFinalReason.BUSINESS_SUCCESS,
                2,
                null,
                null,
                "payload-ref-1",
                Map.of("answer", 42)
        ));

        TaskWorkFinalNotification notification = capturedNotification.get();
        assertNotNull(notification);
        assertEquals("task-1", notification.taskId());
        assertEquals(Map.of("route", "alpha"), notification.sharedConfig());
        assertEquals("msg-1", notification.finalSnapshot().messageId());
        assertEquals("SUCCESS", notification.finalSnapshot().status());
        assertEquals("BUSINESS_SUCCESS", notification.finalSnapshot().finalReason());
        assertEquals(2, notification.finalSnapshot().retryCount());
        assertEquals("payload-ref-1", notification.finalSnapshot().payloadRef());
        assertEquals(Map.of("answer", 42), notification.finalSnapshot().output());
    }

    @Test
    void replaceDefaultRulesUsesOpenRuntimeCapability() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = new EngineConfig();
        RuleStorage ruleStorage = config.getRuleStorage();
        RuleDefinition replacement = new RuleDefinition();
        replacement.setId("sdk_rule");
        replacement.setName("sdk_rule");
        replacement.setType(RuleType.QL_EXPRESS);
        replacement.setContent("true");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);

        MassSdkApplication app = new MassSdkApplication(delegate);
        app.replaceDefaultRules(List.of(replacement));

        Assertions.assertEquals(List.of(replacement), ruleStorage.getAllRules());
    }

    @Test
    void engineConfigUsesInjectedTaskWorkRuntimeForDefaultTaskManagerAssembly() {
        EngineConfig config = new EngineConfig();
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();

        config.setTaskWorkRuntime(runtime);
        assertSame(runtime, config.getTaskWorkRuntime());
        List<Map<String, Object>> inputs = List.of(Map.of("target", "alpha"));
        TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
        shell.setSourceRef("runtime-assembly");
        shell.setProject("demoApp");
        shell.setUserId("sdk-test");
        shell.setExecutionSpec(taskExecutionSpec(null, 1, 0, 3));
        Task task = config.getTaskCommandService().createTaskShell(shell);
        config.getTaskCommandService().appendTaskItems(task.getTid(), inputs);
        assertTrue(config.getTaskCommandService().sealTask(task.getTid()));
        assertEquals(1, runtime.stats(task.getTid()).readyCount());
    }

    @Test
    void engineConfigRejectsTaskWorkRuntimeMismatchAfterTaskManagerIsConfigured() {
        EngineConfig config = new EngineConfig();
        config.getTaskCommandService();

        assertThrows(IllegalStateException.class,
                () -> config.setTaskWorkRuntime(new InMemoryTaskWorkRuntime()));
    }

    @Test
    void engineConfigMemoizesRuntimeBoundaries() {
        EngineConfig config = new EngineConfig();

        TaskResultIngestFacade resultIngestFacade = config.getTaskResultIngestFacade();
        TaskAssignmentRuntimePort assignmentRuntimePort = config.getTaskAssignmentRuntimePort();
        TaskRuntimeMaintenancePort runtimeMaintenancePort = config.getTaskRuntimeMaintenancePort();
        TaskRuntimeRecoveryPort runtimeRecoveryPort = config.getTaskRuntimeRecoveryPort();

        assertSame(resultIngestFacade, config.getTaskResultIngestFacade());
        assertSame(assignmentRuntimePort, config.getTaskAssignmentRuntimePort());
        assertSame(runtimeMaintenancePort, config.getTaskRuntimeMaintenancePort());
        assertSame(runtimeRecoveryPort, config.getTaskRuntimeRecoveryPort());
    }

    @Test
    void engineConfigRequiresExplicitTaskDetailStoreAfterReplacingTaskStorage() {
        EngineConfig config = new EngineConfig();

        config.setTaskStorage(new InMemoryTaskStorage());

        IllegalStateException error = assertThrows(IllegalStateException.class, config::getTaskCommandService);
        assertEquals("taskDetailStore is not configured; provide an explicit taskDetailStore via setTaskDetailStore()",
                error.getMessage());
    }

    @Test
    void engineConfigDerivesWorkerRuntimeFromCurrentWorkerStorage() {
        EngineConfig config = new EngineConfig();
        WorkerResourceRuntime initial = config.getWorkerResourceRuntime();
        assertSame(initial, config.getWorkerResourceRuntime());
        WorkerStorage replacement = spy(new com.xa.mass.storage.memory.InMemoryWorkerStorage());

        config.setWorkerStorage(replacement);

        WorkerResourceRuntime rebound = config.getWorkerResourceRuntime();
        assertNotSame(initial, rebound);
        assertSame(rebound, config.getWorkerResourceRuntime());
        rebound.addWorker(workerResource("worker-rebound", "runtime-group"));

        verify(replacement).addWorker(argThat(worker -> "worker-rebound".equals(worker.getWorkerId())));
        assertNotNull(replacement.getWorker("worker-rebound").orElse(null));
    }

    @Test
    void engineConfigUsesInjectedWorkerRegistryForWorkerRuntimeAssembly() {
        EngineConfig config = new EngineConfig();
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry();

        config.setWorkerRegistry(registry);
        config.getWorkerResourceRuntime().addWorker(workerResource("worker-registry-runtime", "runtime-group"));

        assertTrue(registry.slotByWorkerId("worker-registry-runtime").isPresent());
    }

    @Test
    void engineConfigRejectsWorkerRegistryReplacementAfterWorkerRuntimeIsConfigured() {
        EngineConfig config = new EngineConfig();
        config.getWorkerResourceRuntime();

        assertThrows(IllegalStateException.class,
                () -> config.setWorkerRegistry(new InMemoryWorkerRegistry()));
    }

    @Test
    void engineConfigReinitializesDefaultRulesForReplacementRuleStorage() {
        EngineConfig config = new EngineConfig();
        var initial = config.getRuleManager();
        RuleStorage replacement = new com.xa.mass.storage.memory.InMemoryRuleStorage();

        config.setRuleStorage(replacement);

        var rebound = config.getRuleManager();
        assertNotSame(initial, rebound);
        Assertions.assertFalse(replacement.getAllRules().isEmpty());
    }

    @Test
    void createTaskSupportsModeAwareSdkRequest() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        Task createdTask = new Task();
        createdTask.setTid("task-stream-001");
        Task hydratedTask = new Task();
        hydratedTask.setTid("task-stream-001");
        hydratedTask.setProject("demoApp");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        when(config.getTaskQueryService()).thenReturn(taskQueryService);
        when(engine.createTaskShell(any(TaskShellCreateRequestDto.class))).thenReturn(createdTask);
        when(taskQueryService.getTask("task-stream-001")).thenReturn(hydratedTask);
        stubAppendReceipts(taskCommandService);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);
        MassTaskShellCreateRequest request = MassTaskShellCreateRequest.builder()
                .userId("agent")
                .project("demoApp")
                .sourceRef("crawler-stream")
                .sharedConfig(Map.of("routingCode", "us"))
                .executionSpec(taskExecutionOptions(null, 1, 60, 0))
                .build();

        TaskDetailSnapshot result = createShellWithOptionalItems(app, request, "crawler.fetch-page", List.of(
                Map.of("url", "https://example.test/page-1"),
                Map.of("url", "https://example.test/page-2")
        ), true);

        assertNotNull(result);
        Assertions.assertEquals("task-stream-001", result.getTaskId());
        Assertions.assertEquals("demoApp", result.getProject());
        var captor = org.mockito.ArgumentCaptor.forClass(TaskShellCreateRequestDto.class);
        verify(engine).createTaskShell(captor.capture());
        TaskShellCreateRequestDto dto = captor.getValue();
        Assertions.assertEquals("agent", dto.getUserId());
        Assertions.assertEquals("demoApp", dto.getProject());
        Assertions.assertEquals("crawler-stream", dto.getSourceRef());
        Assertions.assertEquals(
                TaskOwnershipStamp.applyToSharedConfig(
                        Map.of("routingCode", "us"),
                        new TaskOwnershipStamp("sdk-internal", PrincipalType.SERVICE)
                ),
                dto.getSharedConfig()
        );
        verify(taskCommandService).appendTaskItemsWithReceipt("task-stream-001", List.of(
                Map.of("url", "https://example.test/page-1", "eventCode", "crawler.fetch-page"),
                Map.of("url", "https://example.test/page-2", "eventCode", "crawler.fetch-page")
        ));
    }

    @Test
    void appendTaskItemsResolvesEventCodeToSingleWorkerGroupSelector() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        WorkerResourceRuntime workerManager = mock(WorkerResourceRuntime.class);
        Task task = new Task();
        task.setTid("task-resolve-single");
        task.setProject("crawlerApp");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        when(config.getTaskQueryService()).thenReturn(taskQueryService);
        when(config.getWorkerResourceRuntime()).thenReturn(workerManager);
        when(taskQueryService.getTask("task-resolve-single")).thenReturn(task);
        when(workerManager.workerGroups()).thenReturn(List.of(group("crawler", "crawlerApp", "crawler.fetch-page")));
        stubAppendReceipts(taskCommandService);

        new MassSdkApplication(delegate).appendTaskItems("task-resolve-single",
                MassTaskItemBatchAppendRequest.builder()
                        .eventCode("crawler.fetch-page")
                        .items(List.of(Map.of("url", "https://example.test")))
                        .build());

        var captor = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(taskCommandService).updateTask(captor.capture());
        Assertions.assertEquals("crawler",
                captor.getValue().getSharedConfig().get(TaskSharedConfig.WORKER_GROUP_ID));
        Assertions.assertFalse(captor.getValue().getSharedConfig().containsKey(TaskSharedConfig.WORKER_GROUP_IDS));
    }

    @Test
    void appendTaskItemsResolvesEventCodeToOrderedWorkerGroupSelectors() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        WorkerResourceRuntime workerManager = mock(WorkerResourceRuntime.class);
        Task task = new Task();
        task.setTid("task-resolve-multi");
        task.setProject("crawlerApp");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        when(config.getTaskQueryService()).thenReturn(taskQueryService);
        when(config.getWorkerResourceRuntime()).thenReturn(workerManager);
        when(taskQueryService.getTask("task-resolve-multi")).thenReturn(task);
        when(workerManager.workerGroups()).thenReturn(List.of(
                group("crawler-a", "crawlerApp", "crawler.fetch-page"),
                group("crawler-b", "crawlerApp", "crawler.fetch-page")
        ));
        stubAppendReceipts(taskCommandService);

        new MassSdkApplication(delegate).appendTaskItems("task-resolve-multi",
                MassTaskItemBatchAppendRequest.builder()
                        .eventCode("crawler.fetch-page")
                        .items(List.of(Map.of("url", "https://example.test")))
                        .build());

        var captor = org.mockito.ArgumentCaptor.forClass(Task.class);
        verify(taskCommandService).updateTask(captor.capture());
        Assertions.assertEquals(List.of("crawler-a", "crawler-b"),
                captor.getValue().getSharedConfig().get(TaskSharedConfig.WORKER_GROUP_IDS));
        Assertions.assertFalse(captor.getValue().getSharedConfig().containsKey(TaskSharedConfig.WORKER_GROUP_ID));
    }

    @Test
    void appendTaskItemsFailsWhenEventCodeCannotResolveWorkerGroupSelector() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        WorkerResourceRuntime workerManager = mock(WorkerResourceRuntime.class);
        Task task = new Task();
        task.setTid("task-resolve-none");
        task.setProject("crawlerApp");

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        when(config.getTaskQueryService()).thenReturn(taskQueryService);
        when(config.getWorkerResourceRuntime()).thenReturn(workerManager);
        when(taskQueryService.getTask("task-resolve-none")).thenReturn(task);
        when(workerManager.workerGroups()).thenReturn(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new MassSdkApplication(delegate).appendTaskItems("task-resolve-none",
                        MassTaskItemBatchAppendRequest.builder()
                                .eventCode("crawler.fetch-page")
                                .items(List.of(Map.of("url", "https://example.test")))
                                .build()));
        assertTrue(error.getMessage().contains("No worker group selector resolved"));
        verify(taskCommandService, never()).appendTaskItemsWithReceipt(any(), any());
    }

    @Test
    void targetWorkerIdRequiresExplicitWorkerGroupSelector() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);

        assertThrows(IllegalArgumentException.class, () -> app.createTaskShell(MassTaskShellCreateRequest.builder()
                .project("demoApp")
                .sharedConfig(Map.of(TaskSharedConfig.TARGET_WORKER_ID, "worker-1"))
                .build()));
    }

    @Test
    void appendTaskItemsPassesMapPayloadThroughWithoutShellInspection() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        stubAppendReceipts(taskCommandService);

        MassSdkApplication app = new MassSdkApplication(delegate);

        int added = app.appendTaskItems("task-map-001", MassTaskItemBatchAppendRequest.builder()
                .items(List.of(
                        Map.of("target", "hello"),
                        Map.of("target", "world")
                ))
                .build());

        assertEquals(2, added);
        verify(taskCommandService).appendTaskItemsWithReceipt("task-map-001", List.of(
                Map.of("target", "hello"),
                Map.of("target", "world")
        ));
    }

    @Test
    void resourceOperationsAllowSdkLevelProjectAndEventRegistrationWithoutRuntimeStart() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        EventDefinition eventDefinition = EventDefinition.builder()
                .code("bot.command")
                .name("Bot Command")
                .description("Handle a telegram-style bot command")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .defaultRoutingCode("bot")
                .handler((request, principal) -> EventResponse.success(Map.of("accepted", true), request.getRequestId()))
                .build();
        ProjectDefinition projectDefinition = ProjectDefinition.builder()
                .code("botApp")
                .name("Bot App")
                .description("Bot-oriented sdk catalog entry")
                .eventCodes(List.of("bot.command"))
                .build();

        app.registerEventDefinition(eventDefinition);
        app.registerProject(projectDefinition);

        Assertions.assertTrue(app instanceof ResourceOperations);
        Assertions.assertNotNull(app.getEvent("bot.command"));
        Assertions.assertEquals("bot.command", app.getEvent("bot.command").getCode());
        Assertions.assertEquals("Bot Command", app.getEvent("bot.command").getName());
        Assertions.assertEquals(projectDefinition, app.getProject("botApp"));
        Assertions.assertTrue(app.hasEvent("bot.command"));
        Assertions.assertTrue(app.hasProject("botApp"));
        Assertions.assertTrue(app.projectSupportsEvent("botApp", "bot.command"));
        Assertions.assertFalse(app.projectSupportsEvent("botApp", "crawler.fetch-page"));
        Assertions.assertTrue(app.listProjects().stream().anyMatch(project -> "demoApp".equals(project.getCode())));
        Assertions.assertTrue(app.listEvents().stream().anyMatch(event -> PlatformEventCodes.META_EVENTS_LIST.equals(event.getCode())));
        List<EventDefinition> projectEvents = app.getEventsForProject("botApp");
        Assertions.assertEquals(1, projectEvents.size());
        Assertions.assertEquals("bot.command", projectEvents.get(0).getCode());
        Assertions.assertEquals("Bot Command", projectEvents.get(0).getName());
        Assertions.assertEquals(List.of("botApp"), projectEvents.get(0).getProjectCodes());
    }

    @Test
    void registeredSdkEventsProjectFromCommandRuntimeDescriptorTruth() {
        MassApplication delegate = mock(MassApplication.class);
        when(delegate.getEventRuntime()).thenReturn(new InMemoryMassEventRuntime());
        MassSdkApplication app = new MassSdkApplication(delegate);
        app.registerEventDefinition(EventDefinition.builder()
                .code("bot.command")
                .name("Bot Command")
                .description("Handle a bot command")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .defaultRoutingCode("bot")
                .priorityClass(PriorityClass.INTERACTIVE)
                .responseMode(ResponseMode.STREAM)
                .targetScope(TargetScope.WORKER)
                .handler((request, principal) -> EventResponse.success(Map.of("accepted", true), request.getRequestId()))
                .build());
        app.registerProject(ProjectDefinition.builder()
                .code("botApp")
                .name("Bot App")
                .description("bot project")
                .eventCodes(List.of("bot.command"))
                .build());

        CoreEventDescriptor descriptor = requireDelegate(app).getEventRuntime().getDescriptor("bot.command");

        assertNotNull(descriptor);
        assertEquals("bot.command", descriptor.getEvent());
        assertEquals("Bot Command", descriptor.getName());
        assertEquals("Handle a bot command", descriptor.getDescription());
        assertEquals(List.of("TEXT", "JSON"), descriptor.getPayloadTypes());
        assertEquals(List.of("SINGLE_RUN", "STREAMING"), descriptor.getTaskModes());
        assertEquals("bot", descriptor.getDefaultRoutingCode());
        assertEquals(PriorityClass.INTERACTIVE, descriptor.getPriorityClass());
        assertEquals(ResponseMode.STREAM, descriptor.getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.NONE, descriptor.getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.STREAM, descriptor.getConvergenceMode());
        assertEquals(TargetScope.WORKER, descriptor.getTargetScope());
        assertEquals(List.of("botApp"), app.getEvent("bot.command").getProjectCodes());
        assertEquals(descriptor.getDescription(), app.getEvent("bot.command").getDescription());
        assertEquals(PriorityClass.INTERACTIVE, app.getEvent("bot.command").getPriorityClass());
        assertEquals(ResponseMode.STREAM, app.getEvent("bot.command").getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.NONE, app.getEvent("bot.command").getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.STREAM, app.getEvent("bot.command").getConvergenceMode());
        assertEquals(TargetScope.WORKER, app.getEvent("bot.command").getTargetScope());
    }

    @Test
    void sdkCatalogQueriesReadDirectlyFromRuntimeTruth() {
        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        MassApplication delegate = mock(MassApplication.class);
        when(delegate.getEventRuntime()).thenReturn(runtime);
        MassSdkApplication app = new MassSdkApplication(delegate);
        app.registerProject(ProjectDefinition.builder()
                .code("runtimeApp")
                .name("Runtime App")
                .description("runtime-backed project")
                .eventCodes(List.of("runtime.only"))
                .build());

        runtime.registerOrReplace(
                CoreEventDescriptor.builder()
                        .event("runtime.only")
                        .name("Runtime Only")
                        .description("Projected directly from event runtime")
                        .payloadTypes(List.of("JSON"))
                        .taskModes(List.of("SINGLE_RUN"))
                        .projectCodes(List.of("runtimeApp"))
                        .priorityClass(PriorityClass.BULK)
                        .responseMode(ResponseMode.FINAL_RESULT)
                        .deliveryAcknowledgementMode(DeliveryAcknowledgementMode.DELIVERY_ACCEPTED)
                        .convergenceMode(EventConvergenceMode.FINAL_RESULT)
                        .targetScope(TargetScope.TASK_ENGINE)
                        .enabled(true)
                        .build(),
                (request, principal) -> CoreEventResponse.success(Map.of("ok", true), request.getRequestId())
        );

        assertNotNull(app.getEvent("runtime.only"));
        assertEquals("Runtime Only", app.getEvent("runtime.only").getName());
        assertEquals(PriorityClass.BULK, app.getEvent("runtime.only").getPriorityClass());
        assertEquals(ResponseMode.FINAL_RESULT, app.getEvent("runtime.only").getResponseMode());
        assertEquals(DeliveryAcknowledgementMode.DELIVERY_ACCEPTED,
                app.getEvent("runtime.only").getDeliveryAcknowledgementMode());
        assertEquals(EventConvergenceMode.FINAL_RESULT, app.getEvent("runtime.only").getConvergenceMode());
        assertEquals(TargetScope.TASK_ENGINE, app.getEvent("runtime.only").getTargetScope());
        assertTrue(app.listEvents().stream().anyMatch(event -> "runtime.only".equals(event.getCode())));
        assertEquals(List.of("runtime.only"),
                app.getEventsForProject("runtimeApp").stream().map(EventDefinition::getCode).toList());
        assertEquals(List.of("runtime.only"),
                app.catalog().getEventsForProject("runtimeApp").stream()
                        .map(EventDefinition::getCode)
                        .toList());
        assertEquals(List.of("runtime.only"),
                app.catalog().getEventsForProject("runtimeApp").stream()
                        .map(EventDefinition::getCode)
                        .toList());
    }

    @Test
    void eventDefinitionBecomesSingleSourceForMetadataScopeAndHandler() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerProject(ProjectDefinition.builder()
                .code("botApp")
                .name("Bot App")
                .description("bot project")
                .eventCodes(List.of("bot.command"))
                .build());
        app.registerEventDefinition(EventDefinition.builder()
                .code("bot.command")
                .name("Bot Command")
                .description("handle a bot command directly")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .projectCodes(List.of("botApp"))
                .handler((request, principal) -> EventResponse.success(
                        Map.of(
                                "event", request.getEvent().value(),
                                "project", request.getProject(),
                                "userId", principal == null ? null : principal.getUserId()
                        ),
                        request.getRequestId()
                ))
                .build());

        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("bot.command")
                        .project("botApp")
                        .requestId("req-bot-command")
                        .payload(Map.of("text", "/start"))
                        .build(),
                eventPrincipal("client-a", "user-a", "botApp", "bot.command")
        );

        assertTrue(response.isSuccess());
        assertEquals("req-bot-command", response.getRequestId());
        assertEquals("bot.command", ((Map<?, ?>) response.getData()).get("event"));
        assertTrue(app.listEvents().stream().anyMatch(event -> "bot.command".equals(event.getCode())));
        assertEquals(List.of("bot.command"),
                app.getEventsForProject("botApp").stream().map(EventDefinition::getCode).toList());

        ControlPlaneCatalog metadataCatalog = app.catalog();
        assertTrue(metadataCatalog.listEvents().stream().anyMatch(event -> "bot.command".equals(event.getCode())));
        assertEquals(List.of("bot.command"),
                metadataCatalog.getEventsForProject("botApp").stream().map(EventDefinition::getCode).toList());

        ControlPlaneCatalog catalog = app.catalog();
        assertTrue(catalog.listEvents().stream().anyMatch(event -> "bot.command".equals(event.getCode())));
        assertEquals(List.of("bot.command"),
                catalog.getEventsForProject("botApp").stream().map(EventDefinition::getCode).toList());
    }

    @Test
    void submitterOperationsAllowCredentialBasedSubmitterRegistration() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        SubmitterRegistration submitterRegistration = SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("test-api-key")
                .userId("bot-user")
                .projectScope("telegramApp")
                .attributes(Map.of("channel", "telegram"))
                .build();

        app.registerSubmitter(submitterRegistration);

        Assertions.assertTrue(app instanceof AuthProvider);
        Assertions.assertTrue(app.hasSubmitter("telegram-bot"));
        SubmitterProfile submitterMetadata = SubmitterProfile.from(submitterRegistration);
        Assertions.assertEquals(List.of(submitterMetadata), app.listSubmitters());
        Assertions.assertEquals(submitterMetadata, app.getSubmitter("telegram-bot"));
        PrincipalContext submitterContext = app.authenticateSubmitter("test-api-key");
        Assertions.assertNotNull(submitterContext);
        Assertions.assertEquals("telegram-bot", submitterContext.getPrincipalId());
        Assertions.assertEquals("bot-user", submitterContext.getUserId());
        Assertions.assertEquals("telegramApp", submitterContext.getProjectScope());
        Assertions.assertEquals(List.of("task:create"), submitterContext.getPermissions());
        Assertions.assertEquals(List.of("telegramApp"), submitterContext.getProjectScopes());
        Assertions.assertEquals(List.of(), submitterContext.getEventScopes());
        Assertions.assertEquals(Map.of("channel", "telegram"), submitterContext.getAttributes());
        Assertions.assertEquals(submitterContext.getPrincipalId(), app.authenticate("test-api-key").getPrincipalId());
    }

    @Test
    void submitterOperationsAllowMultipleApiKeysForSameUserWithDifferentScopes() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-read-key")
                .credential("crawler-read-secret")
                .userId("crawler-user")
                .permissions(List.of("catalog:view"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("crawler-create-key")
                .credential("crawler-create-secret")
                .userId("crawler-user")
                .permissions(List.of("task:create"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .build());

        PrincipalContext readKey = app.authenticateSubmitter("crawler-read-secret");
        PrincipalContext createKey = app.authenticateSubmitter("crawler-create-secret");

        Assertions.assertNotNull(readKey);
        Assertions.assertNotNull(createKey);
        Assertions.assertEquals("crawler-user", readKey.getUserId());
        Assertions.assertEquals("crawler-user", createKey.getUserId());
        Assertions.assertFalse(readKey.hasPermission("task:create"));
        Assertions.assertTrue(createKey.hasPermission("task:create"));
        Assertions.assertEquals("crawler-read-key", readKey.getPrincipalId());
        Assertions.assertEquals("crawler-create-key", createKey.getPrincipalId());
    }

    @Test
    void submitterQueryApisDoNotExposeCredential() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("test-api-key")
                .projectScope("telegramApp")
                .build());

        SubmitterProfile metadata = app.getSubmitter("telegram-bot");

        Assertions.assertNotNull(metadata);
        Assertions.assertEquals("telegram-bot", metadata.getPrincipalId());
        Assertions.assertFalse(metadata.toString().contains("test-api-key"));
    }

    @Test
    void dispatchEventRequiresPrincipalEventScope() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        EventResponse allowed = app.dispatchEvent(
                EventRequest.builder()
                        .event(PlatformEventCodes.META_EVENTS_LIST)
                        .requestId("req-1")
                        .build(),
                eventPrincipal("client-a", "user-a", "*", PlatformEventCodes.META_EVENTS_LIST)
        );

        assertTrue(allowed.isSuccess());
        Assertions.assertEquals("req-1", allowed.getRequestId());
        Assertions.assertTrue(allowed.getData() instanceof List<?>);

        EventResponse denied = app.dispatchEvent(
                EventRequest.builder()
                        .event(PlatformEventCodes.META_EVENTS_LIST)
                        .requestId("req-2")
                        .build(),
                eventPrincipal("client-a", "missing-user", "*", "crawler.fetch-page")
        );

        assertFalse(denied.isSuccess());
        Assertions.assertEquals("FORBIDDEN", denied.getCode());
    }

    @Test
    void dispatchEventRejectsCatalogEventWhenProjectDoesNotSupportIt() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        registerExampleTaskCatalog(app);
        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("crawler.fetch-page")
                        .project("telegramApp")
                        .payload(Map.of("url", "https://example.test"))
                        .requestId("req-catalog-deny")
                        .build(),
                eventPrincipal("client-a", "user-a", "*", "crawler.fetch-page")
        );

        assertFalse(response.isSuccess());
        Assertions.assertEquals("FORBIDDEN", response.getCode());
    }

    @Test
    void dispatchEventRejectsTaskBackedCatalogTaskEntry() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            registerExampleTaskCatalog(app);
            app.start();
            EventResponse response = app.dispatchEvent(
                    EventRequest.builder()
                            .event("crawler.fetch-page")
                            .project("crawlerApp")
                            .headers(Map.of("taskName", "crawler-fetch-via-event"))
                            .payload(Map.of("url", "https://example.test/page-1"))
                            .requestId("req-catalog-create")
                            .build(),
                    eventPrincipal("client-a", "user-a", "crawlerApp", "crawler.fetch-page")
            );

            assertFalse(response.isSuccess());
            Assertions.assertEquals("TASK_BACKED_EVENT_REQUIRES_TASK_API", response.getCode());
            Assertions.assertEquals("req-catalog-create", response.getRequestId());
        } finally {
            app.stop();
        }
    }

    @Test
    void disabledSubmitterCannotAuthenticate() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("disabled-bot")
                .credential("disabled-key")
                .projectScope("telegramApp")
                .enabled(false)
                .build());

        Assertions.assertNull(app.authenticateSubmitter("disabled-key"));
    }

    @Test
    void duplicateCredentialAcrossDifferentSubmittersIsRejected() {
        MassSdkApplication app = new MassSdkApplication(mock(MassApplication.class));
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("telegram-bot")
                .credential("shared-key")
                .build());

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> app.registerSubmitter(SubmitterRegistration.builder()
                        .principalId("sms-bot")
                        .credential("shared-key")
                        .build()));
    }

    @Test
    void dispatchEventRejectsTaskBackedCatalogEventContract() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);

        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("crawler.fetch-page")
                        .project("crawlerApp")
                        .payload(Map.of("url", "https://example.test"))
                        .requestId("req-task-backed-reject")
                        .build(),
                eventPrincipal("client-a", "user-a", "crawlerApp", "crawler.fetch-page")
        );

        assertFalse(response.isSuccess());
        assertEquals("TASK_BACKED_EVENT_REQUIRES_TASK_API", response.getCode());
    }

    @Test
    void registerProjectMakesCustomProjectExecutableForEngineTaskCreation() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerEventDefinition(EventDefinition.builder()
                    .code("chatbot.reply")
                    .name("Chatbot Reply")
                    .description("custom chatbot reply")
                    .payloadTypes(List.of(PayloadType.JSON))
                    .taskModes(List.of(TaskMode.SINGLE_RUN))
                    .build());
            app.registerProject(ProjectDefinition.builder()
                    .code("botAppExecutableTest")
                    .name("Bot App Executable Test")
                    .description("custom runtime project")
                    .eventCodes(List.of("chatbot.reply"))
                    .build());
            declareSdkTestGroup(app, "bot-executable-workers", List.of(WorkerEventBinding.builder()
                    .eventCode("chatbot.reply")
                    .projectCodes(List.of("botAppExecutableTest"))
                    .build()));

            TaskDetailSnapshot task = createShellWithOptionalItems(app, MassTaskShellCreateRequest.builder()
                    .userId("bot-agent")
                    .project("botAppExecutableTest")
                    .sourceRef("custom-project-task")
                    .executionSpec(taskExecutionOptions(null, 1, 0, 0))
                    .build(), "chatbot.reply", List.of(Map.of("target", "chat-1")), false);

            assertNotNull(task);
            Assertions.assertEquals("botAppExecutableTest", task.getProject());
        } finally {
            app.stop();
        }
    }

    @Test
    void createTaskShellSupportsCustomRegisteredProjectAndEventCatalog() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerEventDefinition(EventDefinition.builder()
                    .code("bot.command")
                    .name("Bot Command")
                    .description("custom bot command")
                    .payloadTypes(List.of(PayloadType.TEXT))
                    .taskModes(List.of(TaskMode.SINGLE_RUN))
                    .build());
            app.registerProject(ProjectDefinition.builder()
                    .code("botAppCatalogTest")
                    .name("Bot App Catalog Test")
                    .description("custom runtime project")
                    .eventCodes(List.of("bot.command"))
                    .build());
            declareSdkTestGroup(app, "bot-catalog-workers", List.of(WorkerEventBinding.builder()
                    .eventCode("bot.command")
                    .projectCodes(List.of("botAppCatalogTest"))
                    .build()));

            TaskDetailSnapshot task = createShellWithOptionalItems(app, MassTaskShellCreateRequest.builder()
                    .userId("bot-agent")
                    .project("botAppCatalogTest")
                    .sourceRef("bot-command-task")
                    .executionSpec(taskExecutionOptions(null, 1, 0, 0))
                    .build(), "bot.command", List.of(Map.of("text", "/start")), false);

            assertNotNull(task);
            Assertions.assertEquals("botAppCatalogTest", task.getProject());
        } finally {
            app.stop();
        }
    }

    private static void registerExampleTaskCatalog(MassSdkApplication app) {
        app.registerEventDefinition(EventDefinition.builder()
                .code("crawler.fetch-page")
                .name("Crawler Fetch Page")
                .description("Example crawler fetch task event.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());
        app.registerEventDefinition(EventDefinition.builder()
                .code("sms.acquire-number")
                .name("SMS Acquire Number")
                .description("Example SMS acquire number task event.")
                .payloadTypes(List.of(PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN))
                .build());
        app.registerEventDefinition(EventDefinition.builder()
                .code("chatbot.reply")
                .name("Chatbot Reply")
                .description("Example chatbot reply task event.")
                .payloadTypes(List.of(PayloadType.TEXT, PayloadType.JSON))
                .taskModes(List.of(TaskMode.SINGLE_RUN, TaskMode.STREAMING))
                .build());

        app.registerProject(ProjectDefinition.builder()
                .code("crawlerApp")
                .name("Crawler App")
                .description("Example crawler project.")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        app.registerProject(ProjectDefinition.builder()
                .code("demoApp")
                .name("Demo App")
                .description("Example demo project.")
                .eventCodes(List.of("crawler.fetch-page"))
                .build());
        app.registerProject(ProjectDefinition.builder()
                .code("telegramApp")
                .name("Telegram App")
                .description("Example telegram project.")
                .eventCodes(List.of("chatbot.reply"))
                .build());
        app.registerProject(ProjectDefinition.builder()
                .code("rcsApp")
                .name("RCS App")
                .description("Example RCS project.")
                .eventCodes(List.of("sms.acquire-number", "chatbot.reply"))
                .build());
    }

    @Test
    void sdkRegistrationNormalizesWorkerAndContextContracts() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = new EngineConfig();
        WorkerStorage workerStorage = spy(config.getWorkerStorage());
        config.setWorkerStorage(workerStorage);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        stubDefaultTransportRegistrationResolution(delegate);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);
        Map<String, String> workerAttributes = new LinkedHashMap<>();
        workerAttributes.put(" type ", "crawler");
        workerAttributes.put(" ", "ignored");
        workerAttributes.put("null-value", null);
        declareSdkTestGroup(app, "crawler", List.of(WorkerEventBinding.builder()
                .eventCode("crawler.fetch-page")
                .projectCodes(List.of("crawlerApp"))
                .build()));
        bindSdkTestNode(app, "crawler");
        app.registerWorker(WorkerRegistration.builder()
                .workerId(" crawler-worker-001 ")
                .adapterNodeId(" sdk-test-node ")
                .workerGroupId(" crawler ")
                .transportHint(" POLLING ")
                .attributes(workerAttributes)
                .build());

        var workerCaptor = org.mockito.ArgumentCaptor.forClass(Worker.class);
        verify(workerStorage).addWorker(workerCaptor.capture());
        Worker worker = workerCaptor.getValue();
        Assertions.assertEquals("crawler-worker-001", worker.getWorkerId());
        Assertions.assertEquals("crawler", worker.getWorkerGroupId());
        Assertions.assertTrue(worker.getSupportedProjects().isEmpty());
        Assertions.assertTrue(worker.getSupportedEventCodes().isEmpty());
        Assertions.assertEquals("polling", worker.getOnlineStrategy());
        Assertions.assertEquals(Map.of("type", "crawler"), worker.getAttributes());
    }

    @Test
    void declareWorkerGroupCreatesGroupCapabilityBeforeWorkerRegistration() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = new EngineConfig();

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        stubDefaultTransportRegistrationResolution(delegate);

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);

        app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId("crawler")
                .eventBindings(List.of(WorkerEventBinding.builder()
                        .eventCode("crawler.fetch-page")
                        .projectCodes(List.of("crawlerApp"))
                        .build()))
                .defaultAttributes(Map.of("source", "declared"))
                .defaultMaxConcurrentWork(3)
                .build());

        WorkerResourceRuntime workerRuntime = config.getWorkerResourceRuntime();
        Assertions.assertTrue(workerRuntime.workerGroups().stream()
                .flatMap(group -> group.eventBindings().stream())
                .anyMatch(binding -> "crawler.fetch-page".equals(binding.eventCode())
                        && binding.projectCodes().contains("crawlerApp")));
        Assertions.assertTrue(workerRuntime.worker("crawler-worker-001").isEmpty());
        var group = workerRuntime.workerGroup("crawler").orElseThrow();
        Assertions.assertEquals(Map.of("source", "declared"), group.defaultAttributes());
        Assertions.assertEquals(3, group.defaultMaxConcurrentWork());

        bindSdkTestNode(app, "crawler");
        app.registerWorker(WorkerRegistration.builder()
                .workerId("crawler-worker-001")
                .adapterNodeId("sdk-test-node")
                .workerGroupId("crawler")
                .transportHint("polling")
                .build());

        WorkerResourceRecord registered = workerRuntime.worker("crawler-worker-001").orElseThrow();
        Assertions.assertEquals("crawler", registered.workerGroupId());
    }

    @Test
    void declareWorkerGroupRejectsUnknownEvent() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(new EngineConfig());

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                        .groupId("unknown")
                        .eventBindings(List.of(WorkerEventBinding.builder()
                                .eventCode("missing.event")
                                .build()))
                        .build())
        );

        Assertions.assertTrue(error.getMessage().contains("Unsupported worker event"));
    }

    @Test
    void declareWorkerGroupRejectsProjectOutsideEventScope() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(new EngineConfig());

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                        .groupId("crawler")
                        .eventBindings(List.of(WorkerEventBinding.builder()
                                .eventCode("crawler.fetch-page")
                                .projectCodes(List.of("telegramApp"))
                                .build()))
                        .build())
        );

        Assertions.assertTrue(error.getMessage().contains("outside event scope"));
    }

    @Test
    void declareWorkerGroupRequiresEventBindings() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(new EngineConfig());

        MassSdkApplication app = new MassSdkApplication(delegate);
        registerExampleTaskCatalog(app);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                        .groupId("empty")
                        .build())
        );

        Assertions.assertEquals("eventBindings is required", error.getMessage());
    }

    @Test
    void registerWorkerRejectsMissingTransportHint() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);

        MassSdkApplication app = new MassSdkApplication(delegate);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> app.registerWorker(WorkerRegistration.builder()
                        .workerId("worker-without-transport")
                        .build())
        );

        Assertions.assertEquals("transportHint must not be blank", error.getMessage());
    }

    @Test
    void registerWorkerRejectsAdapterLabelAsTransportHint() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = new EngineConfig();

        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        stubDefaultTransportRegistrationResolution(delegate);

        MassSdkApplication app = new MassSdkApplication(delegate);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> app.registerWorker(WorkerRegistration.builder()
                        .workerId("worker-with-websocket-label")
                        .adapterId("websocket")
                        .transportHint("websocket")
                        .build())
        );

        Assertions.assertEquals("Worker adapterId 'websocket' belongs to transportHint 'realtime', not 'websocket'",
                error.getMessage());
    }

    @Test
    void workerTransportHintsNormalizeOnlyCanonicalFamiliesAndPreserveCustomLabels() {
        Assertions.assertEquals(WorkerTransportHints.REALTIME, WorkerTransportHints.normalize(" REALTIME "));
        Assertions.assertEquals(WorkerTransportHints.POLLING, WorkerTransportHints.normalize(" POLLING "));
        Assertions.assertEquals("websocket", WorkerTransportHints.normalize("websocket"));
        Assertions.assertEquals("ws", WorkerTransportHints.normalize("ws"));
        Assertions.assertEquals("push", WorkerTransportHints.normalize("push"));
        Assertions.assertEquals("pull", WorkerTransportHints.normalize("pull"));
        Assertions.assertEquals("queue", WorkerTransportHints.normalize("queue"));
        Assertions.assertEquals("grpc", WorkerTransportHints.normalize("grpc"));
        Assertions.assertFalse(WorkerTransportHints.isRealtime("websocket_push"));
        Assertions.assertFalse(WorkerTransportHints.isPolling("pull"));
    }

    @Test
    void pullWorkerRejectsMissingWorker() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> app.pullWorker("missing-worker")
            );
            Assertions.assertEquals("Worker not found: missing-worker", error.getMessage());
        } finally {
            app.stop();
        }
    }

    @Test
    void registerWorkerRejectsMissingAdapterIdWhenRealtimeFamilyHasOnlyOneRuntimeAdapter() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(workerIdRouteBinding(new StubPushOnlyAdapter("websocket", WorkerTransportHints.REALTIME)))
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> app.registerWorker(WorkerRegistration.builder()
                            .workerId("realtime-worker-default-websocket")
                            .transportHint("realtime")
                            .build())
            );
            assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                    error.getMessage());
        } finally {
            app.stop();
        }
    }

    @Test
    void registerWorkerRejectsMissingAdapterIdWhenMultipleRealtimeAdaptersAreConfigured() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(
                        workerIdRouteBinding(new StubPushOnlyAdapter("websocket", WorkerTransportHints.REALTIME)),
                        workerIdRouteBinding(new StubPushOnlyAdapter("socket", WorkerTransportHints.REALTIME))
                )
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> app.registerWorker(WorkerRegistration.builder()
                            .workerId("realtime-worker-missing-adapter")
                            .transportHint("realtime")
                            .build())
            );
            assertEquals("worker adapterId must be set when transportHint 'realtime' is used",
                    error.getMessage());
        } finally {
            app.stop();
        }
    }

    @Test
    void registerWorkerUsesExplicitRealtimeAdapterIdWhenMultipleRealtimeAdaptersAreConfigured() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(
                        workerIdRouteBinding(new StubPushOnlyAdapter("websocket", WorkerTransportHints.REALTIME)),
                        workerIdRouteBinding(new StubPushOnlyAdapter("socket", WorkerTransportHints.REALTIME))
                )
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerWorker(WorkerRegistration.builder()
                    .workerId("realtime-worker-socket")
                    .adapterId("socket")
                    .transportHint("realtime")
                    .build());

            assertEquals("socket", app.getWorkerAdapterId("realtime-worker-socket"));
            assertEquals(WorkerTransportHints.REALTIME, app.getWorkerTransportHint("realtime-worker-socket"));
        } finally {
            app.stop();
        }
    }

    @Test
    void getWorkerTransportHintFallsBackToRegistryBindingInsteadOfNormalizingAdapterId() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(
                        workerIdRouteBinding(new StubPushOnlyAdapter("websocket", WorkerTransportHints.REALTIME)),
                        workerIdRouteBinding(new StubPushOnlyAdapter("socket", WorkerTransportHints.REALTIME))
                )
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerWorker(WorkerRegistration.builder()
                    .workerId("realtime-worker-websocket")
                    .adapterId("websocket")
                    .transportHint("realtime")
                    .build());
            Worker worker = requireDelegate(app).getEngine().getConfig()
                    .getWorkerStorage()
                    .getWorker("realtime-worker-websocket")
                    .orElseThrow();
            worker.setOnlineStrategy(null);
            assertTrue(requireDelegate(app).getEngine().getConfig().getWorkerStorage().updateWorker(worker));

            assertEquals(WorkerTransportHints.REALTIME, app.getWorkerTransportHint("realtime-worker-websocket"));
        } finally {
            app.stop();
        }
    }

    @Test
    void pullWorkerRejectsWorkerWithoutTransportIdentity() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            Worker worker = new Worker();
            worker.setWorkerId("worker-without-transport");
            requireDelegate(app).getEngine().getConfig().getWorkerStorage().addWorker(worker);

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> app.pullWorker("worker-without-transport")
            );
            Assertions.assertEquals("Cannot resolve transport binding for worker worker-without-transport: transportHint must not be blank",
                    error.getMessage());
        } finally {
            app.stop();
        }
    }

    @Test
    void pullWorkerRejectsRealtimeWorkerWhenTransportIsNotPullCapable() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(workerIdRouteBinding(new StubPushOnlyAdapter("websocket", WorkerTransportHints.REALTIME)))
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerWorker(WorkerRegistration.builder()
                    .workerId("realtime-worker-1")
                    .adapterId("websocket")
                    .transportHint("realtime")
                    .build());

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> app.pullWorker("realtime-worker-1")
            );
            Assertions.assertEquals("Worker adapter 'websocket' under transport 'realtime' is not pull-capable for worker realtime-worker-1",
                    error.getMessage());
        } finally {
            app.stop();
        }
    }

    @Test
    void pullWorkerRejectsUnsupportedTransportEvenWhenAnotherPullCapableBindingExists() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(workerIdRouteBinding(
                        new StubPullCapableAdapter("queue-consumer", "queue-consumer"),
                        new StubPullCapableAdapter("queue-consumer", "queue-consumer")))
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> app.registerWorker(WorkerRegistration.builder()
                            .workerId("polling-worker-unsupported")
                            .transportHint("polling")
                            .build())
            );
            Assertions.assertEquals("Unsupported worker transportHint 'polling'; available transportHints=[queue-consumer]",
                    error.getMessage());
        } finally {
            app.stop();
        }
    }

    @Test
    void pullWorkerResolvesByCanonicalTransportHintInsteadOfAdapterProtocolLabel() {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        StubPullCapableAdapter pollingAdapter = new StubPullCapableAdapter(
                "polling-http-v2",
                WorkerTransportHints.POLLING
        );
        WorkerTransportRuntimeFactory transportFactory = (workerResourceRuntime,
                                                         taskResultIngestChannel,
                                                         systemEventChannel,
                                                         workerPresenceStore,
                                                         deliveryService,
                                                         adapterBindings) -> new TransportRuntimeRegistry(
                workerResourceRuntime,
                taskResultIngestChannel,
                systemEventChannel,
                workerPresenceStore,
                List.of(workerIdRouteBinding(pollingAdapter, pollingAdapter))
        );

        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .workerTransportRuntimeFactory(transportFactory))
                .engine(engine -> engine.enabled(true).workerThreads(1))
                .build();

        try {
            app.start();
            app.registerWorker(WorkerRegistration.builder()
                    .workerId("polling-worker-canonical")
                    .transportHint("polling")
                    .build());

            PullWorkerSession session = app.pullWorker("polling-worker-canonical");
            Assertions.assertEquals(WorkerTransportHints.POLLING, session.transportHint());
        } finally {
            app.stop();
        }
    }

    @Test
    void pullWorkerSessionCompletesTaskWithoutWebsocketPush() throws Exception {
        MessageQueue<String> inputQueue = new InMemoryMessageQueue<>("input", String.class);
        MessageQueue<TransportOutboundMessage> outputQueue = new InMemoryMessageQueue<>("output", TransportOutboundMessage.class);
        MassSdkApplication app = MassSdk.builder()
                .transport(transport -> disableBundledWebSocket(transport, 0, "/sdk-transport")
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine.enabled(true).workerThreads(2))
                .build();

        try {
            registerExampleTaskCatalog(app);
            app.start();

            RuleDefinition rule = new RuleDefinition();
            rule.setId("polling-online-project");
            rule.setName("polling-online-project");
            rule.setType(RuleType.QL_EXPRESS);
            rule.setContent("isWorkerAvailable && supportsProject");
            app.replaceDefaultRules(List.of(rule));

            app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                    .groupId("polling-crawler")
                    .eventBindings(List.of(WorkerEventBinding.builder()
                            .eventCode("crawler.fetch-page")
                            .projectCodes(List.of("demoApp"))
                            .build()))
                    .build());
            bindSdkTestNode(app, "polling-crawler");
            app.registerWorker(WorkerRegistration.builder()
                    .workerId("polling-worker-1")
                    .adapterNodeId("sdk-test-node")
                    .workerGroupId("polling-crawler")
                    .transportHint("polling")
                    .build());

            PullWorkerSession session = app.pullWorker("polling-worker-1");
            session.connect();

            TaskDetailSnapshot task = createShellWithOptionalItems(app, MassTaskShellCreateRequest.builder()
                    .userId("crawler-agent")
                    .project("demoApp")
                    .sourceRef("fetch-page")
                    .sharedConfig(Map.of("mode", "pull"))
                    .executionSpec(taskExecutionOptions(null, 1, 0, 0))
                    .build(), "crawler.fetch-page", List.of(Map.of("url", "https://example.test/page-1")), false);

            assertTrue(app.approveTask(task.getTaskId()));

            TaskDispatchItem dispatchItem = waitFor(
                    Duration.ofSeconds(10),
                    () -> {
                        List<TaskDispatchItem> polled = session.poll(1, 250L);
                        return polled.isEmpty() ? null : polled.get(0);
                    }
            );
            assertNotNull(dispatchItem);
            Assertions.assertEquals(task.getTaskId(), dispatchItem.getTaskId());
            Assertions.assertEquals("https://example.test/page-1", dispatchItem.getInput().get("url"));
            Assertions.assertEquals("pull", dispatchItem.getSharedConfig().get("mode"));

            assertTrue(session.submitResult(
                    dispatchItem,
                    true,
                    "fetched",
                    Map.of("httpStatus", 200, "bodyLength", 42)
            ));

            TaskDetailSnapshot terminalTask = waitFor(
                    Duration.ofSeconds(5),
                    () -> {
                        TaskDetailSnapshot current = app.getTaskDetail(task.getTaskId());
                        return current != null && "TERMINAL".equals(current.getStatus()) ? current : null;
                    }
            );

            assertNotNull(terminalTask);
            Assertions.assertEquals("ALL_MESSAGES_SUCCEEDED", terminalTask.getTerminalReason());

            TaskResultWindowSnapshot resultWindow = app.readTaskResults(task.getTaskId(), 0, 10);
            Assertions.assertEquals(1, resultWindow.getItems().size());
            TaskResultItemSnapshot resultRow = resultWindow.getItems().getFirst();
            Assertions.assertEquals("polling-worker-1", resultRow.getWorkerId());
            Assertions.assertFalse(resultRow.getAttemptId().contains("-na-"));
            Assertions.assertEquals("SUCCESS", resultRow.getStatus());
        } finally {
            app.stop();
        }
    }

    @Test
    void appendTaskItemsAppliesBatchEventCodeWithoutPayloadRewriting() {
        MassApplication delegate = mock(MassApplication.class);
        MassEngine engine = mock(MassEngine.class);
        EngineConfig config = mock(EngineConfig.class);
        TaskCommandService taskCommandService = mock(TaskCommandService.class);
        when(delegate.getEngine()).thenReturn(engine);
        when(engine.isRunning()).thenReturn(true);
        when(engine.getConfig()).thenReturn(config);
        when(config.getTaskCommandService()).thenReturn(taskCommandService);
        stubAppendReceipts(taskCommandService);

        MassSdkApplication app = new MassSdkApplication(delegate);

        app.appendTaskItems("task-map-002", MassTaskItemBatchAppendRequest.builder()
                .eventCode("demo.dispatch")
                .items(List.of(
                        Map.of("target", "hello"),
                        Map.of("target", "world")
                ))
                .build());
        app.appendTaskItems("task-json-002", MassTaskItemBatchAppendRequest.builder()
                .eventCode("crawler.fetch-page")
                .items(List.of(Map.of("target", "https://example.test")))
                .build());

        verify(taskCommandService).appendTaskItemsWithReceipt("task-map-002", List.of(
                Map.of("target", "hello", "eventCode", "demo.dispatch"),
                Map.of("target", "world", "eventCode", "demo.dispatch")
        ));
        verify(taskCommandService).appendTaskItemsWithReceipt("task-json-002", List.of(
                Map.of("target", "https://example.test", "eventCode", "crawler.fetch-page")
        ));
    }

    @Test
    void removedSdkEscapeHatchesStayGone() {
        assertMissingMethod(MassSdk.class, "development", int.class, MessageQueue.class, MessageQueue.class);
        assertMissingMethod(MassSdk.class, "production", int.class, MessageQueue.class, MessageQueue.class);
        assertMissingMethod(MassApplicationBuilder.class, "createDevelopment", int.class, MessageQueue.class, MessageQueue.class);
        assertMissingMethod(MassApplicationBuilder.class, "createProduction", int.class, MessageQueue.class, MessageQueue.class);
        assertMissingMethod(MassSdkApplication.class, "unwrap");
        assertMissingMethod(MassSdkApplication.class, "getEngine");
        assertMissingMethod(MassSdkApplication.class, "getTaskManager");
        assertMissingMethod(MassSdkApplication.class, "getWorkerManager");
        assertMissingMethod(MassSdkApplication.class, "updateTask", Task.class);
        assertMissingMethod(MassSdkApplication.class, "updateWorker", Worker.class);
        assertMissingMethod(MassSdkApplication.class, "publishTaskEvents");
        assertMissingMethod(MassSdkApplication.class, "listSessions");
        assertMissingMethod(MassSdkApplication.class, "getSessionStats");
        assertMissingMethod(MassSdkApplication.class, "enqueueRawMessage", Map.class);
        assertMissingMethod(MassSdkApplication.class, "getQueueDetail");
        assertMissingMethod(MassSdkApplication.class, "getQueueMetrics");
        assertMissingMethod(MassSdk.Builder.class, "unwrap");
        assertMissingMethod(MassSdk.TransportOptions.class, "unwrap");
        assertMissingMethod(MassSdk.EngineOptions.class, "unwrap");
        assertMissingMethod(MassEngine.class, "addWorker", Worker.class);
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.sdk.model.WorkerContextRegistration"));
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.sdk.model.WorkerContextSnapshot"));
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.base.model.WorkerContext"));
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.base.enums.worker.WorkerContextStatus"));
        assertMissingMethod(MassEngine.class, "getTaskManager");
        assertMissingMethod(MassEngine.class, "getWorkerManager");
        assertMissingMethod(MassEngine.class, "publishTaskEvents");
        assertMissingMethod(EngineConfig.class, "getWorkerManager");
        assertMissingMethod(EngineConfig.class, "setWorkerManager", WorkerManager.class);
        assertMissingMethod(EngineConfig.class, "setRuleManager", com.xa.mass.engine.rules.RuleManager.class);
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.sdk.TaskOperations"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.sdk.WorkerOperations"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.sdk.TransportOperations"));
    }

    @Test
    void removedWebSocketCompatibilityEscapeHatchesStayGone() {
        Assertions.assertThrows(NoSuchMethodException.class, () -> MassApplication.class.getDeclaredMethod("getDispatcherContext"));
        Assertions.assertThrows(NoSuchMethodException.class, () -> MassApplication.class.getDeclaredMethod("getMessageTransporter"));
        Assertions.assertThrows(NoSuchFieldException.class, () -> MassApplication.class.getDeclaredField("massWebSocketAdapter"));
        Assertions.assertThrows(NoSuchFieldException.class, () -> MassApplication.class.getDeclaredField("webSocketConfig"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.builder.MassGatewayBuilder"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.worker.PollingWorkerAdapter"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.worker.WebSocketWorkerAdapter"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.worker.TransportRoutingTaskMsgDispatchListener"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.transport.DefaultWorkerTransportRuntimeFactory"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.transport.TransportRuntimeRegistry"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.gateway.runtime.WebSocketEmbeddedRuntimeSupport"));
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.transport.websocket.runtime.WebSocketEmbeddedRuntimeSupport"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.MassWebSocketAdapter"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.config.WebSocketConfig"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.starter.config.WebSocketRuntimeComposition"));
        Assertions.assertThrows(ClassNotFoundException.class, () -> Class.forName("com.xa.mass.transport.websocket.dispatcher.WebSocketMessageDispatcher"));
        assertMissingMethod(MassApplicationBuilder.class, "createApiMode", int.class, String.class, String.class, String.class);
        assertMissingMethod(MassApplicationBuilder.class, "server", int.class);
        assertMissingMethod(MassApplicationBuilder.class, "server", int.class, String.class);
        assertMissingMethod(MassApplicationBuilder.class, "transportServer", int.class);
        assertMissingMethod(MassApplicationBuilder.class, "transportServer", int.class, String.class);
        assertMissingMethod(MassApplicationBuilder.class, "websocket", Consumer.class);
        assertMissingMethod(MassSdk.class, "apiMode", int.class, String.class, String.class, String.class);
        assertMissingMethod(MassSdk.Builder.class, "server", int.class);
        assertMissingMethod(MassSdk.Builder.class, "server", int.class, String.class);
        assertMissingMethod(MassSdk.Builder.class, "transportServer", int.class);
        assertMissingMethod(MassSdk.Builder.class, "transportServer", int.class, String.class);
        assertMissingMethod(MassSdk.Builder.class, "websocket", Consumer.class);
        assertMissingMethod(MassApplicationBuilder.TransportBuilder.class, "enabled", boolean.class);
        assertMissingMethod(MassApplicationBuilder.TransportBuilder.class, "transportServerEnabled", boolean.class);
        assertMissingMethod(MassApplicationBuilder.TransportBuilder.class, "transportEndpointPath", String.class);
        assertMissingMethod(MassApplicationBuilder.TransportBuilder.class, "transportServerFactory", TransportServerFactory.class);
        assertMissingMethod(MassApplicationBuilder.TransportBuilder.class, "maxConnections", int.class);
        assertMissingMethod(MassSdk.TransportOptions.class, "enabled", boolean.class);
        assertMissingMethod(MassSdk.TransportOptions.class, "transportServerEnabled", boolean.class);
        assertMissingMethod(MassSdk.TransportOptions.class, "transportEndpointPath", String.class);
        assertMissingMethod(MassSdk.TransportOptions.class, "transportServerFactory", TransportServerFactory.class);
        assertMissingMethod(MassSdk.TransportOptions.class, "maxConnections", int.class);
        assertMissingMethod(MassSdk.TransportOptions.class, "apiMode", String.class, String.class, String.class);
        assertMissingMethod(TransportConfig.class, "setEnabled", boolean.class);
        assertMissingMethod(TransportConfig.class, "isTransportServerEnabled");
        assertMissingMethod(TransportConfig.class, "setTransportServerEnabled", boolean.class);
        assertMissingMethod(TransportConfig.class, "getTransportServerPort");
        assertMissingMethod(TransportConfig.class, "setTransportServerPort", int.class);
        assertMissingMethod(TransportConfig.class, "getTransportEndpointPath");
        assertMissingMethod(TransportConfig.class, "setTransportEndpointPath", String.class);
        assertMissingMethod(TransportConfig.class, "getMaxConnections");
        assertMissingMethod(TransportConfig.class, "setMaxConnections", int.class);
        assertMissingMethod(TransportConfig.class, "getTransportServerFactory");
        assertMissingMethod(TransportConfig.class, "setTransportServerFactory", TransportServerFactory.class);
        assertMissingMethod(TransportConfig.class, "createMessageTransporter");
        assertMissingMethod(
                TransportConfig.class,
                "createDispatcherContext",
                com.xa.mass.base.channel.tranporter.MessageTransporter.class,
                WorkerEndpointRegistry.class,
                TaskResultIngestChannel.class,
                WorkerSystemEventChannel.class
        );
        assertMissingMethod(TransportConfig.class, "resolveWorkerEndpointRegistry");
        assertMissingMethod(TransportConfig.class, "resolveSystemEventChannel");
        assertMissingMethod(TransportConfig.class, "resolveWorkerTransportRuntimeFactory");
        assertMissingMethod(TransportConfig.class, "resolveTransportAdapterBootstrap");
        assertMissingMethod(TransportConfig.class, "resolveSocketTransportAdapterBootstrap");
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext"));
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.transport.websocket.session.EventBusWorkerSystemEventChannel"));
        assertMissingMethod(TransportRuntimeComposition.class, "isTransportServerEnabled");
        assertMissingMethod(TransportRuntimeComposition.class, "getTransportServerPort");
        assertMissingMethod(TransportRuntimeComposition.class, "getTransportEndpointPath");
        assertMissingMethod(TransportRuntimeComposition.class, "getMaxConnections");
        Assertions.assertThrows(NoSuchMethodException.class, () -> TransportServerFactoryContext.class.getDeclaredMethod("getFrameCodec"));
        Assertions.assertThrows(NoSuchMethodException.class, () -> TransportServerFactoryContext.class.getDeclaredConstructor(
                WorkerEndpointRegistry.class,
                com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec.class,
                java.util.function.Consumer.class,
                int.class,
                String.class
        ));
        Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.xa.mass.transport.runtime.WorkerTransportRuntimeFactoryContext"));
    }

    private static void assertEngineOperationsFailFast(MassSdkApplication app) {
        List<Executable> operations = List.of(
                () -> app.getTaskDetail("task-1"),
                () -> app.getTaskSummariesByStatus("READY"),
                () -> app.approveTask("task-1"),
                () -> app.rejectTask("task-1"),
                () -> app.blockTask("task-1"),
                () -> app.pauseTask("task-1"),
                () -> app.resumeTaskDetailed("task-1"),
                () -> app.resumeTask("task-1"),
                () -> app.cancelTask("task-1"),
                () -> app.terminateTask("task-1", "MANUAL_CANCELLED"),
                () -> app.appendTaskItems("task-1", MassTaskItemBatchAppendRequest.builder().items(List.of()).build()),
                () -> app.sealTask("task-1"),
                () -> app.taskDiagnostics().resolveTaskState("task-1"),
                () -> app.taskDiagnostics().validateTaskState("task-1"),
                () -> app.getWorker("worker-1"),
                app::getAllWorkers,
                () -> runtimeDiagnostics(app).isWorkerLocked("worker-1"),
                () -> app.isWorkerOnline("worker-1"),
                () -> app.declareWorkerGroup(WorkerGroupDeclaration.builder().groupId("group-1").build()),
                () -> app.registerWorker(WorkerRegistration.builder().workerId("worker-1").build()),
                () -> app.pullWorker("worker-1"),
                () -> app.replaceDefaultRules(List.of())
        );

        for (Executable operation : operations) {
            Assertions.assertThrows(IllegalStateException.class, operation);
        }
    }

    private static void assertEventDispatchRunsOnVirtualThread(MassSdkApplication app, String requestId) {
        EventResponse response = app.dispatchEvent(
                EventRequest.builder()
                        .event("sdk.event.fast")
                        .requestId(requestId)
                        .build(),
                eventPrincipal("client-a", "user-a", "*", "sdk.event.fast")
        );

        assertTrue(response.isSuccess());
        assertEquals(requestId, response.getRequestId());
        assertEquals(true, ((Map<?, ?>) response.getData()).get("virtualThread"));
    }

    private static PrincipalContext eventPrincipal(String principalId,
                                                   String userId,
                                                   String projectScope,
                                                   String... eventCodes) {
        return PrincipalContext.builder()
                .principalId(principalId)
                .userId(userId)
                .projectScopes(projectScope == null ? List.of() : List.of(projectScope))
                .eventScopes(eventCodes == null ? List.of() : List.of(eventCodes))
                .build();
    }

    private static MassSdk.TransportOptions disableBundledWebSocket(MassSdk.TransportOptions transport,
                                                                    int port,
                                                                    String endpointPath) {
        return transport.webSocketAdapter(webSocket -> webSocket
                .server(port, endpointPath)
                .enabled(false)
                .serverEnabled(false));
    }

    private static MassSdkApplication explicitRealtimeRuntime(int port,
                                                              int workerThreads,
                                                              int maxConnections) {
        return MassSdk.builder()
                .transport(transport -> transport
                        .webSocketAdapter(webSocket -> webSocket
                                .server(port)
                                .enabled(true)
                                .maxConnections(maxConnections))
                        .inputQueue(new InMemoryMessageQueue<>("input", String.class))
                        .outputQueue(new InMemoryMessageQueue<>("output", TransportOutboundMessage.class)))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(workerThreads))
                .build();
    }

    private static MassApplication requireDelegate(MassSdkApplication app) {
        return readField(app, "delegate", MassApplication.class);
    }

    private static TransportAdapterBootstrap adapterBootstrap(TransportRuntimeComposition runtimeComposition,
                                                              String adapterId) {
        return runtimeComposition.resolveTransportAdapterBootstraps().stream()
                .filter(bootstrap -> bootstrap.descriptor() != null
                        && adapterId.equals(bootstrap.descriptor().getAdapterId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing adapter bootstrap for " + adapterId));
    }

    private static TransportDeliveryService deliveryService() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private static void shutdownRuntimeTaskExecutor(VirtualThreadRuntimeTaskExecutor executor) {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static <T> T readField(Object target, String fieldName, Class<T> fieldType) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertMissingMethod(Class<?> type, String methodName, Class<?>... parameterTypes) {
        Assertions.assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod(methodName, parameterTypes));
    }

    private static TransportBinding workerIdRouteBinding(WorkerAdapter adapter) {
        return TransportBinding.builder(adapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> dispatchBinding != null ? dispatchBinding.workerId() : null)
                .build();
    }

    private static TransportBinding workerIdRouteBinding(WorkerAdapter adapter, TaskPullChannel taskPullChannel) {
        return TransportBinding.builder(adapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> dispatchBinding != null ? dispatchBinding.workerId() : null)
                .taskPullChannel(taskPullChannel)
                .build();
    }

    private static <T> T waitFor(Duration timeout, ThrowingSupplier<T> supplier) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            Thread.sleep(50L);
        }
        return supplier.get();
    }

    private static void stubDefaultTransportRegistrationResolution(MassApplication delegate) {
        TransportRegistrationResolver resolver = new TransportRegistrationResolver(List.of(
                new TransportAdapterDescriptor(
                        WorkerTransportHints.POLLING,
                        WorkerTransportHints.POLLING
                ),
                new TransportAdapterDescriptor(
                        "websocket",
                        WorkerTransportHints.REALTIME
                )
        ));
        when(delegate.resolveRegistrationAdapterId(any(), any()))
                .thenAnswer(invocation -> resolver.resolveRegistrationAdapterId(
                        invocation.getArgument(0, String.class),
                        invocation.getArgument(1, String.class)
                ));
    }

    private static void bindSdkTestNode(MassSdkApplication app, String workerGroupId) {
        app.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId("sdk-test-node")
                .adapterType(WorkerTransportHints.POLLING)
                .endpointId("sdk-test")
                .build());
        app.bindNodeGroup(NodeGroupBindingRegistration.builder()
                .adapterNodeId("sdk-test-node")
                .workerGroupId(workerGroupId)
                .build());
    }

    private static void declareSdkTestGroup(MassSdkApplication app,
                                            String workerGroupId,
                                            List<WorkerEventBinding> eventBindings) {
        app.declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId(workerGroupId)
                .eventBindings(eventBindings)
                .build());
    }

    private static WorkerGroupRecord group(String workerGroupId, String projectCode, String eventCode) {
        return WorkerGroupRecord.builder(workerGroupId)
                .eventBindings(List.of(EventBinding.of(eventCode, List.of(projectCode))))
                .build();
    }

    private static WorkerResourceRecord workerResource(String workerId, String workerGroupId) {
        return new WorkerResourceRecord(
                workerId,
                WorkerStatus.ONLINE.name(),
                null,
                null,
                List.of("demoApp"),
                List.of(),
                workerGroupId,
                null,
                null,
                null,
                1,
                Map.of(),
                null,
                null
        );
    }

    private static TaskDetailSnapshot createShellWithOptionalItems(MassSdkApplication app,
                                                                   MassTaskShellCreateRequest request,
                                                                   String eventCode,
                                                                   List<Object> items,
                                                                   boolean keepIntakeOpen) {
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(request, "request");
        String taskId = app.createTaskShell(request).getTaskId();
        if (items != null && !items.isEmpty()) {
            app.appendTaskItems(taskId, MassTaskItemBatchAppendRequest.builder()
                    .eventCode(eventCode)
                    .items(items)
                    .build());
        }
        if (!keepIntakeOpen) {
            assertTrue(app.sealTask(taskId));
        }
        return app.getTaskDetail(taskId);
    }

    private static void stubAppendReceipts(TaskCommandService taskCommandService) {
        when(taskCommandService.appendTaskItemsWithReceipt(any(), any()))
                .thenAnswer(invocation -> {
                    String taskId = invocation.getArgument(0, String.class);
                    List<?> items = invocation.getArgument(1, List.class);
                    return new TaskAppendReceipt(taskId, items.size(), List.of());
                });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class StubPushOnlyAdapter implements WorkerAdapter {
        private final String protocol;
        private final String transportHint;

        private StubPushOnlyAdapter(String protocol) {
            this(protocol, WorkerTransportHints.normalize(protocol));
        }

        private StubPushOnlyAdapter(String protocol, String transportHint) {
            this.protocol = protocol;
            this.transportHint = transportHint;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public String transportHint() {
            return transportHint;
        }

        @Override
        public List<com.xa.mass.transport.model.DispatchOutcome> dispatchEnvelopes(
                List<com.xa.mass.transport.model.TransportDispatchEnvelope> envelopes) {
            return envelopes == null ? List.of() : envelopes.stream()
                    .map(envelope -> com.xa.mass.transport.model.DispatchOutcome.sent(adapterId(), envelope))
                    .toList();
        }
    }

    private static final class StubPullCapableAdapter implements WorkerAdapter, TaskPullChannel {
        private final String protocol;
        private final String transportHint;

        private StubPullCapableAdapter(String protocol) {
            this(protocol, WorkerTransportHints.normalize(protocol));
        }

        private StubPullCapableAdapter(String protocol, String transportHint) {
            this.protocol = protocol;
            this.transportHint = transportHint;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public String transportHint() {
            return transportHint;
        }

        @Override
        public List<com.xa.mass.transport.model.DispatchOutcome> dispatchEnvelopes(
                List<com.xa.mass.transport.model.TransportDispatchEnvelope> envelopes) {
            return envelopes == null ? List.of() : envelopes.stream()
                    .map(envelope -> com.xa.mass.transport.model.DispatchOutcome.sent(adapterId(), envelope))
                    .toList();
        }

        @Override
        public TaskPullResult pollTaskMessagesResult(String workerId, int maxMessages, long timeoutMillis) {
            return TaskPullResult.empty();
        }
    }

    private static final class StaticDedicatedServerBootstrap
            implements TransportAdapterBootstrap {

        private final TransportServer transportServer;

        private StaticDedicatedServerBootstrap(TransportServer transportServer) {
            this.transportServer = transportServer;
        }

        @Override
        public void contribute(TransportAdapterBootstrapContext context) {
            context.registerTransportServer(transportServer);
        }
    }

    private static void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    @SuppressWarnings("unchecked")
    private static boolean hasActiveSocketConnection(Map<String, Object> session) {
        Object connections = session.get("connections");
        if (!(connections instanceof List<?> list)) {
            return false;
        }
        return list.stream().anyMatch(connection -> {
            if (!(connection instanceof Map<?, ?> connectionInfo)) {
                return false;
            }
            return Boolean.TRUE.equals(connectionInfo.get("active"))
                    && Objects.equals("socket", connectionInfo.get("adapterId"));
        });
    }

    private static final class DescriptorOnlyBootstrap
            implements TransportAdapterBootstrap {

        private final TransportAdapterDescriptor descriptor;

        private DescriptorOnlyBootstrap(TransportAdapterDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public TransportAdapterDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public void contribute(TransportAdapterBootstrapContext context) {
        }
    }

    private static final class StubTransportDeliveryStore implements TransportDeliveryStore {
        private final AtomicBoolean shutdownCalled = new AtomicBoolean(false);

        @Override
        public com.xa.mass.transport.model.DispatchOutcome enqueue(
                com.xa.mass.transport.model.TransportDispatchEnvelope envelope) {
            return com.xa.mass.transport.model.DispatchOutcome.queued(
                    envelope == null ? null : envelope.getAdapterId(),
                    envelope
            );
        }

        @Override
        public List<com.xa.mass.transport.model.TransportDispatchEnvelope> drain(String adapterId, String routeKey, int maxItems) {
            return List.of();
        }

        @Override
        public TransportDeliveryPollResult poll(String adapterId,
                                                String routeKey,
                                                int maxItems,
                                                long timeout,
                                                TimeUnit unit) {
            return TransportDeliveryPollResult.empty();
        }

        @Override
        public TransportDeliveryStoreStats stats() {
            return new TransportDeliveryStoreStats(0, 0, 0, 1);
        }

        @Override
        public void shutdown() {
            shutdownCalled.set(true);
        }
    }

    private static final class StubWorkerPresenceStore
            implements com.xa.mass.transport.presence.WorkerPresenceStore, AutoCloseable {

        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final InMemoryWorkerPresenceStore delegate = new InMemoryWorkerPresenceStore();

        @Override
        public com.xa.mass.transport.presence.WorkerPresence markOnline(String workerId,
                                                                        String adapterId,
                                                                        String routeKey,
                                                                        String connectionId,
                                                                        String reason) {
            return delegate.markOnline(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public com.xa.mass.transport.presence.WorkerPresence refreshHeartbeat(String workerId,
                                                                              String adapterId,
                                                                              String routeKey,
                                                                              String connectionId,
                                                                              String reason) {
            return delegate.refreshHeartbeat(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public com.xa.mass.transport.presence.WorkerPresence markOffline(String workerId,
                                                                         String adapterId,
                                                                         String routeKey,
                                                                         String connectionId,
                                                                         String reason) {
            return delegate.markOffline(workerId, adapterId, routeKey, connectionId, reason);
        }

        @Override
        public com.xa.mass.transport.presence.WorkerPresence getPresence(String workerId) {
            return delegate.getPresence(workerId);
        }

        @Override
        public boolean isRouteOnline(String adapterId, String routeKey) {
            return delegate.isRouteOnline(adapterId, routeKey);
        }

        @Override
        public List<com.xa.mass.transport.presence.WorkerPresence> listActivePresences() {
            return delegate.listActivePresences();
        }

        @Override
        public int pruneExpired() {
            return delegate.pruneExpired();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static TaskExecutionSpec taskExecutionSpec(com.xa.mass.base.enums.task.TaskWorkloadClass workloadClass,
                                                       int batchSize,
                                                       int maxRuntimeSeconds,
                                                       int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setWorkloadClass(workloadClass);
        spec.setBatchSize(batchSize);
        spec.setMaxRuntimeSeconds(maxRuntimeSeconds);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static TaskExecutionOptions taskExecutionOptions(com.xa.mass.base.enums.task.TaskWorkloadClass workloadClass,
                                                             int batchSize,
                                                             int maxRuntimeSeconds,
                                                             int defaultMaxRetryCount) {
        TaskExecutionOptions spec = new TaskExecutionOptions();
        spec.setWorkloadClass(workloadClass == null ? null : workloadClass.name());
        spec.setBatchSize(batchSize);
        spec.setMaxRuntimeSeconds(maxRuntimeSeconds);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static RuntimeDiagnosticsOperations runtimeDiagnostics(MassSdkApplication app) {
        return app.runtimeDiagnostics();
    }

    private static TransportDebugOperations rawTransportDebug(MassSdkApplication app) {
        return new DefaultTransportDebugOperations(app.runtimeApplication());
    }

}
