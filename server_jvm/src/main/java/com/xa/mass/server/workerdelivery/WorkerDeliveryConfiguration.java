package com.xa.mass.server.workerdelivery;

import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WorkerDeliveryConfiguration {

    @Bean
    WorkerDeliveryCodec workerDeliveryCodec(ObjectMapper objectMapper) {
        return new WorkerDeliveryCodec(objectMapper);
    }
}
