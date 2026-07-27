package com.xa.mass.server.kernelredis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KernelRedisConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedisClient kernelRedisClient(KernelRedisProperties properties) {
        return RedisClient.create(RedisURI.create(properties.redisUrl()));
    }
}
