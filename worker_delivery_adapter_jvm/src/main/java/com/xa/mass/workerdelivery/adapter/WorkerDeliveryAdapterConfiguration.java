package com.xa.mass.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.http.HttpWorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketConfiguration;
import com.xa.mass.workerdelivery.adapter.websocket.WorkerWebSocketProperties;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerWebSocketProperties.class)
@ConditionalOnProperty(
        prefix = "xa.mass.worker-delivery.adapter.websocket",
        name = "enabled",
        havingValue = "true"
)
@Import(WorkerWebSocketConfiguration.class)
public class WorkerDeliveryAdapterConfiguration {

    @Bean
    @ConditionalOnMissingBean(WorkerDeliveryCodec.class)
    WorkerDeliveryCodec workerDeliveryAdapterCodec() {
        return new WorkerDeliveryCodec();
    }

    @Bean
    WorkerDeliveryGatewayClient workerDeliveryGatewayClient(
            WorkerWebSocketProperties properties,
            WorkerDeliveryCodec codec
    ) {
        return new HttpWorkerDeliveryGatewayClient(
                properties.gatewayBaseUrl(),
                properties.requestTimeout(),
                codec
        );
    }
}
