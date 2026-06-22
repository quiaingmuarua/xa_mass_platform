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
    void pushAssignedDeliveryDoesNotExposeGenericWorkerEndpointRegistry() {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointRegistry.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointRegistry.java")
        );
    }

    @Test
    void deliveryCommandSubmitterDoesNotUseRouteOwnerScansForSelectedWorkerLookup() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchRoutingSubmitter.java")),
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
    void embeddedPullWorkerSessionDoesNotExposeTransportInternalConstructors() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/EmbeddedPullWorkerSession.java")),
                "public EmbeddedPullWorkerSession("
        );
    }

    @Test
    void transportDispatchBatchCodecDoesNotNestTaskBatchJson() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchBatchCodec.java")),
                "taskBatchJson"
        );
    }

    @Test
    void redisTransportDispatchHandoffDoesNotUseRouteOrLaneQueues() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchHandoff.java")),
                "\":route:\"",
                "ready-routes",
                "routeQueueKey(",
                "ready-lanes",
                "targetTransportNodeId",
                "\":lane:\""
        );
    }

    @Test
    void pollingPendingDeliveryBufferLivesInPollingAdapterAndUsesMailboxWorkerSlots() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryService.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryStore.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/InMemoryTransportDeliveryStore.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDeliveryStore.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisDispatchRoutingItemCodec.java")
        );

        Path buffer = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/delivery/PollingPendingDeliveryBuffer.java");
        String bufferSource = Files.readString(buffer);
        assertTrue(bufferSource.contains("enqueue(String adapterMailboxKey"),
                "Polling pending delivery enqueue must be explicitly mailbox-scoped");
        assertTrue(bufferSource.contains("poll(String adapterMailboxKey")
                        && bufferSource.contains("String authenticatedWorkerId"),
                "Polling pending delivery poll must use adapter mailbox plus authenticated polling worker id");

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/delivery")),
                "AssignedDeliveryCommandQueueKey",
                "resolveDeliveryQueueKey(",
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
    void dispatchRoutingItemDoesNotRegainLaneRouteBucketOrPacketFacts() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DispatchRoutingItem.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DispatchRoutingBatch.java")),
                "deliveryBucketId",
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
                "resolveCommandExecutorByAdapterId(",
                "registerCommandExecutor(",
                "resolveCommandExecutor("
        );

        Path binding = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportBinding.java");
        String bindingSource = Files.readString(binding);
        assertTrue(bindingSource.contains("private final String adapterId;"),
                "TransportBinding must own adapter id metadata explicitly");
        assertTrue(bindingSource.contains("private final String transportHint;"),
                "TransportBinding must own transport hint metadata explicitly");
        assertTrue(!bindingSource.contains("AdapterCommandExecutor"),
                "TransportBinding must not own adapter command executors");
        assertTrue(!bindingSource.contains("getCommandExecutor("),
                "TransportBinding must not expose adapter command executors");
        assertTrue(!bindingSource.contains("adapterId()"),
                "TransportBinding must not read adapter id from the executor");
        assertTrue(!bindingSource.contains("transportHint()"),
                "TransportBinding must not read transport hint from the executor");

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketTaskDispatchChannel.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTaskDispatchChannel.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryExecutor.java")
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

        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded/TransportDeliveryCommandListener.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandHandoffPump.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchListener.java")
        );
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
    void concreteAdapterBootstrapsReceiveNarrowRuntimeCapabilities() throws IOException {
        Path bootstrapContext = repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrapContext.java");
        String contextSource = Files.readString(bootstrapContext);
        assertTrue(contextSource.contains("implements AdapterBootstrapCapabilities"),
                "Adapter bootstrap context must be a role-capability surface");
        assertTrue(contextSource.contains("AdapterBootstrapAssignment assignment()"),
                "Adapter bootstrap context must expose host assignment through a narrow capability");
        assertTrue(contextSource.contains("AdapterMailboxCapabilities mailbox()"),
                "Adapter bootstrap context must expose mailbox support through a narrow capability");
        assertTrue(contextSource.contains("AdapterSessionEvidenceCapabilities sessionEvidence()"),
                "Adapter bootstrap context must expose session evidence through a narrow capability");
        assertTrue(!contextSource.contains("getEndpointLeaseStore(")
                        && !contextSource.contains("getWorkerPresenceIngress(")
                        && !contextSource.contains("getDeliveryService(")
                        && !contextSource.contains("pullDeliveryBuffer(")
                        && !contextSource.contains("public TransportResultIngressChannel getResultIngressChannel(")
                        && !contextSource.contains("public RuntimeTaskExecutor getRuntimeTaskExecutor(")
                        && !contextSource.contains("public String adapterMailboxKey(")
                        && !contextSource.contains("public AdapterSessionEvidencePublisher sessionEvidencePublisher(")
                        && !contextSource.contains("public AdapterMailboxConsumer adapterMailboxConsumer("),
                "Adapter bootstrap context must not expose broad transport owner getters to concrete adapters");

        Path pollingBootstrap = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingTransportAdapterBootstrap.java");
        Path websocketBootstrap = repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketTransportAdapterBootstrap.java");
        Path socketBootstrap = repoRoot().resolve(
                "transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketTransportAdapterBootstrap.java");

        String pollingSource = Files.readString(pollingBootstrap);
        assertTrue(pollingSource.contains("context.mailbox().assignedMailboxKey()")
                        && pollingSource.contains("context.sessionEvidence().publisher()")
                        && pollingSource.contains("context.mailbox().consumer(")
                        && pollingSource.contains("PollingPendingDeliveryBuffer")
                        && pollingSource.contains("new PollingDeliveryExecutor")
                        && pollingSource.contains("new PollingDeliveryPullChannel"),
                "Polling bootstrap must consume host-owned mailbox/session capabilities and own its pending pull buffer");
        String websocketSource = Files.readString(websocketBootstrap);
        assertTrue(websocketSource.contains("context.mailbox().assignedMailboxKey()")
                        && websocketSource.contains("context.sessionEvidence().publisher()")
                        && websocketSource.contains("context.mailbox().consumer("),
                "WebSocket bootstrap must consume host-owned mailbox key and session evidence through narrow capabilities");
        String socketSource = Files.readString(socketBootstrap);
        assertTrue(socketSource.contains("context.mailbox().assignedMailboxKey()")
                        && socketSource.contains("context.sessionEvidence().publisher()")
                        && socketSource.contains("context.mailbox().consumer("),
                "Socket bootstrap must consume host-owned mailbox key and session evidence through narrow capabilities");

        assertNoProductionSourceContains(
                List.of(pollingBootstrap, websocketBootstrap, socketBootstrap),
                "getEndpointLeaseStore(",
                "getWorkerPresenceIngress(",
                "getDeliveryService(",
                "TransportEndpointLeaseStore",
                "WorkerPresenceIngress",
                "TransportDeliveryService",
                "context.adapterMailboxKey(",
                "context.sessionEvidencePublisher(",
                "context.adapterMailboxConsumer(",
                "context.getResultIngressChannel(",
                "context.getRuntimeTaskExecutor(",
                "String adapterMailboxKey = config.getAdapterId()",
                "String adapterMailboxKey = metadata.adapterId()"
        );
        assertNoProductionSourceContains(
                List.of(
                        pollingBootstrap,
                        websocketBootstrap,
                        socketBootstrap
                ),
                "AdapterMailboxConsumerRegistry",
                "TransportDispatchHandoff",
                "RedisTransportDispatchHandoff",
                "InMemoryTransportDispatchHandoff",
                "TransportEndpointLeaseStore",
                "WorkerPresenceIngress",
                "TransportDeliveryService",
                "TransportDeliveryStore"
        );
        assertNoProductionSourceContains(
                List.of(websocketBootstrap, socketBootstrap),
                "PollingPendingDeliveryBuffer"
        );

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryExecutor.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryPullChannel.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingSessionEvidenceDriver.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidenceDriver.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java")
                ),
                "TransportEndpointLeaseStore",
                "WorkerPresenceIngress",
                "TransportDeliveryService"
        );
    }

    @Test
    void embeddedAdapterMailboxAvailabilityHasSingleHostOwner() throws IOException {
        Path handoff = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchHandoff.java");
        String handoffSource = Files.readString(handoff);
        assertTrue(handoffSource.contains("poll(String adapterMailboxKey"),
                "Transport dispatch handoff must expose mailbox-scoped poll only");
        assertTrue(handoffSource.contains("int maxItems"),
                "Transport dispatch handoff poll must be bounded by caller-provided maxItems");
        assertTrue(!handoffSource.contains("poll(long timeoutMillis)"),
                "Transport dispatch handoff must not keep an unscoped production poll entry");
        assertTrue(!handoffSource.contains("complete("),
                "Transport dispatch handoff must not keep queue ack/complete for assigned dispatch");

        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java")),
                "TransportDeliveryCommandHandoffPump",
                "TransportDeliveryCommandListener",
                "new AdapterMailboxConsumerAvailability",
                "claimedAdapterMailboxConsumers",
                "claimAdapterMailboxConsumers(",
                "refreshAdapterMailboxConsumer",
                "releaseAdapterMailboxConsumers(",
                "publishMailboxConsumerAvailability("
        );

        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrapContext.java")),
                "getAdapterMailboxConsumerRegistry",
                "publishMailboxConsumerAvailability("
        );

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java")
                ),
                "AdapterMailboxConsumerRegistry",
                "publishMailboxConsumerAvailability("
        );

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/EmbeddedAdapterContributionHost.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/EmbeddedAdapterHostSet.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/MailboxConsumerAvailabilityPublisher.java")
                ),
                "com.xa.mass.transport.websocket",
                "com.xa.mass.transport.socket",
                "com.xa.mass.transport.polling"
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
        assertTrue(compositionSource.contains("new PollingTransportAdapterBootstrap("),
                "TransportRuntimeComposition must install the default polling adapter through bootstrap contribution");
    }

    @Test
    void pollingAdapterCapabilitiesStayRoleSeparated() throws IOException {
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingWorkerAdapter.java"));

        Path executor = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryExecutor.java");
        Path pullChannel = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryPullChannel.java");
        Path evidenceDriver = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingSessionEvidenceDriver.java");
        Path bootstrap = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingTransportAdapterBootstrap.java");
        assertTrue(Files.exists(executor), "Polling command execution must live in an explicit executor");
        assertTrue(Files.exists(pullChannel), "Polling pull demux must live in an explicit pull channel");
        assertTrue(Files.exists(evidenceDriver), "Polling pull-session evidence must live in an explicit driver");

        assertNoProductionSourceContains(
                List.of(executor),
                "DeliveryPullChannel",
                "TransportEndpointLease",
                "DeliveryCommandConsumerRegistry",
                "WorkerPresenceIngress",
                "PullSessionEvidenceDriver"
        );
        assertNoProductionSourceContains(
                List.of(pullChannel),
                "AdapterCommandExecutor",
                "com.xa.mass.transport.model.DeliveryCommand",
                "TransportEndpointLease",
                "DeliveryCommandConsumerRegistry",
                "WorkerPresenceIngress",
                "PullSessionEvidenceDriver"
        );
        assertNoProductionSourceContains(
                List.of(evidenceDriver),
                "com.xa.mass.transport.model.DeliveryCommand",
                "DeliveryPullChannel",
                "TransportDeliveryService",
                "QueuedPulledDispatch"
        );

        String bootstrapSource = Files.readString(bootstrap);
        assertTrue(bootstrapSource.contains("new PollingDeliveryExecutor"),
                "Polling bootstrap must create the command executor explicitly");
        assertTrue(bootstrapSource.contains("new PollingDeliveryPullChannel"),
                "Polling bootstrap must create the pull channel explicitly");
        assertTrue(bootstrapSource.contains("new PollingSessionEvidenceDriver"),
                "Polling bootstrap must create the session evidence driver explicitly");
        assertTrue(!bootstrapSource.contains("deliveryPullChannel(deliveryExecutor)"),
                "Polling bootstrap must not reuse the command executor as the pull channel");
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
    void websocketAssignedDeliveryOwnsFinalHopWithoutEndpointRegistryWrapper() throws IOException {
        Path taskDispatchChannel = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketTaskDispatchChannel.java");
        String taskDispatchSource = Files.readString(taskDispatchChannel);
        assertTrue(taskDispatchSource.contains("WebSocketSessionController"),
                "WebSocket assigned delivery executor must call the adapter-local session controller");
        assertTrue(taskDispatchSource.contains("sendTextToWorker("),
                "WebSocket assigned delivery executor must dispatch by selected worker only");
        assertTrue(!taskDispatchSource.contains("WebSocketSessionStore")
                        && !taskDispatchSource.contains("WebSocketSessionRecord")
                        && !taskDispatchSource.contains("TextWebSocketFrame")
                        && !taskDispatchSource.contains("io.netty"),
                "WebSocket assigned delivery executor must not read session rows or Netty channels directly");
        assertTrue(!taskDispatchSource.contains("WorkerEndpointRegistry"),
                "WebSocket assigned delivery must not route through the generic endpoint-registry wrapper");
        assertTrue(!taskDispatchSource.contains("TransportDeliveryService")
                        && !taskDispatchSource.contains("sendDirect("),
                "WebSocket assigned delivery must not proxy local final-hop sends through TransportDeliveryService");
        assertTrue(!taskDispatchSource.contains("WebSocketCommandDispatchContext"),
                "WebSocket assigned delivery must not reintroduce a command-context wrapper");
        assertTrue(!taskDispatchSource.contains("WebSocketDispatcherContext"),
                "WebSocket assigned delivery must not depend on the raw/result dispatcher context");
        assertTrue(!taskDispatchSource.contains("adapterId"),
                "WebSocket assigned delivery executor must not own adapter id metadata");

        assertPathsDoNotExist(repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketCommandDispatchContext.java"));
        assertPathsDoNotExist(repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSelectedWorkerSender.java"));
        assertPathsDoNotExist(repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSelectedWorkerRegistry.java"));

        String bootstrapSource = Files.readString(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketTransportAdapterBootstrap.java"));
        assertTrue(!bootstrapSource.contains("CompositeWorkerEndpointRegistry")
                        && !bootstrapSource.contains("registerSelectedWorkerRegistry")
                        && !bootstrapSource.contains("getEndpointRegistry()")
                        && !bootstrapSource.contains("WebSocketSelectedWorker"),
                "WebSocket bootstrap must not register a selected-worker endpoint-registry wrapper");

        Path dispatcherContext = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketDispatcherContext.java");
        String dispatcherContextSource = Files.readString(dispatcherContext);
        assertTrue(!dispatcherContextSource.contains("WorkerEndpointRegistry"),
                "WebSocket raw/result dispatcher context must not own assigned-delivery selected endpoint registry");

        assertPathsDoNotExist(repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/ServerSessionManager.java"));
        Path sessionController = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionController.java");
        String sessionControllerSource = Files.readString(sessionController);
        assertTrue(!sessionControllerSource.contains("implements WorkerEndpointRegistry"),
                "WebSocket session controller must not be the assigned-delivery endpoint registry");
        assertTrue(!sessionControllerSource.contains("RawWorkerRouteEndpointRegistry"),
                "WebSocket session controller must not implement the raw route side-channel");
        assertTrue(!sessionControllerSource.contains("WorkerEndpointInspector"),
                "WebSocket session controller must not implement diagnostics inspector");
        assertTrue(!sessionControllerSource.contains("listWorkerEndpoints("),
                "WebSocket session controller must not expose diagnostics inspector methods directly");
        assertTrue(!sessionControllerSource.contains("setEndpointLeaseStore(")
                        && !sessionControllerSource.contains("setDeliveryCommandConsumerRegistry(")
                        && !sessionControllerSource.contains("setWorkerPresenceIngress("),
                "WebSocket session controller must not own endpoint lease or presence wiring setters");
        assertTrue(sessionControllerSource.contains("sendTextToWorker(")
                        && sessionControllerSource.contains("TextWebSocketFrame"),
                "WebSocket session controller owns the adapter-local selected-worker final-hop send");
        String serverSessionHandleSource = Files.readString(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketServerSessionHandle.java"));
        assertTrue(serverSessionHandleSource.contains("String currentWorkerId(Channel channel)"),
                "WebSocket server session handle should expose only bounded current-worker lookup");
        assertTrue(!serverSessionHandleSource.contains("getWorkerId(")
                        && !serverSessionHandleSource.contains("getEndpointAddress(")
                        && !serverSessionHandleSource.contains("getDeliveryBucketId(")
                        && !serverSessionHandleSource.contains("getChannelContext(")
                        && !serverSessionHandleSource.contains("ChannelHandlerContext"),
                "WebSocket server session handle must not expose route/bucket/channel-context index getters");
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketServerSession.java"));
        String sessionStoreSource = Files.readString(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionStore.java"));
        assertTrue(!sessionStoreSource.contains("sendToSelectedWorker(")
                        && !sessionStoreSource.contains("TextWebSocketFrame")
                        && !sessionStoreSource.contains("writeAndFlush("),
                "WebSocket session store must remain an index/state owner, not a send behavior owner");
        assertTrue(!sessionStoreSource.contains("RouteEndpointIndex"),
                "WebSocket session store must keep direct worker/channel/endpoint indexes instead of a route-oriented wrapper");
        assertTrue(!sessionStoreSource.contains("ChannelHandlerContext"),
                "WebSocket session store must not retain unused Netty handler context");
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionRecord.java"));
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidence.java"));
        assertTrue(sessionStoreSource.contains("record SessionSnapshot(")
                        && !sessionStoreSource.contains("WebSocketSessionEvidence"),
                "WebSocket session evidence should stay a store-internal snapshot, not a top-level adapter model");
        String sessionEvidenceDriverSource = Files.readString(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidenceDriver.java"));
        assertTrue(!sessionEvidenceDriverSource.contains("Channel")
                        && !sessionEvidenceDriverSource.contains("TextWebSocketFrame"),
                "WebSocket session evidence driver must consume narrow evidence, not Netty channel/session rows");
        String inboundMessageSource = Files.readString(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketInboundMessage.java"));
        assertTrue(!inboundMessageSource.contains("routeKey")
                        && !inboundMessageSource.contains("endpointAddress")
                        && !inboundMessageSource.contains("deliveryBucketId"),
                "WebSocket inbound message must not inherit endpoint address, route, or delivery bucket metadata from the session");
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointRegistry.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointRegistry.java")
        );
        assertTrue(!taskDispatchSource.contains("sendToSelectedWorker(\n                            adapterId()")
                        && !taskDispatchSource.contains("sendToSelectedWorker(\r\n                            adapterId()"),
                "WebSocket assigned delivery must not pass adapterId into selected-worker endpoint send");
        String socketDispatchSource = Files.readString(repoRoot().resolve(
                "transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTaskDispatchChannel.java"));
        assertTrue(!socketDispatchSource.contains("sendToSelectedWorker(\n                            adapterId()")
                        && !socketDispatchSource.contains("sendToSelectedWorker(\r\n                            adapterId()"),
                "Socket assigned delivery must not pass adapterId into selected-worker endpoint send");
        Path endpointInspector = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketEndpointInspector.java");
        assertTrue(Files.exists(endpointInspector), "WebSocket diagnostics must live in a dedicated inspector");
        Path serverFactoryContext = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketServerFactoryContext.java");
        String serverFactoryContextSource = Files.readString(serverFactoryContext);
        assertTrue(!serverFactoryContextSource.contains("getEndpointRegistry("),
                "WebSocket custom server factory context must not expose assigned-delivery endpoint registry");
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
        assertTrue(taskDispatchSource.contains("SocketSessionManager"),
                "Socket assigned delivery executor must use the adapter-local session owner for final-hop sends");
        assertTrue(taskDispatchSource.contains("sendToWorker("),
                "Socket assigned delivery executor must dispatch by selected worker only");
        assertTrue(taskDispatchSource.contains("SocketTransportFrameCodec"),
                "Socket assigned delivery executor must own frame encoding at the concrete adapter boundary");
        assertTrue(!taskDispatchSource.contains("SocketCommandDispatchContext"),
                "Socket assigned delivery must not reintroduce a command-context wrapper");
        assertTrue(!taskDispatchSource.contains("WorkerEndpointRegistry"),
                "Socket assigned delivery must not route through the generic endpoint-registry wrapper");
        assertTrue(!taskDispatchSource.contains("TransportDeliveryService")
                        && !taskDispatchSource.contains("sendDirect("),
                "Socket assigned delivery must not proxy local final-hop sends through TransportDeliveryService");

        assertPathsDoNotExist(repoRoot().resolve(
                "transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketCommandDispatchContext.java"));

        Path sessionManager = repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java");
        String sessionManagerSource = Files.readString(sessionManager);
        assertTrue(!sessionManagerSource.contains("implements WorkerEndpointRegistry"),
                "SocketSessionManager must not implement the generic endpoint registry");
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
        assertPathsDoNotExist(endpointComposite);

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
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionController.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidenceDriver.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java")
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
    void embeddedPullWorkerSessionUsesEvidenceDriverInsteadOfTransportEvidenceInternals() throws IOException {
        Path embeddedPullWorkerSession = repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/EmbeddedPullWorkerSession.java");
        assertNoProductionSourceContains(
                List.of(embeddedPullWorkerSession),
                "TransportEndpointLeaseStore",
                "DeliveryCommandConsumerRegistry",
                "WorkerPresenceIngress",
                "WorkerSessionPresenceEvent",
                "TransportEndpointLeaseClaim",
                "TransportEndpointLeaseHeartbeat",
                "TransportEndpointLeaseRelease",
                "DeliveryCommandConsumerClaim",
                "claimEndpointLease",
                "refreshEndpointLease",
                "releaseEndpointLease"
        );
        String source = Files.readString(embeddedPullWorkerSession);
        assertTrue(source.contains("PullSessionEvidenceDriver"),
                "EmbeddedPullWorkerSession must consume the runtime-resolved pull-session evidence driver");
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
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchRoutingSubmitter.java")),
                "TransportPacket",
                "TaskDispatchItem",
                "TransportEndpointLease",
                "RouteOwner",
                "adapterId",
                "routeKey",
                "deliveryQueueKey",
                "targetTransportNodeId",
                "transportNodeId",
                "connectionId",
                "sessionToken",
                "connectionToken",
                "\"unknown\""
        );
    }

    @Test
    void selectedWorkerMailboxEvidenceMainlineStaysPointLookupOnly() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerDeliveryTargetView.java"),
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/SelectedWorkerDeliveryTargetEvidence.java")
                ),
                "List<",
                "Map<",
                "list",
                "stats",
                "snapshot",
                "count",
                "inspect"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("xa-mass-base/src/main/java/com/xa/mass/base/runtime/dispatch/TaskDispatchBinding.java")),
                "adapterMailboxKey"
        );
        assertNoTextFilesContain(
                List.of(repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/presence/InMemoryWorkerPresenceRuntime.java")),
                "public synchronized int activeSessionCount",
                "public int activeSessionCount"
        );
    }

    @Test
    void globalDeliveryCommandListenerAndPumpDoNotReappear() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded/TransportDeliveryCommandListener.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandHandoffPump.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryCommandBatchListener.java")
        );
    }

    @Test
    void transportDispatchBatchCodecKeepsRecordMinimal() throws IOException {
        Path codec = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchBatchCodec.java");
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
                "private record DispatchRoutingItemRecord",
                "private static final class DecodedDispatchRoutingBatchRecord",
                "adapterId",
                "deliveryQueueKey",
                "deliveryBucketId",
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
    void pollingPendingDeliveryValueIsFlatDispatchItemNotPacketEnvelope() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeCodec.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeRecord.java")
        );
        Path codec = repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/delivery/PollingDispatchRoutingItemCodec.java");
        assertNoProductionSourceContains(
                List.of(codec),
                "TransportPacket",
                "TaskDispatchItem",
                "transportPayload"
        );
        assertSourceSliceDoesNotContain(
                codec,
                "private record RedisDispatchRoutingItemRecord",
                "private static final class DecodedRedisDispatchRoutingItemRecord",
                "routeKey",
                "\"workerId\"",
                "taskName",
                "project",
                "userId",
                "deliveryQueueKey",
                "deliveryBucketId"
        );
    }

    @Test
    void pollingPendingDeliveryBufferDoesNotRecoverQueueKeyFromEnvelopeValue() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/delivery/InMemoryPollingPendingDeliveryBuffer.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/delivery/RedisPollingPendingDeliveryBuffer.java")),
                "getDeliveryQueueKey(",
                "AssignedDeliveryCommandQueueKey",
                "deliveryBucketId"
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
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/DispatchContext.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerInvocation.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerDispatchHandler.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerResultSink.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerInvocation.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmitRequest.java"),
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
    void sdkFacingEmbeddedPullWorkerSessionDoesNotExposeTransportOwnerIdGetters() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/EmbeddedPullWorkerSession.java")),
                "public String routeKey(",
                "public String connectionId(",
                "public String adapterId(",
                "diagnostic(\"routeKey\"",
                "diagnostic(\"adapterId\"",
                "\"routeKey\"",
                "\"adapterId\""
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
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportResultIngressEnvelope.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportResultEnvelope.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/BufferedTaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TaskResultIngestInboxPump.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportResultEnvelopeCodec.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportResultIngressEnvelopeCodec.java")
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
                "TransportResultIngressEnvelope",
                "TransportResultEnvelope",
                "TaskResultIngestChannel",
                "RedisTaskResultIngestChannel",
                "BufferedTaskResultIngestChannel",
                "TaskResultIngestInboxPump"
        );
    }

    @Test
    void resultIngressMainlineUsesRoutingEnvelopeAndAdaptersDoNotParseResultSemantics() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java"),
                        repoRoot().resolve("xa-mass-server/src/main/java")
                ),
                "TransportResultIngressEnvelope"
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketResultIngressFrameReader.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/protocol/SocketTransportFrameCodec.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/server/SocketTransportServer.java")
                ),
                "PAYLOAD_SUCCESS",
                "SUCCESS_FIELD",
                "readBoolean(",
                "\"success\""
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/BufferedTransportResultIngressChannel.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTransportResultIngressChannel.java")
                ),
                "pendingCount(",
                "queuedResults("
        );
        String targetSource = Files.readString(repoRoot().resolve(
                "transport/transport_api/src/main/java/com/xa/mass/transport/routing/RoutingTarget.java"));
        assertTrue(targetSource.contains("resultIngress"),
                "Result ingress must use a named RoutingTarget factory instead of engine target");
    }

    @Test
    void transportRuntimeDoesNotExposeGenericPollingDeliveryStoreBoundary() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryService.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryStore.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryStoreStats.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryServiceStats.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java"),
                        repoRoot().resolve("xa-mass-server/src/main/java")
                ),
                "TransportDeliveryStore",
                "TransportDeliveryService",
                "deliveryStoreFactory",
                "redisDeliveryStore",
                "maxDeliveryQueuedItems",
                "maxDeliveryItemsPerRoute",
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
