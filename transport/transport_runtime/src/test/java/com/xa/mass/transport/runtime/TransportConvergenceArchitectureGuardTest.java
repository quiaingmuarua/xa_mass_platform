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
    void negativeWorkerDispatchSignalBridgeStaysInStarterAssembly() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java")
                ),
                "WorkerDispatchBlockRuntime",
                "WorkerDispatchBlockSignal",
                "WorkerDispatchGateRuntime",
                "blockWorkerDispatch(",
                "clearWorkerDispatchDisable(",
                "disableWorkerDispatch("
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java")
                ),
                "WorkerDispatchGateRuntime",
                "clearWorkerDispatchDisable(",
                "disableWorkerDispatch("
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java")
                ),
                "WorkerHeartbeatRuntime",
                "refreshWorkerHeartbeat(",
                "refreshSlotHeartbeat"
        );
        assertPathsDoNotExist(
                repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/presence/WorkerPresenceRuntime.java"),
                repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerReachabilityView.java"),
                repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerDeliveryTargetView.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/presence/InMemoryWorkerPresenceRuntime.java")
                ),
                "setDispatchWakeupCallback",
                "dispatchWakeupCallback",
                "notifyDispatchWakeup("
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassEngine.java")),
                "getWorkerPresenceRuntime().setDispatchWakeupCallback"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/EngineConfig.java")),
                "public InMemoryWorkerPresenceRuntime getWorkerPresenceRuntime",
                "getWorkerPresenceRuntime()"
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
        String removedPayloadWorkerIdAccessor = "payloadString(Transport" + "Packet.PAYLOAD_WORKER_ID)";
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterRuntimeFactory.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketAdapterRuntimeFactory.java")
                ),
                removedPayloadWorkerIdAccessor,
                "sendToAdapterRoute("
        );
    }

    @Test
    void websocketAdapterDependencySurfaceDoesNotReverseDependOnSdkBaseOrStaleClients() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "com.xa.mass.sdk",
                "com.xa.mass.base"
        );
        assertNoTextFilesContain(
                List.of(repoRoot().resolve("transport/websocket-adapter/pom.xml")),
                "xa-mass-embedded-sdk-api",
                "xa-mass-base",
                "Java-WebSocket",
                "lettuce-core"
        );
    }

    @Test
    void pushAdaptersUseRuntimeJsonFrameParser() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/frame/TransportJsonFrameParser.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketJsonFrameParser.java")
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "WebSocketJsonFrameParser"
        );
    }

    @Test
    void workerChannelFrameHasSinglePublicContractOwner() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerChannelFrame.java"),
                repoRoot().resolve("sdk/xa-mass-public-contract/src/main/java/com/xa/mass/contract/worker/WorkerChannelActionReplyFrame.java"),
                repoRoot().resolve("sdk/xa-mass-public-contract/src/main/java/com/xa/mass/contract/worker/WorkerChannelActionReplyReader.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketWorkerChannelFrameCodec.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketWorkerChannelFrameJsonCodec.java")
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "public static final String ACTION",
                "public static final String ACTION_REPLY",
                "public static final String EVIDENCE_REPORT",
                "public static final String HEARTBEAT"
        );
    }

    @Test
    void pushAdaptersUseSharedFinalHopAndResultIngressCarrierHelpers() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketResultIngressFrameReader.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketInputProcessor.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketDispatcherContext.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketInboundFrameSink.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterRuntimeFactory.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketAdapterRuntimeFactory.java")
                ),
                "DispatchOutcomeFactory",
                "DispatchOutcome.delivered(",
                "DispatchOutcome.noEndpoint(",
                "DispatchOutcome.failed("
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/server/SocketTransportServer.java")
                ),
                "new ResultIngressEntry",
                "new ResultIngressMessage",
                "new ResultIngressDiagnostics"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "new ResultIngressEntry",
                "new ResultIngressMessage",
                "new ResultIngressDiagnostics",
                "AdapterResultIngressEntries.from("
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "WorkerChannelFrame.ACTION_REPLY"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded")),
                "com.xa.mass.transport.websocket",
                "io.netty"
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
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointInspector.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointSnapshot.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointRegistry.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointInspector.java")
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
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisDispatchMessageCodec.java")
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
    void DispatchMessageDoesNotRegainLaneRouteBucketOrPacketFacts() throws IOException {
        String removedPacketModel = "Transport" + "Packet";
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/DispatchMessage.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/AdapterMailboxDispatchBatch.java")),
                "deliveryBucketId",
                "deliveryQueueKey",
                "targetTransportNodeId",
                "connectionToken",
                "connectionId",
                "routeKey",
                removedPacketModel,
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
    void removedLegacyPacketModelsDoNotReappear() throws IOException {
        String removedPacketModel = "Transport" + "Packet";
        String removedTypeModel = "Packet" + "Type";
        String removedPacketCodec = "Transport" + "PacketCodec";
        String removedJsonPacketCodec = "JsonTransport" + "PacketCodec";
        String removedPacketFactory = "Transport" + "PacketFactory";
        String removedInboundModel = "Inbound" + "Envelope";
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
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/test/java"),
                        repoRoot().resolve("xa-mass-server/src/main/java"),
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java")
                ),
                removedPacketModel,
                removedTypeModel,
                removedPacketCodec,
                removedJsonPacketCodec,
                removedPacketFactory,
                removedInboundModel
        );
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/packet")
                        .resolve("Transport" + "Packet.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/packet")
                        .resolve("Packet" + "Type.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/packet")
                        .resolve("Transport" + "PacketCodec.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/packet")
                        .resolve("JsonTransport" + "PacketCodec.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model")
                        .resolve("Inbound" + "Envelope.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/packet")
                        .resolve("Transport" + "PacketFactory.java")
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
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterRuntimeFactory.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketAdapterRuntimeFactory.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingAdapterRuntimeFactory.java")
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
    void embeddedAdapterStarterReplacesBootstrapContributionModel() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrap.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterBootstrapContext.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportAdapterContribution.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/EmbeddedAdapterContributionHost.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/EmbeddedAdapterHostSet.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/WorkerTransportRuntimeFactory.java"),
                repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingTransportAdapterBootstrap.java"),
                repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/DefaultWorkerTransportRuntimeFactory.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketTransportAdapterBootstrap.java"),
                repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketTransportAdapterBootstrap.java")
        );

        Path starter = repoRoot().resolve(
                "transport/adapter-starter/src/main/java/com/xa/mass/transport/starter/EmbeddedAdapterStarter.java");
        String starterSource = Files.readString(starter);
        assertTrue(starterSource.contains("EmbeddedAdapterCreateResult create(List<EmbeddedAdapterDeclaration> declarations)"),
                "Embedded adapter starter must expose declaration-only creation with a minimal result");
        assertTrue(starterSource.contains("cross-module adapter declarations stay")
                        && starterSource.contains("EmbeddedAdapterDeclaration"),
                "Embedded adapter starter must keep runtime specs internal to adapter-starter");
        assertTrue(starterSource.contains("start(String adapterId)")
                        && starterSource.contains("close(String adapterId)")
                        && starterSource.contains("runtimeByAdapterId"),
                "Embedded adapter starter must own adapter-id lifecycle over its internal runtime registry");
        assertTrue(!starterSource.contains("TransportAdapterContribution")
                        && !starterSource.contains("TransportAdapterBootstrapContext")
                        && !starterSource.contains("EmbeddedAdapterRuntimeSet"),
                "Embedded adapter starter must not reintroduce contribution baskets or runtime set snapshots");
    }

    @Test
    void rawWorkerSideChannelIsRemovedFromMainline() throws IOException {
        String rawChannel = "RawWorker" + "MessageChannel";
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/" + rawChannel + ".java"));

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java"),
                        repoRoot().resolve("xa-mass-server/src/main/java"),
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java")
                ),
                rawChannel,
                "addRawWorker" + "MessageChannel",
                "getRawWorker" + "MessageChannels",
                "sendRawTransport" + "Message",
                "enqueueRaw" + "Message",
                "rawWorker" + "MessageChannels"
        );
    }

    @Test
    void concreteAdapterRuntimeFactoriesConsumeSpecAndKeyedQueueEnvironment() throws IOException {
        Path pollingFactory = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingAdapterRuntimeFactory.java");
        Path websocketFactory = repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterRuntimeFactory.java");
        Path socketFactory = repoRoot().resolve(
                "transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketAdapterRuntimeFactory.java");

        String pollingSource = Files.readString(pollingFactory);
        assertTrue(pollingSource.contains("spec.dispatchQueueKey()")
                        && pollingSource.contains("environment.dispatchQueue()")
                        && pollingSource.contains("new AdapterDispatchQueueConsumerLoop")
                        && pollingSource.contains("new PollingDeliveryExecutor")
                        && pollingSource.contains("new PollingDeliveryPullChannel"),
                "Polling runtime factory must consume explicit queue keys and own polling pull-buffer parts");
        String websocketSource = Files.readString(websocketFactory);
        assertTrue(websocketSource.contains("spec.dispatchQueueKey()")
                        && websocketSource.contains("spec.resultQueueKey()")
                        && websocketSource.contains("environment.dispatchQueue()")
                        && websocketSource.contains("environment.resultQueue()")
                        && websocketSource.contains("new AdapterDispatchQueueConsumerLoop"),
                "WebSocket runtime factory must consume explicit queue keys and shared queue ports");
        String socketSource = Files.readString(socketFactory);
        assertTrue(socketSource.contains("spec.dispatchQueueKey()")
                        && socketSource.contains("spec.resultQueueKey()")
                        && socketSource.contains("environment.dispatchQueue()")
                        && socketSource.contains("environment.resultQueue()")
                        && socketSource.contains("new AdapterDispatchQueueConsumerLoop"),
                "Socket runtime factory must consume explicit queue keys and shared queue ports");

        assertNoProductionSourceContains(
                List.of(pollingFactory, websocketFactory, socketFactory),
                "TransportAdapterBootstrapContext",
                "TransportAdapterContribution",
                "AdapterMailboxCapabilities",
                "AdapterIngressCapabilities",
                "AdapterSessionEvidenceCapabilities",
                "AdapterMailboxClient",
                "AdapterMailboxConsumerRegistry",
                "RedisTransportDispatchHandoff",
                "InMemoryTransportDispatchHandoff",
                "TransportDeliveryService",
                "TransportDeliveryStore",
                "String adapterMailboxKey = config.getAdapterId()",
                "String adapterMailboxKey = metadata.adapterId()"
        );
        assertNoProductionSourceContains(
                List.of(websocketFactory, socketFactory),
                "PollingPendingDeliveryBuffer"
        );

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryExecutor.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/worker/PollingDeliveryPullChannel.java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingSessionEvidenceDriver.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionRegistry.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidenceRefresher.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java")
                ),
                "TransportEndpointLeaseStore",
                "WorkerPresence" + "Ingress",
                "TransportDeliveryService"
        );
    }

    @Test
    void embeddedAdapterMailboxAvailabilityHasSingleHostOwner() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchHandoff.java")
        );
        Path dispatchQueue = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchQueue.java");
        String dispatchQueueSource = Files.readString(dispatchQueue);
        assertTrue(dispatchQueueSource.contains("poll(String dispatchQueueKey"),
                "Transport dispatch queue must expose scoped poll only");
        assertTrue(dispatchQueueSource.contains("int maxItems"),
                "Transport dispatch queue poll must be bounded by caller-provided maxItems");
        assertTrue(!dispatchQueueSource.contains("poll(long timeoutMillis)"),
                "Transport dispatch queue must not keep an unscoped production poll entry");
        assertTrue(!dispatchQueueSource.contains("complete("),
                "Transport dispatch queue must not keep queue ack/complete for assigned dispatch");

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
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/adapter-starter/src/main/java")
                ),
                "getAdapterMailboxConsumerRegistry",
                "publishMailboxConsumerAvailability(",
                "AdapterMailboxConsumerAvailability",
                "MailboxConsumerAvailabilityPublisher"
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
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded")
                ),
                "com.xa.mass.transport.websocket",
                "com.xa.mass.transport.socket",
                "com.xa.mass.transport.polling"
        );

        Path consumerLoop = repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded/AdapterDispatchQueueConsumerLoop.java");
        String consumerLoopSource = Files.readString(consumerLoop);
        assertTrue(consumerLoopSource.contains("dispatchQueue.poll")
                        && consumerLoopSource.contains("dispatchQueueKey"),
                "Adapter runtime consumer loop must be the mailbox queue consumer");
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
                "RawWorker" + "MessageChannel",
                "AdapterCommandExecutor"
        );
    }

    @Test
    void pollingAdapterBindingUsesEmbeddedRuntimeFactoryPath() throws IOException {
        Path pollingFactory = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingAdapterRuntimeFactory.java");
        String factorySource = Files.readString(pollingFactory);
        assertTrue(factorySource.contains("implements EmbeddedTransportAdapterRuntimeFactory"),
                "Polling adapter must be exposed as an embedded adapter runtime factory");
        assertTrue(factorySource.contains("new CompositeEmbeddedTransportAdapterRuntime")
                        && factorySource.contains("TransportBinding.builder")
                        && factorySource.contains(".deliveryPullChannel(pullChannel)")
                        && factorySource.contains(".pullSessionEvidenceDriver(sessionEvidenceDriver)"),
                "Polling runtime factory must create binding and polling runtime capabilities");
        assertTrue(!factorySource.contains("new PollingWorkerAdapter"),
                "Polling runtime factory must not resurrect a polling worker adapter facade");
        assertTrue(!factorySource.contains("registrationDescriptors()"),
                "Polling runtime factory must not own standalone registration metadata");

        assertPathsDoNotExist(
                repoRoot().resolve(
                        "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/EmbeddedAdapterSpecAssembler.java"),
                repoRoot().resolve(
                        "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/TransportRuntimeComposition.java")
        );

        String transportConfigSource = Files.readString(repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/config/TransportConfig.java"));
        assertTrue(!transportConfigSource.contains("EmbeddedAdapterRuntimeSpec")
                        && !transportConfigSource.contains("new PollingAdapterRuntimeFactory(")
                        && !transportConfigSource.contains("new WebSocketAdapterRuntimeFactory(")
                        && !transportConfigSource.contains("new SocketAdapterRuntimeFactory(")
                        && !transportConfigSource.contains("transportHintForType")
                        && !transportConfigSource.contains("TransportRegistrationResolver")
                        && !transportConfigSource.contains("runtimeOwnedEndpointLeaseStore")
                        && !transportConfigSource.contains("WebSocketAdapterConfig")
                        && !transportConfigSource.contains("SocketAdapterConfig")
                        && !transportConfigSource.contains("WebSocketServerFactoryContext"),
                "TransportConfig may accumulate embedded SDK declarations, but runtime specs, factories, and registration resolution must stay in adapter-starter");

        String starterDefaultsSource = Files.readString(repoRoot().resolve(
                "transport/adapter-starter/src/main/java/com/xa/mass/transport/starter/EmbeddedAdapterStarterDefaults.java"));
        assertTrue(starterDefaultsSource.contains("new PollingAdapterRuntimeFactory(")
                        && starterDefaultsSource.contains("new WebSocketAdapterRuntimeFactory(")
                        && starterDefaultsSource.contains("new SocketAdapterRuntimeFactory(")
                        && starterDefaultsSource.contains("createRegistry(")
                        && !starterDefaultsSource.contains("transportHintForType"),
                "Adapter-starter defaults must own the bundled concrete adapter factory list");

        String registrySource = Files.readString(repoRoot().resolve(
                "transport/adapter-starter/src/main/java/com/xa/mass/transport/starter/EmbeddedAdapterRuntimeFactoryRegistry.java"));
        assertTrue(registrySource.contains("registrationResolverFromDeclarations(")
                        && registrySource.contains("toRuntimeSpec(")
                        && !registrySource.contains("ServiceLoader")
                        && !registrySource.contains("Class.forName"),
                "Adapter-starter registry must translate declarations to runtime specs and provide descriptor-only resolver without dynamic discovery");
        String assemblySource = Files.readString(repoRoot().resolve(
                "transport/adapter-starter/src/main/java/com/xa/mass/transport/starter/EmbeddedTransportAssembly.java"));
        assertTrue(assemblySource.contains("AssignedDeliverySink")
                        && assemblySource.contains("ResultIngressSource")
                        && assemblySource.contains("PullSessionEvidencePort")
                        && assemblySource.contains("evidencePort(PullSessionEvidenceDriver"),
                "Adapter-starter assembly must expose narrow transport ports instead of leaking runtime stores and adapter modules to embedded SDK");
        String assemblyConfigSource = Files.readString(repoRoot().resolve(
                "transport/adapter-starter/src/main/java/com/xa/mass/transport/starter/EmbeddedTransportAssemblyConfig.java"));
        assertTrue(assemblyConfigSource.contains("CurrentSessionDisconnectHandler"),
                "Adapter-starter assembly config must expose current-session disconnect through a narrow handler port");
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/adapter-starter/src/main/java")),
                "com.xa.mass.starter.config"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java")),
                "com.xa.mass.transport.runtime",
                "com.xa.mass.transport.polling",
                "com.xa.mass.transport.socket",
                "com.xa.mass.transport.websocket",
                "PollingPendingDeliveryBuffer",
                "TransportEndpointLeaseStore",
                "WebSocketServerFactoryContext",
                "import com.xa.mass.transport.websocket",
                "import com.xa.mass.transport.socket"
        );
        String embeddedSdkPom = Files.readString(repoRoot().resolve("sdk/xa-mass-embedded-sdk/pom.xml"));
        assertTrue(!embeddedSdkPom.contains("xa-mass-transport-runtime")
                        && !embeddedSdkPom.contains("xa-mass-transport-polling")
                        && !embeddedSdkPom.contains("xa-mass-transport-socket")
                        && !embeddedSdkPom.contains("xa-mass-transport-websocket"),
                "Embedded SDK must depend on adapter-starter contracts, not concrete transport runtime/adapter artifacts");
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
        Path factory = repoRoot().resolve(
                "transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/runtime/PollingAdapterRuntimeFactory.java");
        assertTrue(Files.exists(executor), "Polling command execution must live in an explicit executor");
        assertTrue(Files.exists(pullChannel), "Polling pull demux must live in an explicit pull channel");
        assertTrue(Files.exists(evidenceDriver), "Polling pull-session evidence must live in an explicit driver");

        assertNoProductionSourceContains(
                List.of(executor),
                "DeliveryPullChannel",
                "TransportEndpointLease",
                "DeliveryCommandConsumerRegistry",
                "WorkerPresence" + "Ingress",
                "PullSessionEvidenceDriver"
        );
        assertNoProductionSourceContains(
                List.of(pullChannel),
                "AdapterCommandExecutor",
                "com.xa.mass.transport.model.DeliveryCommand",
                "TransportEndpointLease",
                "DeliveryCommandConsumerRegistry",
                "WorkerPresence" + "Ingress",
                "PullSessionEvidenceDriver"
        );
        assertNoProductionSourceContains(
                List.of(evidenceDriver),
                "com.xa.mass.transport.model.DeliveryCommand",
                "DeliveryPullChannel",
                "TransportDeliveryService",
                "QueuedPulledDispatch"
        );

        String factorySource = Files.readString(factory);
        assertTrue(factorySource.contains("new PollingDeliveryExecutor"),
                "Polling runtime factory must create the command executor explicitly");
        assertTrue(factorySource.contains("new PollingDeliveryPullChannel"),
                "Polling runtime factory must create the pull channel explicitly");
        assertTrue(factorySource.contains("new PollingSessionEvidenceDriver"),
                "Polling runtime factory must create the session evidence driver explicitly");
        assertTrue(!factorySource.contains("deliveryPullChannel(deliveryExecutor)"),
                "Polling runtime factory must not reuse the command executor as the pull channel");
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
        String builderSource = Files.readString(repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/builder/MassApplicationBuilder.java"));
        assertTrue(!facadeSource.contains("workerTransportRuntimeFactory(")
                        && !facadeSource.contains("addSupplementalTransportAdapterBootstrap(")
                        && !builderSource.contains("workerTransportRuntimeFactory(")
                        && !builderSource.contains("addSupplementalTransportAdapterBootstrap(")
                        && !builderSource.contains("adapterMailboxConsumerAvailabilityMillis("),
                "Embedded SDK public facade/builder must not expose legacy adapter bootstrap/factory hooks");
    }

    @Test
    void websocketAssignedDeliveryOwnsFinalHopWithoutEndpointRegistryWrapper() throws IOException {
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocket" + "TaskDispatchChannel.java"));

        Path runtimeFactory = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterRuntimeFactory.java");
        String runtimeFactorySource = Files.readString(runtimeFactory);
        assertTrue(runtimeFactorySource.contains("AdapterCommandExecutors.perMessage(\"WebSocket\""),
                "WebSocket runtime factory must let runtime embedded support own per-message outcome normalization");
        assertTrue(runtimeFactorySource.contains("sendTextToWorker("),
                "WebSocket assigned delivery executor must dispatch by selected worker only");
        assertTrue(runtimeFactorySource.contains("encodeAction(item.payload())"),
                "WebSocket assigned delivery must frame the opaque payload as a worker ACTION frame");
        assertSourceSliceDoesNotContain(
                runtimeFactory,
                "static AdapterCommandExecutor webSocketCommandExecutor",
                "private TransportServer createTransportServer",
                "WebSocketSessionStore",
                "WebSocketSessionRecord",
                "TextWebSocketFrame",
                "io.netty",
                "WorkerEndpointRegistry",
                "TransportDeliveryService",
                "sendDirect(",
                "WebSocketCommandDispatchContext",
                "WebSocketDispatcherContext",
                "dispatchQueueKey",
                "resultQueueKey",
                "adapterId"
        );

        assertPathsDoNotExist(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketCommandDispatchContext.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketDispatcherContext.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketInputProcessor.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketInboundFrameSink.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketResultIngressFrameReader.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSelectedWorkerSender.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSelectedWorkerRegistry.java"));

        assertTrue(!runtimeFactorySource.contains("CompositeWorkerEndpointRegistry")
                        && !runtimeFactorySource.contains("registerSelectedWorkerRegistry")
                        && !runtimeFactorySource.contains("getEndpointRegistry()")
                        && !runtimeFactorySource.contains("WebSocketSelectedWorker"),
                "WebSocket runtime factory must not register a selected-worker endpoint-registry wrapper");

        assertPathsDoNotExist(repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/ServerSessionManager.java"));
        assertPathsDoNotExist(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionController.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionStore.java"));
        assertPathsDoNotExist(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketRawWorkerRouteEndpointRegistry.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketOutputProcessor.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/RawWorkerRouteEndpointRegistry.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportOutboundMessage.java"));
        Path sessionRegistry = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionRegistry.java");
        String sessionRegistrySource = Files.readString(sessionRegistry);
        assertTrue(!sessionRegistrySource.contains("implements WorkerEndpointRegistry"),
                "WebSocket session registry must not be the assigned-delivery endpoint registry");
        assertTrue(!sessionRegistrySource.contains("RawWorkerRouteEndpointRegistry"),
                "WebSocket session registry must not implement the raw route side-channel");
        assertTrue(!sessionRegistrySource.contains("WorkerEndpointInspector"),
                "WebSocket session registry must not implement diagnostics inspector");
        assertTrue(!sessionRegistrySource.contains("listWorkerEndpoints("),
                "WebSocket session registry must not expose diagnostics inspector methods directly");
        assertTrue(!sessionRegistrySource.contains("setEndpointLeaseStore(")
                        && !sessionRegistrySource.contains("setDeliveryCommandConsumerRegistry(")
                        && !sessionRegistrySource.contains("setWorker" + "PresenceIngress("),
                "WebSocket session registry must not own endpoint lease or presence wiring setters");
        assertTrue(sessionRegistrySource.contains("sessionsByWorkerId")
                        && sessionRegistrySource.contains("sessionsByChannel")
                        && !sessionRegistrySource.contains("endpointAddress")
                        && !sessionRegistrySource.contains("sessionsByWorkerGroup")
                        && !sessionRegistrySource.contains("sessionsByRoute"),
                "WebSocket session registry must keep only worker and channel indexes");
        assertTrue(sessionRegistrySource.contains("sendTextToWorker(")
                        && sessionRegistrySource.contains("TextWebSocketFrame"),
                "WebSocket session registry owns the adapter-local selected-worker final-hop send");
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
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionRecord.java"));
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidence.java"));
        assertTrue(sessionRegistrySource.contains("record SessionSnapshot(")
                        && !sessionRegistrySource.contains("WebSocketSessionEvidence"),
                "WebSocket session evidence should stay a store-internal snapshot, not a top-level adapter model");
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidenceDriver.java"));
        assertPathsDoNotExist(
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketSessionOpenFrameReader.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/frame/WebSocketSessionIdentity.java")
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "WebSocketSessionOpenFrameReader",
                "WebSocketSessionIdentity"
        );
        Path adapterSessionIdentity = repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/embedded/AdapterSessionIdentity.java");
        String adapterSessionIdentitySource = Files.readString(adapterSessionIdentity);
        assertTrue(adapterSessionIdentitySource.contains("String deliveryBucketId")
                        && adapterSessionIdentitySource.contains("String workerId"),
                "Adapter session identity must carry only delivery bucket and worker identity");
        assertTrue(!adapterSessionIdentitySource.contains("routeKey")
                        && !adapterSessionIdentitySource.contains("adapterId")
                        && !adapterSessionIdentitySource.contains("sessionHandle")
                        && !adapterSessionIdentitySource.contains("endpointLeaseId")
                        && !adapterSessionIdentitySource.contains("connectionId")
                        && !adapterSessionIdentitySource.contains("transportHint"),
                "Adapter session identity must not become a fat endpoint/session model");
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher")
                ),
                "AdapterSessionIdentity"
        );
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/dispatcher/WebSocketInboundMessage.java"));
        assertNoProductionSourceContains(List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java")),
                "WebSocketInboundMessage");
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointRegistry.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointRegistry.java")
        );
        String webSocketFactorySource = Files.readString(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterRuntimeFactory.java"));
        assertTrue(!webSocketFactorySource.contains("sendToSelectedWorker(\n                            adapterId()")
                        && !webSocketFactorySource.contains("sendToSelectedWorker(\r\n                            adapterId()"),
                "WebSocket assigned delivery must not pass adapterId into selected-worker endpoint send");
        String socketFactorySource = Files.readString(repoRoot().resolve(
                "transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketAdapterRuntimeFactory.java"));
        assertTrue(!socketFactorySource.contains("sendToSelectedWorker(\n                            adapterId()")
                        && !socketFactorySource.contains("sendToSelectedWorker(\r\n                            adapterId()"),
                "Socket assigned delivery must not pass adapterId into selected-worker endpoint send");
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketEndpointInspector.java"));
        Path serverFactoryContext = repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketServerFactoryContext.java");
        String serverFactoryContextSource = Files.readString(serverFactoryContext);
        assertTrue(!serverFactoryContextSource.contains("getEndpointRegistry("),
                "WebSocket custom server factory context must not expose assigned-delivery endpoint registry");
        Path webSocketAdapterConfig = repoRoot().resolve(
                "transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/runtime/WebSocketAdapterConfig.java");
        String webSocketAdapterConfigSource = Files.readString(webSocketAdapterConfig);
        assertTrue(!webSocketAdapterConfigSource.contains("TransportServerFactory")
                        && !webSocketAdapterConfigSource.contains("WebSocketServerFactoryContext"),
                "WebSocketAdapterConfig must remain pure adapter property config; custom server factory hooks belong to SDK assembly");
        assertTrue(!webSocketFactorySource.contains("private final WebSocketAdapterConfig config"),
                "WebSocket runtime factory must not retain live adapter config access");
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
    void socketAssignedDeliveryUsesBootstrapOwnedWorkerIdFinalHop() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketTask" + "DispatchChannel.java"),
                repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/dispatcher/SocketCommandDispatchContext.java")
        );

        Path runtimeFactory = repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/runtime/SocketAdapterRuntimeFactory.java");
        String runtimeFactorySource = Files.readString(runtimeFactory);
        assertTrue(runtimeFactorySource.contains("AdapterCommandExecutors.perMessage(\"Socket\""),
                "Socket runtime factory must let runtime embedded support own per-message outcome normalization");
        assertTrue(runtimeFactorySource.contains("sendToWorker("),
                "Socket assigned delivery executor must dispatch by selected worker only");
        assertTrue(runtimeFactorySource.contains("encodeCanonicalTaskDispatch(item)"),
                "Socket assigned delivery must frame the opaque payload as a worker ACTION frame");
        assertTrue(!runtimeFactorySource.contains("private final SocketAdapterConfig config"),
                "Socket runtime factory must not retain live adapter config access");
        assertSourceSliceDoesNotContain(
                runtimeFactory,
                "static AdapterCommandExecutor socketCommandExecutor",
                "private TransportServer createServer",
                "SocketCommandDispatchContext",
                "WorkerEndpointRegistry",
                "TransportDeliveryService",
                "sendDirect(",
                "dispatchQueueKey",
                "resultQueueKey"
        );


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
        assertTrue(!sessionManagerSource.contains("WorkerEndpointSnapshot")
                        && !sessionManagerSource.contains("listEndpointSnapshots("),
                "SocketSessionManager must not expose endpoint snapshot diagnostics views");
        String routeEndpointIndex = "Route" + "EndpointIndex";
        String entriesForRoute = "entriesFor" + "Route(";
        String sendToRoute = "sendTo" + "Route(";
        String adapterRouteOnline = "isAdapter" + "RouteOnline(";
        assertTrue(!sessionManagerSource.contains(routeEndpointIndex)
                        && !sessionManagerSource.contains(entriesForRoute)
                        && !sessionManagerSource.contains(sendToRoute)
                        && !sessionManagerSource.contains(adapterRouteOnline),
                "SocketSessionManager must keep worker/endpoint indexes instead of routeKey route lookup");
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketEndpointInspector.java"));
    }

    @Test
    void endpointSnapshotViewDoesNotOwnRawRouteOrDiagnosticsRoles() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointRegistry.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/CompositeWorkerEndpointInspector.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointInspector.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/WorkerEndpointSnapshot.java"),
                repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketEndpointInspector.java"),
                repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketEndpointInspector.java")
        );
        Path massApplication = repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java");
        String massApplicationSource = Files.readString(massApplication);
        assertTrue(!massApplicationSource.contains("endpointRegistry instanceof WorkerEndpointInspector"),
                "MassApplication must not discover endpoint diagnostics through endpoint registry side roles");
        assertTrue(!massApplicationSource.contains("listWorkerEndpoints(")
                        && !massApplicationSource.contains("resolveRawMessageRouteKey(")
                        && !massApplicationSource.contains("getEndpointInspector("),
                "MassApplication must not resolve raw routes through endpoint snapshot diagnostics");
    }

    @Test
    void concreteAdaptersDoNotOwnEndpointLeaseOrPresenceProjectionInternals() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionRegistry.java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/session/WebSocketSessionEvidenceRefresher.java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/session/SocketSessionManager.java")
                ),
                "TransportEndpointLeaseClaim",
                "TransportEndpointLeaseHeartbeat",
                "TransportEndpointLeaseRelease",
                "TransportEndpointLeaseConsumerEvidence",
                "DeliveryCommandConsumerClaim",
                "WorkerSession" + "PresenceEvent",
                "endpointLeaseStore.claimEndpointLease",
                "endpointLeaseStore.refreshEndpointLease",
                "endpointLeaseStore.releaseEndpointLease"
        );
        assertTrue(Files.exists(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease/TransportEndpointLeasePublisher.java")),
                "Endpoint lease projection must live in a dedicated publisher");
        String removedPresencePublisher = "WorkerPresence" + "SessionPublisher";
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease/" + removedPresencePublisher + ".java"));
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport"),
                        repoRoot().resolve("sdk")
                ),
                removedPresencePublisher
        );
    }

    @Test
    void sessionEvidencePublisherAndEndpointLeaseApiStayNegativeOnlyAndSingleToken() throws IOException {
        assertPathsDoNotExist(repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease/CurrentSessionConnectSink.java"));

        Path publisherPath = repoRoot().resolve(
                "transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease/AdapterSessionEvidencePublisher.java");
        String publisherSource = Files.readString(publisherPath);
        assertTrue(!publisherSource.contains("adapterMailboxKey")
                        && !publisherSource.contains("traceId")
                        && !publisherSource.contains("claimEndpoint(")
                        && !publisherSource.contains("currentSessionConnected("),
                "AdapterSessionEvidencePublisher must publish endpoint lease evidence without mailbox, trace, or positive recovery hooks");

        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java")
                ),
                "CurrentSessionConnectSink",
                "currentSessionConnected("
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/lease"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/lease")
                ),
                "endpointLeaseId",
                "sessionHandle"
        );
    }

    @Test
    void embeddedPullWorkerSessionUsesEvidenceDriverInsteadOfTransportEvidenceInternals() throws IOException {
        Path embeddedPullWorkerSession = repoRoot().resolve(
                "sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/EmbeddedPullWorkerSession.java");
        assertNoProductionSourceContains(
                List.of(embeddedPullWorkerSession),
                "TransportEndpointLeaseStore",
                "DeliveryCommandConsumerRegistry",
                "WorkerPresence" + "Ingress",
                "WorkerSession" + "PresenceEvent",
                "TransportEndpointLeaseClaim",
                "TransportEndpointLeaseHeartbeat",
                "TransportEndpointLeaseRelease",
                "DeliveryCommandConsumerClaim",
                "claimEndpointLease",
                "refreshEndpointLease",
                "releaseEndpointLease"
        );
        String source = Files.readString(embeddedPullWorkerSession);
        assertTrue(source.contains("PullSessionEvidencePort")
                        && !source.contains("PullSessionEvidenceDriver"),
                "EmbeddedPullWorkerSession must consume the adapter-starter pull-session evidence port");
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
        String removedPacketModel = "Transport" + "Packet";
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskDispatchRoutingSubmitter.java")),
                removedPacketModel,
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
        assertPathsDoNotExist(
                repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/WorkerDeliveryTargetView.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("xa-mass-worker-runtime/src/main/java/com/xa/mass/worker/runtime/evidence/SelectedWorkerDeliveryTargetEvidence.java")
                ),
                "List<",
                "Map<",
                "list",
                "stats",
                "snapshot",
                "count",
                "inspect",
                "WorkerReachabilityState",
                "reachabilityState"
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
        String removedPacketModel = "Transport" + "Packet";
        assertNoProductionSourceContains(
                List.of(codec),
                removedPacketModel,
                "TaskDispatchItem",
                "connectionToken",
                "taskName",
                "project",
                "userId"
        );
        assertSourceSliceDoesNotContain(
                codec,
                "private record DispatchMessageRecord",
                "private static final class DecodedAdapterMailboxDispatchBatchRecord",
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
        String removedPacketModel = "Transport" + "Packet";
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeCodec.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeRecord.java")
        );
        Path codec = repoRoot().resolve("transport/polling-adapter/src/main/java/com/xa/mass/transport/polling/delivery/PollingDispatchMessageCodec.java");
        assertNoProductionSourceContains(
                List.of(codec),
                removedPacketModel,
                "TaskDispatchItem",
                "transportPayload"
        );
        assertSourceSliceDoesNotContain(
                codec,
                "private record RedisDispatchMessageRecord",
                "private static final class DecodedRedisDispatchMessageRecord",
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
    void transportDoesNotReintroduceDeliveryFailureInboxSideChannel() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDeliveryFailureChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureEvent.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureEventCodec.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureHandler.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDeliveryFailureInboxPump.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("transport/adapter-starter/src/main/java"),
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java")
                ),
                "RedisTransport" + "DeliveryFailureChannel",
                "Transport" + "DeliveryFailureEvent",
                "Transport" + "DeliveryFailureHandler",
                "DeliveryFailure" + "InboxPump",
                "redisDelivery" + "FailureInbox",
                "deliveryFailure" + "InboxFactory"
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
    void workerActionSurfacesExposeOnlyOpaqueReplyCorrelation() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/DispatchContext.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerDispatchItem.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerInvocation.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerInvocation.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerResultSubmission.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/session/WorkerDispatchHandler.java"),
                repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerResultSink.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerAction.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerActionReply.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerActionResult.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/PulledTaskDispatch.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/WorkerAction.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker/WorkerActionReply.java"),
                        repoRoot().resolve("xa-mass-server/src/main/java/com/xa/mass/api/model/worker/WorkerActionReplyRequest.java")
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
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/WorkerAction.java"),
                        repoRoot().resolve("sdk/xa-mass-java-sdk/src/main/java/com/xa/mass/client/worker/handler/WorkerActionHandler.java")
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
        assertTrue(source.contains("result callback payload requires replyRef"),
                "Worker result ingress payload must require opaque replyRef");
        assertTrue(source.contains("result ingress message correlation must match payload replyRef"),
                "Starter result bridge must validate message correlation against worker replyRef");
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
                        repoRoot().resolve("transport/socket-adapter/src/main/java/com/xa/mass/transport/socket/server/SocketTransportServer.java")
                ),
                "deliveryBucketId",
                "DELIVERY_BUCKET_ID_FIELD",
                "workerId/deliveryBucketId"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/websocket-adapter/src/main/java/com/xa/mass/transport/websocket/server/DispatcherInboundHandler.java")),
                "\"deliveryBucketId\"",
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
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/channel/TransportResultIngressOutcome.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/BufferedTaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTaskResultIngestChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TaskResultIngestInboxPump.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/ClaimedTransportResultIngress.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportResultIngressInboxPump.java"),
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
                "TaskResultIngestInboxPump",
                "TransportResultIngressOutcome",
                "ClaimedTransportResultIngress",
                "TransportResultIngressInboxPump"
        );
    }

    @Test
    void resultIngressMainlineUsesExplicitEntryAndAdaptersDoNotParseResultSemantics() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/routing/RoutingEnvelope.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/routing/RoutingTarget.java"),
                repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/routing/RoutingOwnerKinds.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RoutingEnvelopeCodec.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/TransportResultIngressQueuePump.java")
        );
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
                "TransportResultIngressEnvelope",
                "RoutingEnvelope",
                "RoutingTarget",
                "RoutingOwnerKinds",
                "RoutingEnvelopeCodec"
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
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
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTransportResultIngressChannel.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/TaskResultIngressQueueDrain.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/RuntimeTaskResultIngestChannel.java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/ResultIngressHandleOutcome.java")
                ),
                "pendingCount(",
                "queuedResults(",
                "TransportResultIngressOutcome",
                "ClaimedTransportResultIngress",
                "TransportResultIngressInboxPump",
                "visibilityTimeout",
                "inflight",
                "complete(",
                "ackable",
                "RETRYABLE_FAILURE",
                "toTransportOutcome",
                "reclaim"
        );
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

    @Test
    void transportQueueMainlineUsesInfraQueuePrimitivesOnly() throws IOException {
        assertPathsDoNotExist(
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/BufferedTransportResultIngressChannel.java"),
                repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/TransportDispatchHandoff.java")
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/InMemoryTransportResultIngressQueue.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/RedisTransportResultIngressChannel.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/InMemoryTransportDispatchHandoff.java"),
                        repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchHandoff.java")
                ),
                "LinkedBlockingQueue",
                "ArrayBlockingQueue",
                "BlockingQueue<",
                "Thread.sleep",
                "TimeUnit.MILLISECONDS.sleep",
                "TimeUnit.NANOSECONDS.sleep",
                "RedisCommands",
                "RPUSH",
                "LPOP",
                "BRPOP",
                "BLPOP"
        );
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/transport_runtime/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java")
                ),
                "import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff",
                "implements TransportDispatchHandoff",
                "Supplier<TransportDispatchHandoff>",
                "TransportDispatchHandoff transportDispatch",
                "TransportDispatchHandoff handoff"
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
