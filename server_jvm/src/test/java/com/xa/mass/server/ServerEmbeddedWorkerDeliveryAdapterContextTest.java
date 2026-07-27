package com.xa.mass.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.http.HttpWorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerDeliveryPump;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "xa.mass.kernel.base-url=http://127.0.0.1:1",
                "xa.mass.kernel.connect-timeout=10ms",
                "xa.mass.kernel.read-timeout=10ms",
                "xa.mass.worker-delivery.adapter.websocket.enabled=true",
                "xa.mass.worker-delivery.adapter.websocket."
                        + "endpoint-manager-id=embedded-adapter",
                "xa.mass.worker-delivery.adapter.websocket."
                        + "gateway-base-url=http://127.0.0.1:1",
                "xa.mass.worker-delivery.adapter.websocket.pump-interval=1h"
        }
)
class ServerEmbeddedWorkerDeliveryAdapterContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void explicitlyImportsTheHttpBackedAdapter() {
        assertThat(context.getBeansOfType(
                WorkerDeliveryGatewayClient.class
        )).hasSize(1);
        assertThat(context.getBean(
                WorkerDeliveryGatewayClient.class
        )).isInstanceOf(HttpWorkerDeliveryGatewayClient.class);
        assertThat(context.getBeansOfType(
                WorkerWebSocketHandler.class
        )).hasSize(1);
        assertThat(context.getBeansOfType(
                WorkerDeliveryPump.class
        )).hasSize(1);
    }
}
