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
    void adaptersDoNotPublishWorkerLifecycleFromSessionState() throws IOException {
        assertNoProductionSourceContains(
                List.of(
                        repoRoot().resolve("transport/polling-adapter/src/main/java"),
                        repoRoot().resolve("transport/socket-adapter/src/main/java"),
                        repoRoot().resolve("transport/websocket-adapter/src/main/java"),
                        repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/sdk/worker")
                ),
                "publishWorkerOnline(",
                "publishWorkerOffline(",
                "publishWorkerHeartbeat("
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
                "WorkerDispatchRouteOwnerView",
                "activeOwnerForSelectedWorker(",
                "getWorkerRouteOwnerView("
        );
    }

    @Test
    void starterDoesNotExposeRouteOwnerViewAsSdkReadableInspectionSurface() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("sdk/xa-mass-embedded-sdk/src/main/java/com/xa/mass/starter/MassApplication.java")),
                "public WorkerDispatchRouteOwnerView",
                "getWorkerRouteOwnerView("
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
    void redisDeliveryCommandHandoffUsesDeliveryLanes() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDeliveryCommandHandoff.java")),
                "\":route:\"",
                "ready-routes",
                "routeQueueKey("
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
                "correlation",
                "Map<String, String>"
        );
    }

    @Test
    void taskDispatchExecutionContextDoesNotCarryTransportRouteOrSessionFacts() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TaskDispatchExecutionContext.java")),
                "workerId",
                "routeKey",
                "adapterId",
                "deliveryQueueKey",
                "targetTransportNodeId",
                "connectionId",
                "connectionToken",
                "session",
                "endpoint"
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
                "\"unknown\"",
                "correlation"
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
                "correlation",
                "\"payload\""
        );
        assertSourceSliceDoesNotContain(
                codec,
                "private record DeliveryCommandRecord",
                "private record TaskDispatchContentRecord",
                "adapterId",
                "deliveryQueueKey",
                "targetTransportNodeId",
                "routeKey",
                "connectionId"
        );
    }

    @Test
    void transportDispatchEnvelopeDoesNotCarryStoreQueueKey() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_api/src/main/java/com/xa/mass/transport/model/TransportDispatchEnvelope.java")),
                "deliveryQueueKey",
                "getDeliveryQueueKey("
        );
    }

    @Test
    void redisDispatchEnvelopeValueDoesNotSerializeStoreQueueKey() throws IOException {
        Path codec = repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeCodec.java");
        assertSourceSliceDoesNotContain(
                codec,
                "byte[] encodeEntry",
                "private static String encodeKeyToken",
                "deliveryQueueKey"
        );
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/RedisTransportDispatchEnvelopeRecord.java")),
                "deliveryQueueKey"
        );
    }

    @Test
    void deliveryStoreDoesNotRecoverQueueKeyFromEnvelopeValue() throws IOException {
        assertNoProductionSourceContains(
                List.of(repoRoot().resolve("transport/transport_runtime/src/main/java/com/xa/mass/transport/runtime/delivery/QueueBackedTransportDeliveryStore.java")),
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
                "connectionToken",
                "correlation",
                "\"payload\""
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
