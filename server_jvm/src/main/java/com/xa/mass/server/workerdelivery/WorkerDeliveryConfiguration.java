package com.xa.mass.server.workerdelivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.server.workerdelivery.websocket.WorkerWebSocketProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerDeliveryConfiguration {

    @Bean
    WorkerDeliveryCodec workerDeliveryCodec() {
        return new WorkerDeliveryCodec();
    }

    @Bean
    WorkerDeliveryAccessPolicy workerDeliveryAccessPolicy(
            WorkerWebSocketProperties properties
    ) {
        return new WorkerDeliveryAccessPolicy(properties);
    }
}
