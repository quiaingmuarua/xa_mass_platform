package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.NettyAdapterProcessConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerPropertiesCacheConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerRouteCacheConfig;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection
        .WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection
        .WorkerConnectionState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryCommandRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerRouteRemoteApi;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DeliveryCommandProcessTest {


    private static final WorkerDeliveryCodec CODEC = new WorkerDeliveryCodec();

    @Test
    void softCapacityAllowsOneRemoteBatchOfRedundancy() {
        try (Fixture fixture = new Fixture(2, 3, 10)) {
            fixture.peer.batches.add(commands("worker-1", "worker-2"));
            fixture.peer.batches.add(commands("worker-3", "worker-4"));
            fixture.peer.batches.add(commands("worker-5"));

            fixture.process.round();
            fixture.process.round();
            fixture.process.round();

            assertThat(fixture.peer.requestedLimits).containsExactly(2, 2);
        }
    }

    @Test
    void remoteFailureDoesNotPreventQueuedCommandDelivery() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.peer.batches.add(commands("worker-1"));
            fixture.process.round();
            fixture.peer.failures = 1;
            fixture.activate("worker-1");

            fixture.process.round();

            assertThat(fixture.peer.requestedLimits).containsExactly(1, 1);
            assertThat(fixture.network.writtenWorkerIds)
                    .containsExactly("worker-1");
        }
    }

    @Test
    void malformedRemoteResponseIsOwnerLocalAndDoesNotStopRounds() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.peer.responseBodyOverride = "{\"unexpected\":true}";

            fixture.process.round();
            fixture.peer.responseBodyOverride = null;
            fixture.peer.batches.add(commands("worker-1"));
            fixture.process.round();

            assertThat(fixture.peer.requestedLimits).containsExactly(1, 1);
        }
    }

    @Test
    void eachObservedCommandRunsOnceAndOnlyRetryLaterReturns() {
        try (Fixture fixture = new Fixture(3, 3, 10)) {
            fixture.peer.batches.add(commands(
                    "worker-started",
                    "worker-unknown",
                    "worker-retry"
            ));
            fixture.activate("worker-started");
            fixture.activate("worker-unknown");
            fixture.activate("worker-retry");
            fixture.network.attempt = workerId -> switch (workerId) {
                case "worker-started" -> STARTED;
                case "worker-unknown" -> UNKNOWN;
                default -> RETRY_LATER;
            };

            fixture.process.round();
            fixture.process.round();

            assertThat(fixture.network.writtenWorkerIds)
                    .containsOnlyOnce("worker-started", "worker-unknown")
                    .filteredOn("worker-retry"::equals)
                    .hasSize(2);
        }
    }

    @Test
    void expiredCommandCreatesTaskResultAndKernelEvidenceTogether() {
        try (Fixture fixture = new Fixture(1, 1, 2)) {
            DeliveryCommand expired = command(1_000, "expired-context");
            fixture.peer.batches.add(Map.of("worker-1", expired));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedReports.stream()
                    .map(CODEC::decodeDeliveryReport)
                    .toList())
                    .hasSize(2)
                    .anySatisfy(report -> assertThat(report).isEqualTo(
                            DeliveryReport.fromCommand(
                                    expired,
                                    ADAPTER,
                                    "adapter-1",
                                    Integer.toString(
                                            WorkerDeliveryAdapterErrorCode
                                                    .COMMAND_EXPIRED.code()
                                    ),
                                    "null"
                            )
                    ))
                    .anySatisfy(report -> {
                        assertThat(report.src()).isEqualTo(ADAPTER);
                        assertThat(report.sourceId()).isEqualTo("adapter-1");
                        assertThat(report.dst()).isEqualTo(KERNEL);
                        assertThat(report.messageType()).isEqualTo(
                                "platform.adapter.worker-delivery.expired"
                        );
                        assertThat(report.outcomeCode()).isEqualTo("200");
                        assertThat(report.forward()).isEqualTo(
                                "worker-serviceability-evidence:v1"
                        );
                        assertThat(Jsons.parseObject(report.payload()))
                                .containsExactlyInAnyOrderEntriesOf(Map.of(
                                        "workerId", "worker-1",
                                        "observedAtMillis", 1_000L
                                ));
                    });
        }
    }

    @Test
    void rejectedExpiredResultDoesNotRetainTheCommand() {
        try (Fixture fixture = new Fixture(1, 1, 2)) {
            assertThat(fixture.reportProcess.ingress(List.of(
                    "occupied-1",
                    "occupied-2"
            )))
                    .isEqualTo(ACCEPTED);
            assertThat(fixture.reportProcess.ingress(List.of("full")))
                    .isEqualTo(FULL);
            fixture.peer.batches.add(Map.of(
                    "worker-1",
                    command(1_000, "expired-context")
            ));

            fixture.process.round();
            fixture.reportProcess.round();
            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedReports)
                    .containsExactly("occupied-1", "occupied-2");
        }
    }

    @Test
    void quiescePreventsFurtherRemoteAndConnectionWork() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.peer.batches.add(commands("worker-1"));
            fixture.process.round();

            fixture.process.quiesce();
            fixture.activate("worker-1");
            fixture.process.round();
            fixture.process.finishAfterSchedulerStop();
            fixture.process.finishAfterSchedulerStop();

            assertThat(fixture.peer.requestedLimits).containsExactly(1);
            assertThat(fixture.network.writtenWorkerIds).isEmpty();
        }
    }

    @Test
    void oneQueueDispatchesMultipleOpaqueAdapterEntriesAndAWorkerEntry() {
        try (Fixture fixture = new Fixture(3, 3, 10)) {
            DeliveryCommand probe = systemCommand(
                    ADAPTER,
                    "platform.adapter.probe",
                    "null",
                    2_000
            );
            DeliveryCommand events = systemCommand(
                    ADAPTER,
                    AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT,
                    "null",
                    2_000
            );
            DeliveryCommand worker = systemCommand(
                    WORKER,
                    "platform.worker.probe",
                    "null",
                    2_000
            );
            fixture.peer.batches.add(Map.of(
                    "first-opaque-entry", probe,
                    "second-opaque-entry", events,
                    "worker-1", worker
            ));
            fixture.activate("worker-1");

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.network.writtenWorkerIds)
                    .containsExactly("worker-1");
            assertThat(fixture.peer.appendedSystemReports.stream()
                    .map(CODEC::decodeDeliveryReport)
                    .toList())
                    .hasSize(2)
                    .extracting(DeliveryReport::messageType)
                    .containsExactlyInAnyOrder(
                            "platform.adapter.probe",
                            AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT
                    );
        }
    }

    @Test
    void systemWorkerCommandUsesTheExistingConnectionRoute() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            DeliveryCommand systemCommand = systemCommand(
                    WORKER,
                    "worker.observe",
                    "{}",
                    2_000
            );
            fixture.peer.batches.add(Map.of("worker-1", systemCommand));
            fixture.activate("worker-1");

            fixture.process.round();

            assertThat(fixture.network.writtenWorkerIds)
                    .containsExactly("worker-1");
            assertThat(fixture.peer.appendedSystemReports).isEmpty();
        }
    }

    @Test
    void expiredOrMisaddressedSystemCommandDoesNotFabricateResult() {
        try (Fixture fixture = new Fixture(2, 2, 10)) {
            fixture.peer.batches.add(Map.of(
                    "worker-expired",
                    systemCommand(
                            WORKER,
                            "worker.observe",
                            "{}",
                            1_000
                    ),
                    "opaque-worker-entry",
                    systemCommand(
                            WORKER,
                            "worker.observe",
                            "{}",
                            2_000
                    )
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedSystemReports).isEmpty();
            assertThat(fixture.peer.appendedReports).isEmpty();
        }
    }

    private static Map<String, DeliveryCommand> commands(String... workerIds) {
        Map<String, DeliveryCommand> commands = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            commands.put(workerId, command(2_000, workerId + "-context"));
        }
        return commands;
    }

    private static DeliveryCommand command(long deadline, String forward) {
        return DeliveryCommand.create(
                TASK,
                WORKER,
                "test.observe",
                deadline,
                "{}",
                forward
        );
    }

    private static DeliveryCommand systemCommand(
            com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
                    .DeliveryEndpoint destination,
            String event,
            String payload,
            long deadline
    ) {
        return DeliveryCommand.create(
                SYSTEM,
                destination,
                event,
                deadline,
                payload,
                "direct-call:v1:test"
        );
    }

    private static final class Fixture implements AutoCloseable {

        private final RemotePeer peer = new RemotePeer();
        private final WorkerDeliveryHttpClient httpClient =
                new WorkerDeliveryHttpClient(
                        peer.server.baseUri(),
                        Duration.ofSeconds(2)
                );
        private final DeliveryReportProcess reportProcess;
        private final FakeNetworkServer network = new FakeNetworkServer();
        private final WorkerConnectionMechanism connectionMechanism;
        private final DeliveryCommandProcess process;
        private final List<EmbeddedChannel> channels = new ArrayList<>();

        private Fixture(
                int consumeLimit,
                int commandCapacity,
                int reportCapacity
        ) {
            reportProcess = new DeliveryReportProcess(
                    new DeliveryReportRemoteApi(httpClient),
                    "adapter-1",
                    reportCapacity
            );
            connectionMechanism = new WorkerConnectionMechanism(
                    new WorkerRouteRegistry(
                            new NettyWorkerRouteCacheConfig(
                                    Duration.ofMinutes(10),
                                    100_000L
                            )
                    ),
                    network,
                    new WorkerRouteRemoteApi(httpClient),
                    CODEC,
                    reportProcess,
                    "adapter-1",
                    Duration.ofSeconds(1),
                    new NettyWorkerPropertiesCacheConfig(
                            64L * 1024L * 1024L
                    )
            );
            process = new DeliveryCommandProcess(
                    new DeliveryCommandRemoteApi(httpClient, CODEC),
                    connectionMechanism,
                    AdapterEventDispatcher.defaults(
                            "adapter-1",
                            connectionMechanism
                    ),
                    reportProcess,
                    CODEC,
                    "adapter-1",
                    consumeLimit,
                    commandCapacity,
                    () -> 1_000
            );
        }

        private void activate(String workerId) {
            EmbeddedChannel channel = new EmbeddedChannel(
                    new WorkerConnectionInboundHandler(connectionMechanism)
            );
            channels.add(channel);
            network.workerIds.put(channel, workerId);
            channel.writeInbound(CODEC.encodeDeliveryReport(
                    DeliveryReport.create(
                            WORKER,
                            workerId,
                            ADAPTER,
                            WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                            "200",
                            "null",
                            ""
                    )
            ));
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
            while (System.nanoTime() < deadline) {
                channel.runPendingTasks();
                if (connectionMechanism.connectionStates(
                        List.of(workerId)
                ).get(workerId) == WorkerConnectionState.CONNECTED) {
                    return;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "Interrupted while activating Worker route",
                            error
                    );
                }
            }
            throw new AssertionError("Worker route did not activate");
        }

        @Override
        public void close() {
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            peer.close();
        }
    }

    private static final class RemotePeer implements AutoCloseable {

        private final ArrayDeque<Map<String, DeliveryCommand>> batches =
                new ArrayDeque<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<String> appendedReports = new ArrayList<>();
        private final List<String> appendedSystemReports = new ArrayList<>();
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );
        private int failures;
        private String responseBodyOverride;

        private synchronized Response handle(
                ScriptedHttpServer.Request request
        ) {
            if (request.rawPath().endsWith("/commands:consume")) {
                Object limit = Jsons.parseObject(request.body()).get("limit");
                requestedLimits.add(Math.toIntExact((Long) limit));
                if (failures > 0) {
                    failures--;
                    return new Response(503, "{}");
                }
                if (responseBodyOverride != null) {
                    return new Response(200, responseBodyOverride);
                }
                Map<String, DeliveryCommand> batch = batches.pollFirst();
                return commandResponse("commands", batch);
            }
            if (request.rawPath().endsWith("/results:append")) {
                @SuppressWarnings("unchecked")
                List<String> reports = (List<String>) Jsons.parseObject(
                        request.body()
                ).get("results");
                for (String report : reports) {
                    try {
                        if (CODEC.decodeDeliveryReport(report).dst() == SYSTEM) {
                            appendedSystemReports.add(report);
                        } else {
                            appendedReports.add(report);
                        }
                    } catch (RuntimeException ignored) {
                        appendedReports.add(report);
                    }
                }
                return accepted(reports.size());
            }
            return new Response(204, "");
        }

        private static Response commandResponse(
                String field,
                Map<String, DeliveryCommand> batch
        ) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            if (batch != null) {
                batch.forEach((target, command) -> encoded.put(
                        target,
                        Jsons.parseObject(CODEC.encodeDeliveryCommand(command))
                ));
            }
            return new Response(200, Jsons.toJson(Map.of(field, encoded)));
        }

        private static Response accepted(int count) {
            return new Response(202, Jsons.toJson(Map.of(
                    "acceptedCount", count,
                    "rejectedCount", 0
            )));
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private static final class FakeNetworkServer
            implements NettyWorkerServer {

        private final Map<Channel, String> workerIds =
                new IdentityHashMap<>();
        private final List<String> writtenWorkerIds = new ArrayList<>();
        private final List<String> closedWorkerIds = new ArrayList<>();
        private final List<AdapterConnectionCloseReason> closeReasons =
                new ArrayList<>();
        private Function<String, TextWriteAttempt> attempt =
                workerId -> STARTED;

        @Override
        public void start(ChannelHandler sharedConnectionHandler) {
        }

        @Override
        public TextWriteAttempt writeText(Channel channel, String message) {
            String workerId = workerIds.get(channel);
            writtenWorkerIds.add(workerId);
            return attempt.apply(workerId);
        }

        @Override
        public void writeTextAndClose(
                Channel channel,
                String message,
                AdapterConnectionCloseReason reason
        ) {
            channel.close();
        }

        @Override
        public void closeConnection(
                Channel channel,
                AdapterConnectionCloseReason reason
        ) {
            closedWorkerIds.add(workerIds.get(channel));
            closeReasons.add(reason);
            channel.close();
        }

        @Override
        public void close() {
        }
    }
}
