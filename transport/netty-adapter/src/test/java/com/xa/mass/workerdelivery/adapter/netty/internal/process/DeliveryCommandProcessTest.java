package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.STARTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess.ReportIngressStatus.FULL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
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
        private final List<Integer> requestedLimits = new ArrayList<>();
        private final List<String> appendedReports = new ArrayList<>();
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
                Map<String, Object> encoded = new LinkedHashMap<>();
                if (batch != null) {
                    batch.forEach((workerId, command) -> encoded.put(
                            workerId,
                            Jsons.parseObject(CODEC.encodeDeliveryCommand(
                                    command
                            ))
                    ));
                }
                return new Response(200, Jsons.toJson(Map.of(
                        "workerCommandsByWorkerId", encoded
                )));
            }
            if (request.rawPath().endsWith("/results:append")) {
                @SuppressWarnings("unchecked")
                List<String> reports = (List<String>) Jsons.parseObject(
                        request.body()
                ).get("results");
                appendedReports.addAll(reports);
                return new Response(202, Jsons.toJson(Map.of(
                        "acceptedCount", reports.size(),
                        "rejectedCount", 0
                )));
            }
            return new Response(204, "");
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
            channel.close();
        }

        @Override
        public void close() {
        }
    }
}
