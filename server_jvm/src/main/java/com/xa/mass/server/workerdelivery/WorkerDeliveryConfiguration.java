package com.xa.mass.server.workerdelivery;

import com.xa.mass.kernel.delivery.SeedResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryAccessPolicy;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.server.workerdelivery.websocket.WorkerWebSocketProperties;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerWebSocketProperties.class)
public class WorkerDeliveryConfiguration {

    @Bean
    WorkerDeliveryCodec workerDeliveryCodec() {
        return new WorkerDeliveryCodec();
    }

    @Bean
    WorkerDeliveryAccessPolicy workerDeliveryAccessPolicy(
            WorkerWebSocketProperties properties
    ) {
        return new WorkerDeliveryAccessPolicy(
                properties.enabled() ? properties.endpointManagerId() : null
        );
    }

    @Bean
    WorkerDeliveryService workerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            SeedResultRuntime resultRuntime,
            WorkerDeliveryCodec codec
    ) {
        return new WorkerDeliveryService(
                commandRuntime,
                resultRuntime,
                codec
        );
    }
}
