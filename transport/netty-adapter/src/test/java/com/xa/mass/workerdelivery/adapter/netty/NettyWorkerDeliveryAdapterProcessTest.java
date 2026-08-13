package com.xa.mass.workerdelivery.adapter.netty;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.AFTER_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.BEFORE_NETWORK_CLOSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerRouteRegistry;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.AdapterConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.TextWriteAttempt;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.ScheduledAdapterProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.DeliveryReportRemoteApi;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerRouteRemoteApi;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NettyWorkerDeliveryAdapterProcessTest {

    @Test
    void quiescesByPhaseAndFinishesInReverseRegistrationOrder() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingProcess command = new RecordingProcess("command", events);
        RecordingProcess report = new RecordingProcess("report", events);
        RecordingNetworkServer network = new RecordingNetworkServer(events);
        try (ScriptedHttpServer http = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            NettyWorkerDeliveryAdapter adapter = adapter(
                    network,
                    http,
                    Duration.ofSeconds(1),
                    List.of(
                            scheduled("command", BEFORE_NETWORK_CLOSE, command),
                            scheduled("report", AFTER_NETWORK_CLOSE, report)
                    )
            );

            adapter.start();
            assertThat(network.startedHandler)
                    .isInstanceOf(WorkerConnectionInboundHandler.class)
                    .isNotInstanceOf(WorkerConnectionMechanism.class);
            adapter.close();

            assertThat(events).containsSubsequence(
                    "quiesce-command",
                    "network-close",
                    "quiesce-report",
                    "finish-report",
                    "finish-command"
            );
        }
    }

    @Test
    void schedulerTimeoutSkipsAllFinishHooks() throws Exception {
        StubbornProcess stubborn = new StubbornProcess();
        RecordingNetworkServer network = new RecordingNetworkServer(
                new CopyOnWriteArrayList<>()
        );
        try (ScriptedHttpServer http = new ScriptedHttpServer(
                request -> new Response(204, "")
        )) {
            NettyWorkerDeliveryAdapter adapter = adapter(
                    network,
                    http,
                    Duration.ofMillis(40),
                    List.of(scheduled(
                            "stubborn",
                            BEFORE_NETWORK_CLOSE,
                            stubborn
                    ))
            );
            adapter.start();
            assertThat(stubborn.started.await(2, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            try {
                assertThatThrownBy(adapter::close)
                        .isInstanceOfSatisfying(
                                WorkerDeliveryAdapterException.class,
                                failure -> {
                                    assertThat(failure.errorCode()).isEqualTo(
                                            WorkerDeliveryAdapterErrorCode
                                                    .SHUTDOWN_TIMEOUT
                                    );
                                    assertThat(failure.operation())
                                            .isEqualTo(
                                                    "adapterProcess."
                                                            + "stopScheduler"
                                            );
                                }
                        );
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(Duration.ofSeconds(1));
                assertThat(stubborn.finishCalls).isZero();
            } finally {
                stubborn.release.countDown();
                adapter.close();
            }
        }
    }

    private static NettyWorkerDeliveryAdapter adapter(
            RecordingNetworkServer network,
            ScriptedHttpServer http,
            Duration shutdownTimeout,
            List<ScheduledAdapterProcess> processes
    ) {
        WorkerDeliveryHttpClient client = new WorkerDeliveryHttpClient(
                http.baseUri(),
                Duration.ofSeconds(2)
        );
        DeliveryReportProcess reports = new DeliveryReportProcess(
                new DeliveryReportRemoteApi(client),
                "adapter-1",
                10
        );
        WorkerConnectionMechanism connection = new WorkerConnectionMechanism(
                new WorkerRouteRegistry(),
                network,
                new WorkerRouteRemoteApi(client),
                new WorkerDeliveryCodec(),
                reports,
                "adapter-1",
                Duration.ofSeconds(1)
        );
        WorkerConnectionInboundHandler inboundHandler =
                new WorkerConnectionInboundHandler(connection);
        return new NettyWorkerDeliveryAdapter(
                "adapter-1",
                network,
                inboundHandler,
                connection,
                new AdapterProcessManager(
                        "adapter-1",
                        shutdownTimeout,
                        processes
                )
        );
    }

    private static ScheduledAdapterProcess scheduled(
            String id,
            com.xa.mass.workerdelivery.adapter.netty.internal.process
                    .QuiescePhase phase,
            AdapterProcess process
    ) {
        return new ScheduledAdapterProcess(
                id,
                Duration.ZERO,
                Duration.ofMillis(5),
                phase,
                process
        );
    }

    private static final class RecordingProcess implements AdapterProcess {

        private final String id;
        private final List<String> events;

        private RecordingProcess(String id, List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public void round() {
        }

        @Override
        public void quiesce() {
            events.add("quiesce-" + id);
        }

        @Override
        public void finishAfterSchedulerStop() {
            events.add("finish-" + id);
        }
    }

    private static final class StubbornProcess implements AdapterProcess {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int finishCalls;

        @Override
        public void round() {
            started.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void finishAfterSchedulerStop() {
            finishCalls++;
        }
    }

    private static final class RecordingNetworkServer
            implements NettyWorkerServer {

        private final List<String> events;
        private volatile ChannelHandler startedHandler;

        private RecordingNetworkServer(List<String> events) {
            this.events = events;
        }

        @Override
        public void start(ChannelHandler sharedConnectionHandler) {
            startedHandler = sharedConnectionHandler;
            events.add("network-start");
        }

        @Override
        public TextWriteAttempt writeText(Channel channel, String message) {
            return TextWriteAttempt.RETRY_LATER;
        }

        @Override
        public void writeTextAndClose(
                Channel channel,
                String message,
                AdapterConnectionCloseReason reason
        ) {
        }

        @Override
        public void closeConnection(
                Channel channel,
                AdapterConnectionCloseReason reason
        ) {
        }

        @Override
        public void close() {
            events.add("network-close");
        }
    }
}
