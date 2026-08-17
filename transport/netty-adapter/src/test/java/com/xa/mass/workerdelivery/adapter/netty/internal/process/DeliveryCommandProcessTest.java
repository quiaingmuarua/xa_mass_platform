package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.NettyAdapterProcessConfig;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
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
    void expiredCommandCreatesBestEffortAdapterResult() {
        try (Fixture fixture = new Fixture(1, 1, 2)) {
            DeliveryCommand expired = command(1_000, "expired-context");
            fixture.peer.batches.add(Map.of("worker-1", expired));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedReports).singleElement()
                    .satisfies(encoded -> assertThat(
                            CODEC.decodeDeliveryReport(encoded)
                    ).isEqualTo(DeliveryReport.fromCommand(
                            expired,
                            ADAPTER,
                            "adapter-1",
                            Integer.toString(
                                    WorkerDeliveryAdapterErrorCode
                                            .COMMAND_EXPIRED.code()
                            ),
                            "null"
                    )));
        }
    }

    @Test
    void rejectedExpiredResultDoesNotRetainTheCommand() {
        try (Fixture fixture = new Fixture(1, 1, 1)) {
            assertThat(fixture.reportProcess.ingress(List.of("occupied")))
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
                    .containsExactly("occupied");
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
    void unifiedCommandSourceExecutesAdapterProbe() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            DeliveryCommand probe = controlCommand(
                    ADAPTER,
                    "platform.adapter.probe",
                    "null",
                    2_000
            );
            fixture.peer.controlBatches.add(Map.of(
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    probe
            ));
            fixture.peer.batches.add(commands("worker-1"));
            fixture.activate("worker-1");

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.server.requests())
                    .extracting(ScriptedHttpServer.Request::rawPath)
                    .startsWith(
                            "/api/v1/worker-delivery/endpoint-managers/"
                                    + "adapter-1/commands:consume"
                    );
            assertThat(fixture.peer.appendedControlReports).singleElement()
                    .satisfies(encoded -> {
                        DeliveryReport report = CODEC.decodeDeliveryReport(
                                encoded
                        );
                        assertThat(report).isEqualTo(
                                DeliveryReport.fromCommand(
                                        probe,
                                        ADAPTER,
                                        "adapter-1",
                                        "200",
                                        report.payload()
                                )
                        );
                        assertThat(Jsons.parseObject(report.payload()))
                                .containsEntry("adapterId", "adapter-1")
                                .containsEntry("reachable", true);
                    });
        }
    }

    @Test
    void unifiedCommandSourceReportsTheStaticAdapterEvents() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.peer.controlBatches.add(Map.of(
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    controlCommand(
                            ADAPTER,
                            AdapterControlExecutor.EVENTS_SNAPSHOT_EVENT,
                            "null",
                            2_000
                    )
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedControlReports).singleElement()
                    .satisfies(encoded -> {
                        DeliveryReport report = CODEC.decodeDeliveryReport(
                                encoded
                        );
                        assertThat(report.outcomeCode()).isEqualTo("200");
                        assertThat(Jsons.parseObject(report.payload()).get(
                                "eventNames"
                        )).isEqualTo(List.of(
                                "platform.adapter.events.snapshot",
                                "platform.adapter.probe",
                                "platform.adapter.worker-connections."
                                        + "close-current",
                                "platform.adapter.worker-connections.snapshot"
                        ));
                    });
        }
    }

    @Test
    void connectionSnapshotIsBoundedToExplicitWorkerIds() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.activate("worker-1");
            List<String> workerIds = new ArrayList<>();
            for (int index = 1; index <= 100; index++) {
                workerIds.add("worker-" + index);
            }
            fixture.peer.controlBatches.add(Map.of(
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    controlCommand(
                            ADAPTER,
                            "platform.adapter.worker-connections.snapshot",
                            Jsons.toJson(Map.of(
                                    "workerIds",
                                    workerIds
                            )),
                            2_000
                    )
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedControlReports).singleElement()
                    .satisfies(encoded -> {
                        DeliveryReport report = CODEC.decodeDeliveryReport(
                                encoded
                        );
                        assertThat(report.outcomeCode()).isEqualTo("200");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> states =
                                (Map<String, Object>) Jsons.parseObject(
                                        report.payload()
                                ).get("connectedByWorkerId");
                        assertThat(states.keySet())
                                .containsExactlyElementsOf(workerIds);
                        assertThat(states).hasSize(100)
                                .containsEntry("worker-1", true)
                                .containsEntry("worker-100", false);
                    });
        }
    }

    @Test
    void closeCurrentUsesTheConnectionAndPhysicalNetworkOwners() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.activate("worker-1");
            fixture.peer.controlBatches.add(Map.of(
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    controlCommand(
                            ADAPTER,
                            "platform.adapter.worker-connections.close-current",
                            Jsons.toJson(Map.of(
                                    "workerIds",
                                    List.of("worker-1", "worker-2")
                            )),
                            2_000
                    )
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.network.closedWorkerIds)
                    .containsExactly("worker-1");
            assertThat(fixture.network.closeReasons).containsExactly(
                    AdapterConnectionCloseReason.CONTROL_REQUEST
            );
            assertThat(fixture.routes.activeChannel("worker-1")).isNull();
            assertThat(fixture.peer.appendedControlReports).singleElement()
                    .satisfies(encoded -> {
                        DeliveryReport report = CODEC.decodeDeliveryReport(
                                encoded
                        );
                        @SuppressWarnings("unchecked")
                        Map<String, Object> outcomes =
                                (Map<String, Object>) Jsons.parseObject(
                                        report.payload()
                                ).get("outcomeByWorkerId");
                        assertThat(outcomes).containsExactly(
                                Map.entry("worker-1", "close-started"),
                                Map.entry("worker-2", "not-connected")
                        );
                    });
        }
    }

    @Test
    void connectionControlRejectsDuplicateOrUnknownPayloadFields() {
        List<String> invalidPayloads = new ArrayList<>(List.of(
                "{\"workerIds\":[\"w1\",\"w1\"]}",
                "{\"workerIds\":[\"w1\"],\"extra\":true}",
                "{\"workerIds\":[]}"
        ));
        List<String> tooManyWorkerIds = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooManyWorkerIds.add("worker-" + index);
        }
        invalidPayloads.add(Jsons.toJson(Map.of(
                "workerIds",
                tooManyWorkerIds
        )));
        for (String payload : invalidPayloads) {
            try (Fixture fixture = new Fixture(1, 2, 10)) {
                fixture.peer.controlBatches.add(Map.of(
                        DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                        controlCommand(
                                ADAPTER,
                                "platform.adapter.worker-connections.snapshot",
                                payload,
                                2_000
                        )
                ));

                fixture.process.round();
                fixture.reportProcess.round();

                assertThat(fixture.peer.appendedControlReports)
                        .singleElement()
                        .satisfies(encoded -> assertThat(
                                CODEC.decodeDeliveryReport(encoded)
                                        .outcomeCode()
                        ).isEqualTo(Integer.toString(
                                WorkerDeliveryAdapterErrorCode
                                        .CONTROL_COMMAND_INVALID.code()
                        )));
            }
        }
    }

    @Test
    void controlWorkerCommandUsesTheExistingConnectionRoute() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            DeliveryCommand control = controlCommand(
                    WORKER,
                    "worker.observe",
                    "{}",
                    2_000
            );
            fixture.peer.controlBatches.add(Map.of("worker-1", control));
            fixture.activate("worker-1");

            fixture.process.round();

            assertThat(fixture.network.writtenWorkerIds)
                    .containsExactly("worker-1");
            assertThat(fixture.peer.appendedControlReports).isEmpty();
        }
    }

    @Test
    void unsupportedAdapterControlProducesObservedAdapterError() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            DeliveryCommand unsupported = controlCommand(
                    ADAPTER,
                    "extension.adapter.unknown",
                    "null",
                    2_000
            );
            fixture.peer.controlBatches.add(Map.of(
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    unsupported
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedControlReports).singleElement()
                    .satisfies(encoded -> assertThat(
                            CODEC.decodeDeliveryReport(encoded).outcomeCode()
                    ).isEqualTo(Integer.toString(
                            WorkerDeliveryAdapterErrorCode
                                    .CONTROL_EVENT_UNSUPPORTED.code()
                    )));
        }
    }

    @Test
    void adapterProbeRejectsANonNullPayload() {
        try (Fixture fixture = new Fixture(1, 2, 10)) {
            fixture.peer.controlBatches.add(Map.of(
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    controlCommand(
                            ADAPTER,
                            "platform.adapter.probe",
                            "{}",
                            2_000
                    )
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedControlReports).singleElement()
                    .satisfies(encoded -> assertThat(
                            CODEC.decodeDeliveryReport(encoded).outcomeCode()
                    ).isEqualTo(Integer.toString(
                            WorkerDeliveryAdapterErrorCode
                                    .CONTROL_COMMAND_INVALID.code()
                    )));
        }
    }

    @Test
    void expiredOrMisaddressedControlDoesNotFabricateResult() {
        try (Fixture fixture = new Fixture(2, 2, 10)) {
            fixture.peer.controlBatches.add(Map.of(
                    "worker-expired",
                    controlCommand(
                            WORKER,
                            "worker.observe",
                            "{}",
                            1_000
                    ),
                    DeliveryCommandProcess.ADAPTER_TARGET_ADDRESS,
                    controlCommand(
                            WORKER,
                            "worker.observe",
                            "{}",
                            2_000
                    )
            ));

            fixture.process.round();
            fixture.reportProcess.round();

            assertThat(fixture.peer.appendedControlReports).isEmpty();
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

    private static DeliveryCommand controlCommand(
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
                "control-only:v1:test"
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
        private final WorkerRouteRegistry routes = new WorkerRouteRegistry();
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
                    routes,
                    network,
                    new WorkerRouteRemoteApi(httpClient),
                    CODEC,
                    reportProcess,
                    "adapter-1",
                    Duration.ofSeconds(1)
            );
            process = new DeliveryCommandProcess(
                    new DeliveryCommandRemoteApi(httpClient, CODEC),
                    connectionMechanism,
                    AdapterControlExecutor.defaults(
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
            EmbeddedChannel channel = new EmbeddedChannel();
            channels.add(channel);
            network.workerIds.put(channel, workerId);
            routes.admitIdentity(workerId, channel);
            assertThat(routes.completeVerificationAndActivate(
                    workerId,
                    channel
            ).accepted()).isTrue();
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
        private final ArrayDeque<Map<String, DeliveryCommand>>
                controlBatches = new ArrayDeque<>();
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<String> appendedReports = new ArrayList<>();
        private final List<String> appendedControlReports = new ArrayList<>();
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
                Map<String, DeliveryCommand> batch = controlBatches.pollFirst();
                if (batch == null) {
                    batch = batches.pollFirst();
                }
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
                            appendedControlReports.add(report);
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
