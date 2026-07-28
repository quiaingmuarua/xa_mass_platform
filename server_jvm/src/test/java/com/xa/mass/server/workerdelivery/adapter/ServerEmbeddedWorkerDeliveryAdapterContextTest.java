package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketHandler;
import com.xa.mass.workerdelivery.adapter.websocket.WebSocketWorkerDeliveryAdapterFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(
        properties = {
                "xa.mass.kernel.base-url=http://127.0.0.1:1",
                "xa.mass.kernel.connect-timeout=10ms",
                "xa.mass.kernel.read-timeout=10ms",
                "xa.mass.worker-delivery.adapter.enabled=true",
                "xa.mass.worker-delivery.adapter.type=WEBSOCKET",
                "xa.mass.worker-delivery.adapter.runtime.endpoint-manager-id=embedded-websocket",
                "xa.mass.worker-delivery.adapter.runtime.gateway-base-url=http://127.0.0.1:1",
                "xa.mass.worker-delivery.adapter.runtime.request-timeout=10ms",
                "xa.mass.worker-delivery.adapter.runtime.dispatch-interval=1h"
        }
)
class ServerEmbeddedWorkerDeliveryAdapterContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void serverCanHostTheConcreteWebSocketAdapter() {
        assertThat(applicationContext.getBean(
                WorkerDeliveryAdapterManager.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                WorkerWebSocketHandler.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                WorkerDeliveryAdapterLifecycleHost.class
        )).isNotNull();
        assertThat(applicationContext.getBean(
                WebSocketWorkerDeliveryAdapterFactory.class
        )).isNotNull();
    }
}
