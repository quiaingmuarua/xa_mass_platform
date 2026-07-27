package com.xa.mass.server.workerdelivery.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerDeliveryRedisConfiguration {

    @Bean(destroyMethod = "shutdown")
    RedisClient workerDeliveryRedisClient(
            WorkerDeliveryRedisProperties properties
    ) {
        return RedisClient.create(
                RedisURI.create(properties.redisUrl())
        );
    }
}
