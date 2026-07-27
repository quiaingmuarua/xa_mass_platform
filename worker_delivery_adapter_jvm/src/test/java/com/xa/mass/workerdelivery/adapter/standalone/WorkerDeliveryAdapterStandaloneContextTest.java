package com.xa.mass.workerdelivery.adapter.standalone;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerDeliveryPump;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketConfiguration;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketHandler;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        classes = WorkerDeliveryAdapterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xa.mass.worker-delivery.adapter.websocket.enabled=true",
                "xa.mass.worker-delivery.adapter.websocket."
                        + "endpoint-manager-id=standalone-adapter",
                "xa.mass.worker-delivery.adapter.websocket."
                        + "gateway-base-url=http://127.0.0.1:1",
                "xa.mass.worker-delivery.adapter.websocket.pump-interval=1h"
        }
)
class WorkerDeliveryAdapterStandaloneContextTest {

    @Autowired
    private ApplicationContext context;

    @LocalServerPort
    private int port;

    @Test
    void startsOnlyTheWebSocketAdapterSurface() throws Exception {
        assertThat(context.getBeansOfType(
                WorkerDeliveryGatewayClient.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerWebSocketProperties.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerWebSocketConfiguration.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerWebSocketHandler.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerDeliveryPump.class
        )).hasSize(1);

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> health = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/actuator/health/liveness"
                        ))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(health.statusCode()).isEqualTo(200);

        HttpResponse<String> readiness = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/actuator/health/readiness"
                        ))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(readiness.statusCode()).isEqualTo(200);

        HttpResponse<String> gatewayRoute = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        + "/api/v1/worker-delivery/"
                                        + "endpoint-managers/adapter/"
                                        + "commands:consume"
                        ))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(gatewayRoute.statusCode()).isEqualTo(404);
    }
}
