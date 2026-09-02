package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.RETRY_LATER;
import static com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt.STARTED;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.TASK;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerPropertiesCacheConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerRouteCacheConfig;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionState;
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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DeliveryCommandProcessTest {

    private static final WorkerDeliveryCodec CODEC = new WorkerDeliveryCodec();

    @Test
    void emptyResponseUsesTimedBackoffInsteadOfHotPolling()
            throws Exception {
        try (Fixture fixture = new Fixture(
                1,
                10,
                10,
                Duration.ofMillis(300)
        )) {
            fixture.startCommandLoop();
            await(() -> fixture.peer.requestedLimits.size() == 1);

            Thread.sleep(100);

            assertThat(fixture.peer.requestedLimits).containsExactly(1);
        }
    }

    @Test
    void residentLoopConsumesContinuousBatchesBeyondThePreviousCap()
            throws Exception {
        try (Fixture fixture = new Fixture(
                1,
                10,
                10,
                Duration.ofSeconds(1)
        )) {
            for (int index = 1; index <= 12; index++) {
                String workerId = "worker-" + index;
                fixture.activate(workerId);
                fixture.peer.batches.add(commands(workerId));
            }

            fixture.startCommandLoop();
            await(() -> fixture.network.writtenWorkerIds.size() == 12);

            assertThat(fixture.network.writtenWorkerIds).hasSize(12);
            assertThat(fixture.peer.batches).isEmpty();
            assertThat(fixture.peer.requestedLimits.size())
                    .isGreaterThanOrEqualTo(12);
        }
    }

    @Test
    void retrySliceRunsBeforeFreshAndDoesNotBlockFreshAcquisition()
            throws Exception {
        try (Fixture fixture = new Fixture(
                1,
                1,
                10,
                Duration.ofSeconds(1)
        )) {
            fixture.activate("worker-retry");
            fixture.activate("worker-fresh");
            fixture.network.attempt = workerId ->
                    "worker-retry".equals(workerId)
                            ? RETRY_LATER
                            : STARTED;
            fixture.peer.batches.add(commands("worker-retry"));
            fixture.peer.batches.add(commands("worker-fresh"));

            fixture.startCommandLoop();
            await(() -> fixture.network.writtenWorkerIds.contains(
                    "worker-fresh"
            ));

            assertThat(List.copyOf(
                    fixture.network.writtenWorkerIds
            ).subList(0, 3))
                    .containsExactly(
                            "worker-retry",
                            "worker-retry",
                            "worker-fresh"
                    );
            assertThat(fixture.peer.requestedLimits.size())
                    .isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void remoteFailureUsesTheSameInterruptibleBackoff()
            throws Exception {
        try (Fixture fixture = new Fixture(
                1,
                2,
                10,
                Duration.ofMillis(300)
        )) {
            fixture.peer.failures.set(1);
            fixture.startCommandLoop();
            await(() -> fixture.peer.requestedLimits.size() == 1);

            Thread.sleep(100);
            assertThat(fixture.peer.requestedLimits).hasSize(1);

            await(() -> fixture.peer.requestedLimits.size() == 2);
        }
    }

    @Test
    void stopAndInterruptCancelAnActiveHttpCall()
            throws Exception {
        try (Fixture fixture = new Fixture(
                1,
                2,
                10,
                Duration.ofSeconds(1)
        )) {
            fixture.peer.blockCommands();
            fixture.startCommandLoop();
            assertThat(fixture.peer.commandStarted.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            long started = System.nanoTime();
            fixture.stopCommandLoop();

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
            assertThat(fixture.network.writtenWorkerIds).isEmpty();
            fixture.peer.releaseCommand.countDown();
        }
    }

    @Test
    void expiredCommandCreatesTaskResultAndKernelEvidenceTogether()
            throws Exception {
        try (Fixture fixture = new Fixture(
                1,
                1,
                2,
                Duration.ofSeconds(1)
        )) {
            DeliveryCommand expired = command(1_000, "expired-context");
            fixture.peer.batches.add(Map.of("worker-1", expired));

            fixture.startCommandLoop();
            await(() -> fixture.peer.requestedLimits.size() >= 2);
            fixture.stopCommandLoop();
            fixture.flushReports();

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
    void adapterEventsAndWorkerCommandKeepTheirExistingDestinations()
            throws Exception {
        try (Fixture fixture = new Fixture(
                3,
                3,
                10,
                Duration.ofSeconds(1)
        )) {
            fixture.activate("worker-1");
            fixture.peer.batches.add(Map.of(
                    "first-opaque-entry",
                    systemCommand(
                            ADAPTER,
                            "platform.adapter.probe",
                            "null",
                            2_000
                    ),
                    "second-opaque-entry",
                    systemCommand(
                            ADAPTER,
                            AdapterEventDispatcher.EVENTS_SNAPSHOT_EVENT,
                            "null",
                            2_000
                    ),
                    "worker-1",
                    systemCommand(
                            WORKER,
                            "platform.worker.probe",
                            "null",
                            2_000
                    )
            ));

            fixture.startCommandLoop();
            await(() -> fixture.network.writtenWorkerIds.contains(
                    "worker-1"
            ));
            await(() -> fixture.peer.requestedLimits.size() >= 2);
            fixture.stopCommandLoop();
            fixture.flushReports();

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

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Condition did not become true");
    }

    private static boolean waitingForReportIngress(Thread loop) {
        return loop.getState() == Thread.State.WAITING;
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
        private Thread commandLoop;

        private Fixture(
                int consumeLimit,
                int commandCapacity,
                int reportCapacity,
                Duration backoff
        ) {
            reportProcess = new DeliveryReportProcess(
                    new DeliveryReportRemoteApi(httpClient),
                    "adapter-1",
                    reportCapacity,
                    Duration.ofSeconds(1)
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
                    backoff,
                    () -> 1_000
            );
        }

        private void startCommandLoop() {
            commandLoop = new Thread(
                    process::runLoop,
                    "command-loop-test"
            );
            commandLoop.start();
        }

        private void stopCommandLoop() throws InterruptedException {
            process.stop();
            Thread running = commandLoop;
            if (running != null) {
                running.interrupt();
                running.join(2_000);
                assertThat(running.isAlive()).isFalse();
                commandLoop = null;
            }
        }

        private void flushReports() throws InterruptedException {
            int previousAttempts = peer.reportAttempts.get();
            Thread loop = new Thread(
                    reportProcess::runLoop,
                    "report-loop-test"
            );
            loop.start();
            await(() -> peer.reportAttempts.get() > previousAttempts);
            await(() -> waitingForReportIngress(loop));
            loop.interrupt();
            loop.join(2_000);
            assertThat(loop.isAlive()).isFalse();
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
            await(() -> {
                channel.runPendingTasks();
                return connectionMechanism.connectionStates(
                        List.of(workerId)
                ).get(workerId) == WorkerConnectionState.CONNECTED;
            });
        }

        @Override
        public void close() throws Exception {
            stopCommandLoop();
            channels.forEach(EmbeddedChannel::finishAndReleaseAll);
            reportProcess.stop();
            peer.releaseCommand.countDown();
            peer.close();
        }
    }

    private static final class RemotePeer implements AutoCloseable {

        private final ConcurrentLinkedQueue<Map<String, DeliveryCommand>>
                batches = new ConcurrentLinkedQueue<>();
        private final List<Integer> requestedLimits =
                new CopyOnWriteArrayList<>();
        private final List<String> appendedReports =
                new CopyOnWriteArrayList<>();
        private final List<String> appendedSystemReports =
                new CopyOnWriteArrayList<>();
        private final AtomicInteger reportAttempts = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();
        private final CountDownLatch commandStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCommand = new CountDownLatch(1);
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );
        private volatile boolean commandsBlocked;

        private void blockCommands() {
            commandsBlocked = true;
        }

        private Response handle(ScriptedHttpServer.Request request)
                throws InterruptedException {
            if (request.rawPath().endsWith("/commands:consume")) {
                requestedLimits.add(Integer.parseInt(request.body()));
                if (commandsBlocked) {
                    commandStarted.countDown();
                    releaseCommand.await();
                }
                if (failures.getAndUpdate(value -> Math.max(0, value - 1))
                        > 0) {
                    return new Response(503, "{}");
                }
                return commandResponse(batches.poll());
            }
            if (request.rawPath().endsWith("/results:append")) {
                List<String> reports = Jsons.parseArray(request.body())
                        .stream()
                        .map(String.class::cast)
                        .toList();
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
                reportAttempts.incrementAndGet();
                return accepted(reports.size());
            }
            return new Response(204, "");
        }

        private static Response commandResponse(
                Map<String, DeliveryCommand> batch
        ) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            if (batch != null) {
                batch.forEach((target, command) -> encoded.put(
                        target,
                        Jsons.parseObject(CODEC.encodeDeliveryCommand(command))
                ));
            }
            return new Response(200, Jsons.toJson(encoded));
        }

        private static Response accepted(int count) {
            return new Response(202, Jsons.toJson(Map.of(
                    "acceptedCount", count,
                    "rejectedCount", 0
            )));
        }

        @Override
        public void close() {
            releaseCommand.countDown();
            server.close();
        }
    }

    private static final class FakeNetworkServer
            implements NettyWorkerServer {

        private final Map<Channel, String> workerIds =
                java.util.Collections.synchronizedMap(new IdentityHashMap<>());
        private final List<String> writtenWorkerIds =
                new CopyOnWriteArrayList<>();
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
