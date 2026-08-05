package com.xa.mass.transport.android.websocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.transport.websocket.WebSocketWorkerTransport;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
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

    private static final String MESSAGE_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    @Test
    public void completesBindCommandAndResultRoundTrip()
            throws Exception {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        WorkerCommand command = new WorkerCommand(
                MESSAGE_ID,
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                "test.observe",
                System.currentTimeMillis() + 30_000,
                "{\"value\":\"visible\"}",
                "result-context"
        );
        WorkerResult expectedResult = new WorkerResult(
                MESSAGE_ID,
                WorkerMessageEndpoint.TASK,
                "test.observe",
                "200",
                "{\"observed\":\"visible\"}",
                "result-context"
        );

        CountDownLatch resultReceived = new CountDownLatch(1);
        AtomicReference<WorkerConnectionBind> bind =
                new AtomicReference<>();
        AtomicReference<WorkerResult> result =
                new AtomicReference<>();
        WebSocketListener serverListener = new WebSocketListener() {
            @Override
            public void onMessage(
                    WebSocket webSocket,
                    String text
            ) {
                if (bind.get() == null) {
                    bind.set(codec.decodeWorkerConnectionBind(text));
                    webSocket.send(codec.encodeWorkerCommand(command));
                    return;
                }
                result.set(codec.decodeWorkerResult(text));
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
            TextWebSocketClient client =
                    new AndroidOkHttpTextWebSocketClient(
                            socketUri,
                            Duration.ofSeconds(2),
                            Duration.ofMillis(20)
                    );
            WebSocketWorkerTransport worker =
                    new WebSocketWorkerTransport(
                            client,
                            MESSAGE_ID,
                            List.of(WorkerEventDefinition.of(
                                    "TASK",
                                    "test.observe",
                                    WorkerEventParameterResolvers.jsonMap(),
                                    parameters -> Jsons.toJson(Map.of(
                                            "observed",
                                            parameters.get("value")
                                    ))
                            ))
                    );
            try {
                worker.start();
                assertTrue(resultReceived.await(
                        5,
                        TimeUnit.SECONDS
                ));
            } finally {
                worker.close();
            }
        }

        assertNotNull(bind.get());
        assertEquals(MESSAGE_ID, bind.get().workerId());
        assertEquals(expectedResult, result.get());
    }
}
