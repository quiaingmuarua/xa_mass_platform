package com.xa.mass.transport.runtime;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportConvergenceArchitectureGuardTest {

    @Test
    void transportRuntimeDoesNotImportWorkerResourceRuntime() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java")
                ),
                "com.xa.mass.worker.runtime",
                "WorkerResourceQueryRuntime"
        );
    }

    @Test
    void transportRuntimeAndPollingAdapterDoNotDependOnWorkerRuntime() throws IOException {
        assertNoTextFilesContain(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/pom.xml"),
                        repoRoot().resolve("transport/polling-adapter/pom.xml")
                ),
                "<artifactId>xa-mass-worker-runtime</artifactId>"
        );
    }

    @Test
    void transportAdaptersDoNotDependOnEngineOrWorkerRuntime() throws IOException {
        assertNoTextFilesContain(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/pom.xml"),
                        repoRoot().resolve("transport/socket-adapter/pom.xml"),
                        repoRoot().resolve("transport/websocket-adapter/pom.xml")
                ),
                "<artifactId>xa-mass-engine</artifactId>",
                "<artifactId>xa-mass-worker-runtime</artifactId>"
        );
    }

    @Test
    void transportProductionSourceDoesNotImportEngineOrWorkerRuntime() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java")
                ),
                "com.xa.mass.engine",
                "com.xa.mass.worker.runtime"
        );
    }

    @Test
    void oldWorkerLifecycleChannelAndRouteProjectorDoNotReappear() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker")
                ),
                "WorkerSystemEventChannel",
                "NoopWorkerSystemEventChannel",
                "RuntimeEventBusWorkerSystemEventChannel",
                "TracingWorkerSystemEventChannel",
                "TransportRouteLifecycleProjector",
                "publishWorkerOnline(",
                "publishWorkerOffline(",
                "publishWorkerHeartbeat("
        );
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/WorkerSystemEventChannel.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/NoopWorkerSystemEventChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RuntimeEventBusWorkerSystemEventChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TracingWorkerSystemEventChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportRouteLifecycleProjector.java")
        );
    }

    @Test
    void endpointLeaseRecordsDoNotBecomePresencePayloadOrProjectionInput() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/WorkerRuntimePresenceIngress.java"),
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/presence")
                ),
                "TransportEndpointLeaseStore",
                "TransportEndpointLeaseRecord",
                "TransportEndpointLeaseMetadata",
                "claimEndpointLease",
                "refreshEndpointLease",
                "releaseEndpointLease",
                "TransportRouteOwnerRecord",
                "TransportRouteOwnerStore",
                "claimRouteOwner",
                "refreshHeartbeat",
                "releaseRouteOwner"
        );
    }

    @Test
    void transportDataPlaneDoesNotDependOnRouteKeyMintCodec() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java")
                ),
                "CanonicalWorkerGroupRouteKeyCodec",
                "CanonicalWorkerRouteKeyCodec"
        );
    }

    @Test
    void oldNodeTargetedDispatchMainlineIsRemoved() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("xa-mass-base/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java")
                ),
                "NodeTargetedTaskDispatchHandoff",
                "NodeTargetedTaskDispatchSubmitter",
                "TransportRoutingTaskDispatchListener",
                "RedisNodeTargetedTaskDispatchHandoff",
                "RedisTaskDispatchHandoff"
        );
    }

    @Test
    void pollingMainlineDoesNotUseRouteKeyOnlyTaskPull() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker")
                ),
                "pollTaskMessagesResult(routeKey",
                "pollTaskMessages(routeKey",
                "pollEnvelopeResult(PROTOCOL, routeKey",
                "pollEnvelopeResult(adapterId, routeKey",
                "deliveryStore.poll(adapterId, routeKey",
                "deliveryStore.drain(adapterId, routeKey"
        );
    }

    @Test
    void workerFacingPollingApisDoNotExposeDeliveryQueueKey() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("xa-mass-server/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker")
                ),
                "deliveryQueueKey",
                "DeliveryQueueKey"
        );
    }

    @Test
    void directDispatchChannelsUseEnvelopeSelectedWorkerConstraint() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketTaskDispatchChannel.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTaskDispatchChannel.java")
                ),
                "payloadString(TransportPacket.PAYLOAD_WORKER_ID)",
                "sendToAdapterRoute("
        );
    }

    @Test
    void engineCoreDoesNotImportTransportContracts() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("xa-mass-engine/src/main/java")),
                "com.xa.mass.transport"
        );
    }

    @Test
    void endpointRegistryCannotFallbackSelectedWorkerSendToRouteOnlySend() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointRegistry.java")),
                "sendToAdapterRoute(",
                "isAdapterRouteOnline("
        );
    }

    @Test
    void deliveryCommandSubmitterDoesNotUseRouteOwnerScansForSelectedWorkerLookup() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchDeliveryCommandSubmitter.java")),
                "TransportEndpointLeaseStore",
                "TransportEndpointLeaseView",
                "currentEndpointLease(",
                "WorkerDispatchRouteOwnerView",
                "WorkerDispatchRouteOwner",
                "TransportNodeRegistry",
                "activeOwnerForSelectedWorker(",
                "activeOwners(",
                "currentOwners("
        );
    }

    @Test
    void sdkWorkerInspectionDoesNotReadTransportRouteOwner() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdkApplication.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/WorkerInspectionOperations.java")
                ),
                "TransportEndpointLease",
                "currentEndpointLease(",
                "WorkerDispatchRouteOwnerView",
                "activeOwnerForSelectedWorker(",
                "getWorkerRouteOwnerView("
        );
    }

    @Test
    void starterDoesNotExposeRouteOwnerViewAsSdkReadableInspectionSurface() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java")),
                "public TransportEndpointLease",
                "public WorkerDispatchRouteOwnerView",
                "getWorkerRouteOwnerView("
        );
    }

    @Test
    void pullWorkerSessionDoesNotExposeTransportInternalConstructors() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/PullWorkerSession.java")),
                "public PullWorkerSession("
        );
    }

    @Test
    void deliveryCommandBatchCodecDoesNotNestTaskBatchJson() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchCodec.java")),
                "taskBatchJson"
        );
    }

    @Test
    void redisDeliveryCommandHandoffDoesNotUseRouteOrLaneQueues() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDeliveryCommandHandoff.java")),
                "\":route:\"",
                "ready-routes",
                "routeQueueKey(",
                "commands.lpop(",
                "ready-lanes",
                "targetTransportNodeId",
                "\":lane:\""
        );
    }

    @Test
    void pollingPullQueuePlacementDoesNotUseAdapterId() throws IOException {
        Path deliveryService = repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryService.java");
        String source = Files.readString(deliveryService);
        assertTrue(source.contains("AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId)"),
                "Polling pull queue placement must derive from deliveryBucketId");
        assertTrue(!source.contains("resolveDeliveryQueueKey(adapterId)"),
                "Polling pull queue placement must not derive from adapterId");

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/InMemoryTransportDeliveryStore.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDeliveryStore.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisQueuedPulledDispatchCodec.java")),
                "normalizeAdapterId(value)",
                "worker-index"
        );
    }

    @Test
    void oldRouteOwnerStoreAndBucketWorkerOwnerPointersDoNotRemain() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease")),
                "\":owner\""
        );
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/RouteConsumerEndpoint.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/SelectedWorkerDeliveryTarget.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/TransportRouteOwnerClaim.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/TransportRouteOwnerRecord.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/TransportRouteOwnerStore.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/WorkerDispatchRouteOwner.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/route/WorkerDispatchRouteOwnerView.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DeliveryCommandConsumerProjectingRouteOwnerStore.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/route/InMemoryTransportRouteOwnerStore.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/route/RedisTransportRouteOwnerStore.java")
        );
    }

    @Test
    void deliveryCommandItemDoesNotRegainLaneRouteOrPacketFacts() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/DeliveryCommand.java")),
                "deliveryQueueKey",
                "targetTransportNodeId",
                "connectionToken",
                "connectionId",
                "routeKey",
                "TransportPacket",
                "TaskDispatchItem",
                "Map<String, String>"
        );
    }

    @Test
    void removedAdapterRequestModelDoesNotReappear() throws IOException {
        String removedRequestModel = "AdapterDispatch" + "Request";
        String removedRequestFactory = "from" + "Request(";
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_api/src/test/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/test/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/test/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/test/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/test/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/test/java")
                ),
                removedRequestModel,
                removedRequestFactory
        );
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model")
                        .resolve("AdapterDispatch" + "Request.java")
        );
    }

    @Test
    void adapterCommandExecutorDoesNotMixAdapterMetadata() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/worker/AdapterCommandExecutor.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/worker/WorkerAdapter.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java")
                ),
                "import com.xa.mass.transport.worker.AdapterCommandExecutor",
                "import com.xa.mass.transport.worker.WorkerAdapter",
                "implements WorkerAdapter",
                "getWorkerAdapter(",
                "resolveDispatchAdapter(",
                "resolveCommandExecutorByAdapterId("
        );

        Path binding = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportBinding.java");
        String bindingSource = Files.readString(binding);
        assertTrue(bindingSource.contains("private final String adapterId;"),
                "TransportBinding must own adapter id metadata explicitly");
        assertTrue(bindingSource.contains("private final String transportHint;"),
                "TransportBinding must own transport hint metadata explicitly");
        assertTrue(bindingSource.contains("private final AdapterCommandExecutor commandExecutor;"),
                "TransportBinding must own the command executor separately");
        assertTrue(!bindingSource.contains("adapterId()"),
                "TransportBinding must not read adapter id from the executor");
        assertTrue(!bindingSource.contains("transportHint()"),
                "TransportBinding must not read transport hint from the executor");

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketTaskDispatchChannel.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTaskDispatchChannel.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingWorkerAdapter.java")
                ),
                "public String protocol(",
                "public String adapterId(",
                "public String transportHint(",
                "public static final String DEFAULT_ADAPTER_ID",
                "public static final String PROTOCOL"
        );

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketTransportAdapterBootstrap.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketTransportAdapterBootstrap.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/DefaultWorkerTransportRuntimeFactory.java")
                ),
                ".protocol(commandExecutor.protocol())",
                ".protocol(pollingAdapter.protocol())"
        );

        Path listener = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded/TransportDeliveryCommandListener.java");
        String listenerSource = Files.readString(listener);
        assertTrue(!listenerSource.contains("Map<AdapterCommandExecutor"),
                "Delivery command listener must group by adapter binding identity, not executor instance");
        assertTrue(!listenerSource.contains("putIfAbsent(executor"),
                "Delivery command listener must not store adapter identity by executor instance");
    }

    @Test
    void adapterBootstrapOutputsAreExplicitContributions() throws IOException {
        Path bootstrap = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrap.java");
        String bootstrapSource = Files.readString(bootstrap);
        assertTrue(bootstrapSource.contains("TransportAdapterContribution contribute(TransportAdapterBootstrapContext context)"),
                "Adapter bootstraps must return explicit contribution output");

        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrapContext.java")),
                "registerTransportBinding",
                "registerManagedTransportAdapter",
                "registerTransportServer",
                "registerRawWorkerMessageChannel",
                "private TransportBinding",
                "private ManagedTransportAdapter",
                "private TransportServer",
                "private RawWorkerMessageChannel"
        );
    }

    @Test
    void coreDeliveryAndLeasePackagesDoNotImportEmbeddedAdapterAssembly() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease")
                ),
                "TransportAdapterBootstrap",
                "TransportAdapterContribution",
                "TransportBinding",
                "TransportRuntimeRegistry",
                "ManagedTransportAdapter",
                "CompositeWorkerEndpointRegistry",
                "CompositeWorkerEndpointInspector",
                "RawWorkerMessageChannel",
                "AdapterCommandExecutor"
        );
    }

    @Test
    void pollingAdapterBindingUsesBootstrapContributionPath() throws IOException {
        Path pollingBootstrap = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingTransportAdapterBootstrap.java");
        assertTrue(Files.exists(pollingBootstrap),
                "Polling adapter must have the same bootstrap contribution owner as other embedded adapters");

        Path defaultFactory = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/DefaultWorkerTransportRuntimeFactory.java");
        String factorySource = Files.readString(defaultFactory);
        assertTrue(!factorySource.contains("new PollingWorkerAdapter"),
                "Default runtime factory must not create the polling adapter binding");
        assertTrue(!factorySource.contains("pollingBinding("),
                "Default runtime factory must not keep a polling binding helper");
        assertTrue(!factorySource.contains("registrationDescriptors()"),
                "Default runtime factory must not own polling registration metadata");

        Path composition = repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/TransportRuntimeComposition.java");
        String compositionSource = Files.readString(composition);
        assertTrue(compositionSource.contains("new PollingTransportAdapterBootstrap()"),
                "TransportRuntimeComposition must install the default polling adapter through bootstrap contribution");
    }

    @Test
    void embeddedJavaAdapterAssemblySeamsStayOutOfExternalSurfaces() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-public-contract/src/main/java"),
                        repoRoot().resolve("xa-mass-server/src/main/java"),
                        repoRoot().resolve("integrations")
                ),
                "TransportAdapterBootstrap",
                "TransportBinding",
                "TransportRuntimeRegistry",
                "WorkerTransportRuntimeFactory",
                "AdapterCommandExecutor"
        );

        String facadeSource = Files.readString(repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/MassSdk.java"));
        assertTrue(facadeSource.contains("Advanced embedded Java assembly seam"),
                "MassSdk advanced transport hooks must be documented as embedded Java assembly");

        String builderSource = Files.readString(repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java"));
        assertTrue(builderSource.contains("Advanced embedded Java assembly seam"),
                "MassApplicationBuilder advanced transport hooks must be documented as embedded Java assembly");
    }

    @Test
    void websocketAssignedDeliveryUsesNarrowCommandContext() throws IOException {
        Path taskDispatchChannel = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketTaskDispatchChannel.java");
        String taskDispatchSource = Files.readString(taskDispatchChannel);
        assertTrue(taskDispatchSource.contains("WebSocketCommandDispatchContext"),
                "WebSocket assigned delivery must use the narrow command context");
        assertTrue(!taskDispatchSource.contains("WebSocketDispatcherContext"),
                "WebSocket assigned delivery must not depend on the raw/result dispatcher context");

        Path commandContext = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketCommandDispatchContext.java");
        String commandContextSource = Files.readString(commandContext);
        assertTrue(commandContextSource.contains("WorkerEndpointRegistry"),
                "WebSocket command context may depend on selected-worker endpoint registry");
        assertTrue(!commandContextSource.contains("RawWorkerRouteEndpointRegistry"),
                "WebSocket command context must not depend on raw route registry");
        assertTrue(!commandContextSource.contains("TransportResultIngressChannel"),
                "WebSocket command context must not depend on result ingress");

        Path dispatcherContext = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketDispatcherContext.java");
        String dispatcherContextSource = Files.readString(dispatcherContext);
        assertTrue(!dispatcherContextSource.contains("WorkerEndpointRegistry"),
                "WebSocket raw/result dispatcher context must not own assigned-delivery selected endpoint registry");

        Path sessionManager = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/ServerSessionManager.java");
        String sessionManagerSource = Files.readString(sessionManager);
        assertTrue(!sessionManagerSource.contains("RawWorkerRouteEndpointRegistry"),
                "ServerSessionManager must not implement the raw route side-channel");
        assertTrue(!sessionManagerSource.contains("WorkerEndpointInspector"),
                "ServerSessionManager must not implement diagnostics inspector");
        assertTrue(!sessionManagerSource.contains("listWorkerEndpoints("),
                "ServerSessionManager must not expose diagnostics inspector methods directly");
        Path endpointInspector = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketEndpointInspector.java");
        assertTrue(Files.exists(endpointInspector), "WebSocket diagnostics must live in a dedicated inspector");
    }

    @Test
    void websocketWireSessionDoesNotReintroduceMultiOwnerCodecOrFrameRebinding() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/queue/WebSocketTransportFrameCodec.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/server/MassWebSocketServer.java")
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "WebSocketTransportFrameCodec",
                "encodeCanonicalTaskDispatch",
                "routeKeyForWorkerGroup",
                "MassWebSocketServer",
                "setPrettyPrinting"
        );
        assertSourceSliceDoesNotContain(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/server/DispatcherInboundHandler.java"),
                "protected void channelRead0",
                "public void userEventTriggered",
                "registerSession("
        );
    }

    @Test
    void socketAssignedDeliveryUsesNarrowCommandContext() throws IOException {
        Path taskDispatchChannel = repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTaskDispatchChannel.java");
        String taskDispatchSource = Files.readString(taskDispatchChannel);
        assertTrue(taskDispatchSource.contains("SocketCommandDispatchContext"),
                "Socket assigned delivery must use the narrow command context");
        assertTrue(!taskDispatchSource.contains("SocketSessionManager"),
                "Socket assigned delivery must not depend on the concrete session manager");

        Path commandContext = repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketCommandDispatchContext.java");
        String commandContextSource = Files.readString(commandContext);
        assertTrue(commandContextSource.contains("WorkerEndpointRegistry"),
                "Socket command context may depend on selected-worker endpoint registry");
        assertTrue(!commandContextSource.contains("RawWorkerRouteEndpointRegistry"),
                "Socket command context must not depend on raw route registry");
        assertTrue(!commandContextSource.contains("TransportResultIngressChannel"),
                "Socket command context must not depend on result ingress");

        Path sessionManager = repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java");
        String sessionManagerSource = Files.readString(sessionManager);
        assertTrue(!sessionManagerSource.contains("RawWorkerRouteEndpointRegistry"),
                "SocketSessionManager must not implement the raw route side-channel");
        assertTrue(!sessionManagerSource.contains("WorkerEndpointInspector"),
                "SocketSessionManager must not implement diagnostics inspector");
        assertTrue(!sessionManagerSource.contains("listWorkerEndpoints("),
                "SocketSessionManager must not expose diagnostics inspector methods directly");
        Path endpointInspector = repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketEndpointInspector.java");
        assertTrue(Files.exists(endpointInspector), "Socket diagnostics must live in a dedicated inspector");
    }

    @Test
    void endpointCompositeDoesNotOwnRawRouteOrDiagnosticsRoles() throws IOException {
        Path endpointComposite = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointRegistry.java");
        String endpointCompositeSource = Files.readString(endpointComposite);
        assertTrue(!endpointCompositeSource.contains("RawWorkerRouteEndpointRegistry"),
                "Endpoint registry composite must not own raw route side-channel");
        assertTrue(!endpointCompositeSource.contains("WorkerEndpointInspector"),
                "Endpoint registry composite must not own diagnostics inspector aggregation");
        assertTrue(!endpointCompositeSource.contains("instanceof"),
                "Endpoint registry composite must not discover side roles via instanceof");

        Path inspectorComposite = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointInspector.java");
        String inspectorCompositeSource = Files.readString(inspectorComposite);
        assertTrue(inspectorCompositeSource.contains("implements WorkerEndpointInspector"),
                "Endpoint diagnostics must live in the dedicated inspector composite");

        Path massApplication = repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String massApplicationSource = Files.readString(massApplication);
        assertTrue(!massApplicationSource.contains("endpointRegistry instanceof WorkerEndpointInspector"),
                "MassApplication must not discover endpoint diagnostics through endpoint registry side roles");
    }

    @Test
    void concreteAdaptersDoNotOwnEndpointLeaseOrPresenceProjectionInternals() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/ServerSessionManager.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingWorkerAdapter.java")
                ),
                "TransportEndpointLeaseClaim",
                "TransportEndpointLeaseHeartbeat",
                "TransportEndpointLeaseRelease",
                "TransportEndpointLeaseConsumerEvidence",
                "DeliveryCommandConsumerClaim",
                "WorkerSessionPresenceEvent",
                "endpointLeaseStore.claimEndpointLease",
                "endpointLeaseStore.refreshEndpointLease",
                "endpointLeaseStore.releaseEndpointLease"
        );
        assertTrue(Files.exists(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease/TransportEndpointLeasePublisher.java")),
                "Endpoint lease projection must live in a dedicated publisher");
        assertTrue(Files.exists(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease/WorkerPresenceSessionPublisher.java")),
                "Worker presence projection must live in a dedicated publisher");
    }

    @Test
    void taskDispatchContentAndExecutionContextModelsDoNotReappear() {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TaskDispatchContent.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TaskDispatchExecutionContext.java")
        );
    }

    @Test
    void starterDeliverySubmitterDoesNotBuildPacketBackedCommandsOrFakeRouteFacts() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchDeliveryCommandSubmitter.java")),
                "TransportPacket",
                "TaskDispatchItem",
                "routeKey",
                "deliveryQueueKey",
                "targetTransportNodeId",
                "connectionToken",
                "\"unknown\""
        );
    }

    @Test
    void deliveryCommandListenerDoesNotResolveRouteOwnerEndpointForAssignedDelivery() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded/TransportDeliveryCommandListener.java")),
                "WorkerDispatchRouteOwnerView",
                "endpointForSelectedWorker",
                "RouteConsumerEndpoint",
                "AdapterEndpoint",
                "targetTransportNodeId",
                "connectionId"
        );
    }

    @Test
    void deliveryCommandBatchCodecKeepsCommandRecordMinimal() throws IOException {
        Path codec = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchCodec.java");
        assertNoProductionSourceContains(
                List.of(codec),
                "TransportPacket",
                "TaskDispatchItem",
                "connectionToken",
                "taskName",
                "project",
                "userId"
        );
        assertSourceSliceDoesNotContain(
                codec,
                "private record DeliveryCommandRecord",
                "private static final class DecodedDeliveryCommandBatchRecord",
                "adapterId",
                "deliveryQueueKey",
                "targetTransportNodeId",
                "routeKey",
                "connectionId"
        );
    }

    @Test
    void removedEnvelopeAndEndpointLeaseModelsDoNotReappear() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportDispatchEnvelope.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/TaskDispatchChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/EndpointLease.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/ResolvedDeliveryItem.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DeliveryObservationGroupContext.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DeliveryObservationItemSnapshot.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DeliveryObservationSupport.java")
        );
    }

    @Test
    void redisPollingQueueValueIsTypedPulledDispatchNotPacketEnvelope() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeCodec.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeRecord.java")
        );
        Path codec = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisQueuedPulledDispatchCodec.java");
        assertNoProductionSourceContains(
                List.of(codec),
                "TransportPacket",
                "TaskDispatchItem",
                "transportPayload"
        );
        assertSourceSliceDoesNotContain(
                codec,
                "private record RedisQueuedPulledDispatchRecord",
                "private static final class DecodedRedisQueuedPulledDispatchRecord",
                "routeKey",
                "\"workerId\"",
                "taskName",
                "project",
                "userId",
                "deliveryQueueKey"
        );
    }

    @Test
    void deliveryStoreDoesNotRecoverQueueKeyFromEnvelopeValue() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/InMemoryTransportDeliveryStore.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDeliveryStore.java")),
                "getDeliveryQueueKey("
        );
    }

    @Test
    void deliveryFailureEventCodecDoesNotSerializeFullCommandsOrPacketPayloads() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureEventCodec.java")),
                "DeliveryCommand",
                "TransportPacket",
                "TaskDispatchItem",
                "DeliveryObservation",
                "groupContext",
                "itemSnapshot",
                "connectionToken",
                "adapterId",
                "deliveryQueueKey",
                "routeKey",
                "transportNodeId",
                "connectionId",
                "\"payload\""
        );
    }

    @Test
    void publicWorkerAndOutcomeSurfacesDoNotExposeTransportOwnerIds() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/DispatchOutcome.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerRegistrationResult.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerPresenceResult.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerSpec.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WebSocketWorkerSession.java"),
                        repoRoot().resolve("xa-mass-server/src/main/java/com/xa/mass/api/model/worker/ExternalWorkerRegisterApiRequest.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/DefaultRuntimeDiagnosticsOperations.java")
                ),
                "adapterId",
                "deliveryQueueKey",
                "routeKey",
                "transportNodeId",
                "connectionId"
        );
    }

    @Test
    void workerInvocationSurfacesExposeOnlyOpaqueResultCorrelation() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/DispatchContext.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmitRequest.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerInvocation.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerResultSink.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/PulledTaskDispatch.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/WorkerResultSubmitRequest.java"),
                        repoRoot().resolve("xa-mass-server/src/main/java/com/xa/mass/api/model/worker/ExternalWorkerResultSubmitApiRequest.java")
                ),
                "taskId",
                "messageId",
                "attemptId",
                "attemptNo",
                "retryCount",
                "batchId",
                "workerId",
                "taskName",
                "project",
                "userId",
                "DispatchContext"
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerInvocation.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerResultSink.java")
                ),
                "WorkerDispatchItem"
        );
    }

    @Test
    void workerResultIngressPayloadDoesNotAcceptTaskShapedIdentity() throws IOException {
        Path codec = repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskResultCallbackCodec.java");
        assertNoProductionSourceContains(
                List.of(codec),
                "TASK_ID_FIELD",
                "MESSAGE_ID_FIELD",
                "PayloadRecord",
                "toEnvelope(TaskResultCallbackCommand",
                "readString(payload, \"taskId\")",
                "readString(payload, \"messageId\")"
        );
        String source = Files.readString(codec);
        assertTrue(source.contains("result callback payload requires resultCorrelationRef"),
                "Worker result ingress payload must require opaque resultCorrelationRef");
    }

    @Test
    void sdkFacingPullWorkerSessionDoesNotExposeTransportOwnerIdGetters() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/PullWorkerSession.java")),
                "public String routeKey(",
                "public String connectionId(",
                "public String adapterId("
        );
    }

    @Test
    void publicWorkerWireUsesWorkerGroupIdNotDeliveryBucketId() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WebSocketWorkerSession.java"),
                        repoRoot().resolve("integrations/xa-mass-worker-pack/src/main/java/com/xa/mass/workerpack/sample/client/SampleWorkerWebSocketClient.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/queue/WebSocketTransportFrameCodec.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/protocol/SocketTransportFrameCodec.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/server/DispatcherInboundHandler.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/server/SocketTransportServer.java")
                ),
                "deliveryBucketId",
                "DELIVERY_BUCKET_ID_FIELD",
                "workerId/deliveryBucketId"
        );
    }

    @Test
    void productionCodeDoesNotImportRemovedTaskDispatchItem() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java"),
                        repoRoot().resolve("xa-mass-server/src/main/java"),
                        repoRoot().resolve("xa-mass-testing/src/main/java")
                ),
                "com.xa.mass.transport.model.TaskDispatchItem"
        );
    }

    @Test
    void transportApiDoesNotExposeTaskShapedPullContracts() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/TaskPullChannel.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/TaskPullResult.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/TaskPullStatus.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/PulledTaskDispatch.java")
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_api/src/main/java")),
                "TaskPullChannel",
                "TaskPullResult",
                "TaskPullStatus",
                "PulledTaskDispatch",
                "TaskDispatchContent",
                "TaskDispatchExecutionContext"
        );
    }

    @Test
    void transportResultIngressDoesNotExposeTaskShapedContracts() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/TaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TaskResultReport.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportResultEnvelope.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/BufferedTaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TaskResultIngestInboxPump.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportResultEnvelopeCodec.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java")
                ),
                "TaskResultReport",
                "TransportResultEnvelope",
                "TaskResultIngestChannel",
                "RedisTaskResultIngestChannel",
                "BufferedTaskResultIngestChannel",
                "TaskResultIngestInboxPump"
        );
    }

    @Test
    void transportDeliveryServiceDoesNotExposeWorkerFacingProjectionHelpers() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryService.java")),
                "TaskDispatchItem",
                "PulledTaskDispatch",
                "pollDispatchViews",
                "toDispatchView",
                "toDispatchViews"
        );
    }

    private static void assertNoProductionSourceContains(List<Path> roots, String... forbiddenTokens) throws IOException {
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> violations = files
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> containsAny(path, forbiddenTokens))
                        .toList();
                assertTrue(violations.isEmpty(), () -> "Forbidden transport convergence residue: " + violations);
            }
        }
    }

    private static boolean containsAny(Path path, String[] forbiddenTokens) {
        try {
            String source = Files.readString(path);
            for (String token : forbiddenTokens) {
                if (source.contains(token)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static void assertNoTextFilesContain(List<Path> paths, String... forbiddenTokens) throws IOException {
        List<Path> violations = paths.stream()
                .filter(Files::exists)
                .filter(path -> containsAny(path, forbiddenTokens))
                .toList();
        assertTrue(violations.isEmpty(), () -> "Forbidden transport convergence residue: " + violations);
    }

    private static void assertPathsDoNotExist(Path... paths) {
        List<Path> existing = Stream.of(paths)
                .filter(Files::exists)
                .toList();
        assertTrue(existing.isEmpty(), () -> "Removed transport convergence files still exist: " + existing);
    }

    private static void assertSourceSliceDoesNotContain(Path path,
                                                        String startToken,
                                                        String endToken,
                                                        String... forbiddenTokens) throws IOException {
        String source = Files.readString(path);
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start + startToken.length());
        assertTrue(start >= 0, () -> "Missing source slice start token: " + startToken + " in " + path);
        assertTrue(end > start, () -> "Missing source slice end token: " + endToken + " in " + path);
        String slice = source.substring(start, end);
        for (String token : forbiddenTokens) {
            assertTrue(!slice.contains(token), () -> "Forbidden token '" + token + "' in slice " + startToken + " of " + path);
        }
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null
                && !(Files.exists(current.resolve("pom.xml"))
                && Files.exists(current.resolve("AGENTS.md"))
                && Files.exists(current.resolve("xa-mass-engine")))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        }
        return current;
    }
}
