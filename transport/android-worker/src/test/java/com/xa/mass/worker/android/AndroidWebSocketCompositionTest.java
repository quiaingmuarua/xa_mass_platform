package com.xa.mass.worker.android;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.worker.runtime.PreparedWorker;
import com.xa.mass.worker.runtime.WorkerPreparation;
import com.xa.mass.worker.runtime.WorkerRunController;
import com.xa.mass.worker.runtime.TextMessageWorkerTransportFactory;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AndroidWebSocketCompositionTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    @Test
    public void completesIdentifyCommandAndResultRoundTrip()
            throws Exception {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        DeliveryCommand command = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "test.observe",
                System.currentTimeMillis() + 30_000,
                "{\"value\":\"visible\"}",
                "result-context"
        );
        DeliveryReport expectedResult = DeliveryReport.fromCommand(
                command,
                WORKER,
                WORKER_ID,
                "200",
                "{\"observed\":\"visible\"}"
        );

        CountDownLatch resultReceived = new CountDownLatch(1);
        AtomicReference<DeliveryReport> identity =
                new AtomicReference<>();
        AtomicReference<DeliveryReport> result =
                new AtomicReference<>();
        WebSocketListener serverListener = new WebSocketListener() {
            @Override
            public void onMessage(
                    WebSocket webSocket,
                    String text
            ) {
                if (identity.get() == null) {
                    identity.set(codec.decodeDeliveryReport(text));
                    webSocket.send(codec.encodeDeliveryCommand(command));
                    return;
                }
                result.set(codec.decodeDeliveryReport(text));
                resultReceived.countDown();
            }
        };

        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(
                    new MockResponse.Builder()
                            .webSocketUpgrade(serverListener)
                            .build()
            );
            server.start();
            URI socketUri = URI.create(
                    server.url("/api/v1/worker-delivery/websocket")
                            .toString()
                            .replaceFirst("^http", "ws")
            );
            WorkerPreparation preparation = new WorkerPreparation() {
                @Override
                public PreparedWorker prepare() {
                    return new PreparedWorker(WORKER_ID, socketUri);
                }

                @Override
                public void close() {
                }
            };
            TextMessageReconnectPolicy connectionPolicy =
                    TextMessageReconnectPolicy.of(
                            20,
                            Duration.ofMillis(20),
                            Duration.ofMillis(100)
                    );
            AndroidWorkerPlatform platform =
                    AndroidWorkerPlatform.create(
                            "android-composition-test"
            );
            WorkerCommandDispatcher dispatcher =
                    WorkerCommandDispatcher.forWorker(List.of(
                            WorkerEventDefinition.of(
                                    "TASK",
                                    "test.observe",
                                    WorkerEventParameterResolvers
                                            .jsonMap(),
                                    parameters -> Jsons.toJson(Map.of(
                                            "observed",
                                            parameters.get("value")
                                    ))
                            )
                    ));
            WorkerRunController worker = new WorkerRunController(
                    preparation,
                    new TextMessageWorkerTransportFactory(
                            endpoint -> platform.textClient(
                                    endpoint,
                                    Duration.ofSeconds(2),
                                    connectionPolicy
                            ),
                            dispatcher
                    ),
                    platform.controlExecutor()
            );
            try {
                worker.start();
                assertTrue(resultReceived.await(
                        5,
                        TimeUnit.SECONDS
                ));
            } finally {
                worker.close();
                platform.close();
            }
        }

        assertNotNull(identity.get());
        assertEquals(WORKER, identity.get().src());
        assertEquals(WORKER_ID, identity.get().sourceId());
        assertEquals(ADAPTER, identity.get().dst());
        assertEquals(
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                identity.get().messageType()
        );
        assertEquals("200", identity.get().outcomeCode());
        assertEquals("null", identity.get().payload());
        assertEquals("", identity.get().forward());
        assertEquals(expectedResult, result.get());
    }
}
