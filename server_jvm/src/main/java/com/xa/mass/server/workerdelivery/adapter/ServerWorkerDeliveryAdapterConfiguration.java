package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.InMemoryWorkerConnectionRegistry;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnectionRegistry;
import com.xa.mass.workerdelivery.adapter.http.HttpWorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketEndpointConfigurer;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketHandler;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
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
        prefix = "xa.mass.worker-delivery.adapter.websocket",
        name = "enabled",
        havingValue = "true"
)
public class ServerWorkerDeliveryAdapterConfiguration {

    @Bean
    WorkerDeliveryGatewayClient workerDeliveryGatewayClient(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerDeliveryCodec codec
    ) {
        return new HttpWorkerDeliveryGatewayClient(
                properties.gatewayBaseUrl(),
                properties.requestTimeout(),
                codec
        );
    }

    @Bean
    WorkerConnectionRegistry workerConnectionRegistry() {
        return new InMemoryWorkerConnectionRegistry();
    }

    @Bean(destroyMethod = "")
    WorkerDeliveryAdapter workerDeliveryAdapter(
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec,
            WorkerConnectionRegistry connections,
            ServerWorkerDeliveryAdapterProperties properties
    ) {
        return new WorkerDeliveryAdapter(
                gateway,
                codec,
                connections,
                new WorkerDeliveryAdapter.Config(
                        properties.endpointManagerId(),
                        properties.scanCount(),
                        properties.resultBatchSize(),
                        properties.resultBufferCapacity()
                )
        );
    }

    @Bean
    WorkerWebSocketHandler workerWebSocketHandler(
            WorkerDeliveryCodec codec,
            WorkerDeliveryAdapter adapter,
            ServerWorkerDeliveryAdapterProperties properties
    ) {
        return new WorkerWebSocketHandler(
                codec,
                adapter,
                properties.sendTimeLimit()
        );
    }

    @Bean
    WorkerWebSocketEndpointConfigurer workerWebSocketEndpointConfigurer(
            WorkerWebSocketHandler handler
    ) {
        return new WorkerWebSocketEndpointConfigurer(handler);
    }

    @Bean
    WorkerDeliveryAdapterLoop workerDeliveryAdapterLoop(
            WorkerDeliveryAdapter adapter,
            ServerWorkerDeliveryAdapterProperties properties
    ) {
        return new WorkerDeliveryAdapterLoop(
                adapter,
                properties.pumpInterval()
        );
    }
}
