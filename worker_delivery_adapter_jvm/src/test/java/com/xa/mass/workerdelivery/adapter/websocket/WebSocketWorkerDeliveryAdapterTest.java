package com.xa.mass.workerdelivery.adapter.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerCommandPage;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WebSocketWorkerDeliveryAdapterTest {

    @Test
    void twoAdaptersOwnDistinctPortsAndIsolateTheSameWorkerId()
            throws Exception {
        int firstPort = availablePort();
        int secondPort = availablePort();
        while (secondPort == firstPort) {
            secondPort = availablePort();
        }
        FakeGateway firstGateway = new FakeGateway();
        FakeGateway secondGateway = new FakeGateway();
        WebSocketWorkerDeliveryAdapter first = adapter(
                "websocket-1",
                firstPort,
                firstGateway
        );
        WebSocketWorkerDeliveryAdapter second = adapter(
                "websocket-2",
                secondPort,
                secondGateway
        );
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(first);
        manager.register(second);
        manager.start();

        Probe firstProbe = new Probe();
        Probe secondProbe = new Probe();
        WebSocket firstSocket = connect(firstPort, "worker-1", firstProbe);
        WebSocket secondSocket = connect(
                secondPort,
                "worker-1",
                secondProbe
        );
        try {
            assertThat(firstProbe.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(secondProbe.opened.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            WorkerCommandEnvelope firstCommand = command(
                    "a5e9e10d-f78b-469e-93ab-864b49c189c1"
            );
            WorkerCommandEnvelope secondCommand = command(
                    "9f0d983c-8010-4d59-a6d2-e8fedb8d0059"
            );
            firstGateway.pages.add(new WorkerCommandPage(
                    Map.of("worker-1", firstCommand),
                    null
            ));
            secondGateway.pages.add(new WorkerCommandPage(
                    Map.of("worker-1", secondCommand),
                    null
            ));

            assertThat(firstProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            assertThat(secondProbe.message.await(
                    3,
                    TimeUnit.SECONDS
            )).isTrue();
            WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
            assertThat(codec.decodeWorkerCommand(
                    firstProbe.messages.getFirst()
            )).isEqualTo(firstCommand);
            assertThat(codec.decodeWorkerCommand(
                    secondProbe.messages.getFirst()
            )).isEqualTo(secondCommand);
            assertThat(firstGateway.endpointManagerIds)
                    .containsOnly("websocket-1");
            assertThat(secondGateway.endpointManagerIds)
                    .containsOnly("websocket-2");
        } finally {
            firstSocket.abort();
            secondSocket.abort();
            manager.close();
        }

        assertThat(first.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
        assertThat(second.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    @Test
    void secondPortConflictRollsBackEveryRegisteredAdapter() {
        int port = availablePort();
        WebSocketWorkerDeliveryAdapter first = adapter(
                "websocket-1",
                port,
                new FakeGateway()
        );
        WebSocketWorkerDeliveryAdapter second = adapter(
                "websocket-2",
                port,
                new FakeGateway()
        );
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        manager.register(first);
        manager.register(second);

        assertThatThrownBy(manager::start)
                .isInstanceOf(WorkerDeliveryAdapterException.class)
                .hasMessageContaining("websocket-2");

        assertThat(first.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
        assertThat(second.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
    }

    private static WebSocketWorkerDeliveryAdapter adapter(
            String adapterId,
            int port,
            WorkerDeliveryGatewayClient gateway
    ) {
        return new WebSocketWorkerDeliveryAdapter(
                adapterId,
                gateway,
                new WorkerDeliveryCodec(),
                "127.0.0.1",
                port,
                Duration.ofMillis(10),
                100,
                4,
                100,
                1000,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1)
        );
    }

    private static WorkerCommandEnvelope command(String commandId) {
        return new WorkerCommandEnvelope(
                commandId,
                WorkerMessageType.TASK_ITEM,
                System.currentTimeMillis() + 60_000,
                "opaque"
        );
    }

    private static WebSocket connect(
            int port,
            String workerId,
            Probe probe
    ) throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:" + port
                                        + WorkerWebSocketHandler
                                                .WORKER_PATH_PREFIX
                                        + workerId
                        ),
                        probe
                )
                .get(2, TimeUnit.SECONDS);
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not reserve a test port",
                    error
            );
        }
    }

    private static final class FakeGateway
            implements WorkerDeliveryGatewayClient {

        private final ConcurrentLinkedQueue<WorkerCommandPage> pages =
                new ConcurrentLinkedQueue<>();
        private final List<String> endpointManagerIds =
                new CopyOnWriteArrayList<>();

        @Override
        public WorkerCommandPage consumeWorkerCommands(
                String endpointManagerId,
                String cursor,
                int scanCount
        ) {
            endpointManagerIds.add(endpointManagerId);
            WorkerCommandPage page = pages.poll();
            return page == null
                    ? new WorkerCommandPage(Map.of(), null)
                    : page;
        }

        @Override
        public void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            endpointManagerIds.add(endpointManagerId);
        }
    }

    private static final class Probe implements WebSocket.Listener {

        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch message = new CountDownLatch(1);
        private final List<String> messages = new ArrayList<>();
        private final StringBuilder fragments = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            opened.countDown();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            fragments.append(data);
            if (last) {
                messages.add(fragments.toString());
                fragments.setLength(0);
                message.countDown();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
