package com.xa.mass.server.assembly.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(XaMassRedisProperties.class)
public class KernelRedisConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedisClient kernelRedisClient(XaMassRedisProperties properties) {
        return RedisClient.create(RedisURI.create(properties.url()));
    }

    @Bean
    public KernelRedisHealthIndicator kernelRedisHealthIndicator(
            RedisClient redisClient
    ) {
        return new KernelRedisHealthIndicator(redisClient);
    }
}
