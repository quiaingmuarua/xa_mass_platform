package com.xa.mass.integration.androidworkerproof;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonHttpClientTest {
    @Test void explicitCallerReachesMessagesButIsNotInventedOrForwardedToDeviceHost() throws Exception {
        var seen = new CopyOnWriteArrayList<List<String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(Arrays.asList(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-XA-Dev-User"),
                    exchange.getRequestHeaders().getFirst("X-XA-Dev-Role")));
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var headers = new HashMap<>(Map.of("X-XA-Dev-User", "MTAwMQ", "X-XA-Dev-Role", "operator"));
            var client = new JsonHttpClient(base, Duration.ofSeconds(2), headers);
            headers.put("X-XA-Dev-User", "MTAwMg");
            client.send("POST", "/api/v1/messages/tasks", Map.of(), "create");
            client.sendText("POST", "/api/v1/messages/tasks/known/recipients:import", "fixture", "text/plain", "import");
            client.send("GET", "/api/v1/messages/tasks/known/results", null, "results");
            client.send("GET", "/events", null, "device");
            new JsonHttpClient(base, Duration.ofSeconds(2), Map.of())
                    .send("GET", "/api/v1/messages/catalog", null, "anonymous");
            for (int i = 0; i < 3; i++) assertEquals(List.of("MTAwMQ", "operator"), seen.get(i).subList(1, 3));
            for (int i = 3; i < 5; i++) assertEquals(Arrays.asList(null, null), seen.get(i).subList(1, 3));
        } finally { server.stop(0); }
    }
}
