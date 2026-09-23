package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.*;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.Test;

class JavaWorkerReplicaDefinitionsTest {
    @Test void transportExecutesTheActualReplicasImmutableDefinition() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var results = new LinkedBlockingQueue<DeliveryReport>();
            var codec = new WorkerDeliveryCodec();
            var first = new ArrayList<WorkerEventDefinition<?>>(List.of(definition("first")));
            var builder = JavaWorkerManager.builder(URI.create(server.url("/").toString()), "group", WorkerTransportType.WEBSOCKET)
                    .replica("first", Map::of, first)
                    .replica("second", Map::of, List.of(definition("second")));
            first.clear(); // Caller mutation must not alter the assembled first replica.
            try (var manager = builder.build()) {
                for (String key : List.of("first", "second")) {
                    String endpoint = server.url("/worker").toString().replaceFirst("^http", "ws");
                    server.enqueue(new MockResponse.Builder().code(200).body("{\"workerId\":\"" + key
                            + "\",\"transportType\":\"WEBSOCKET\",\"endpointUri\":\"" + endpoint + "\"}").build());
                    server.enqueue(new MockResponse.Builder().webSocketUpgrade(new okhttp3.WebSocketListener() {
                        @Override public void onMessage(okhttp3.WebSocket socket, String message) {
                            var report = codec.decodeDeliveryReport(message);
                            if (report.dst() == DeliveryEndpoint.ADAPTER) {
                                socket.send(codec.encodeDeliveryCommand(DeliveryCommand.create(DeliveryEndpoint.SERVER,
                                        DeliveryEndpoint.WORKER, "extension.worker.replica.witness", System.currentTimeMillis() + 5_000,
                                        "{}", "correlation")));
                            } else results.add(report);
                        }
                        @Override public void onClosing(okhttp3.WebSocket socket, int code, String reason) { socket.close(code, reason); }
                    }).build());
                    manager.start(key);
                    var result = results.poll(5, TimeUnit.SECONDS);
                    assertNotNull(result);
                    assertEquals(key, result.sourceId());
                    assertEquals("\"" + key + "\"", result.payload());
                }
            }
        }
    }

    @Test void commonAndReplicaDuplicateNamesAreRejected() {
        var builder = JavaWorkerManager.builder(URI.create("http://127.0.0.1:1"), "group", WorkerTransportType.WEBSOCKET)
                .extendEventDefinitions(List.of(definition("common")))
                .replica("first", Map::of, List.of(definition("local")));
        assertThrows(IllegalArgumentException.class, builder::build);
    }
    private static WorkerEventDefinition<?> definition(String key) {
        return WorkerEventDefinition.extension("replica.witness", WorkerEventParameterResolvers.jsonMap(), ignored -> "\"" + key + "\"");
    }
}
