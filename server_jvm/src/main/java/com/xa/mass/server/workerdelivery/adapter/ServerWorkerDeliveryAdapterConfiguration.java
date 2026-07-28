package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterFactory;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketEndpointConfigurer;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketHandler;
import com.xa.mass.workerdelivery.adapter.websocket.WebSocketWorkerDeliveryAdapterFactory;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(
        ServerWorkerDeliveryAdapterProperties.class
)
@ConditionalOnProperty(
        prefix = "xa.mass.worker-delivery.adapter",
        name = "enabled",
        havingValue = "true"
)
public class ServerWorkerDeliveryAdapterConfiguration {

    @Bean
    WebSocketWorkerDeliveryAdapterFactory
    webSocketWorkerDeliveryAdapterFactory(
            WorkerDeliveryCodec codec
    ) {
        return new WebSocketWorkerDeliveryAdapterFactory(codec);
    }

    @Bean
    WorkerDeliveryAdapterManager workerDeliveryAdapterManager(
            List<WorkerDeliveryAdapterFactory<?>> factories,
            ServerWorkerDeliveryAdapterProperties properties
    ) {
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager(factories);
        manager.register(properties.definition());
        return manager;
    }

    @Bean
    WorkerWebSocketHandler workerWebSocketHandler(
            WebSocketWorkerDeliveryAdapterFactory factory,
            WorkerDeliveryAdapterManager manager
    ) {
        return factory.handler();
    }

    @Bean
    WorkerWebSocketEndpointConfigurer workerWebSocketEndpointConfigurer(
            WorkerWebSocketHandler handler
    ) {
        return new WorkerWebSocketEndpointConfigurer(handler);
    }

    @Bean
    WorkerDeliveryAdapterLifecycleHost
    workerDeliveryAdapterLifecycleHost(
            WorkerDeliveryAdapterManager manager
    ) {
        return new WorkerDeliveryAdapterLifecycleHost(manager);
    }
}
