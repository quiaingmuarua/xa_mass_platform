package com.xa.mass.server.workerdelivery;

import com.xa.mass.kernel.delivery.SeedResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.redis.RedisSeedResultRuntime;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerDeliveryOwnerAssemblyConfiguration {

    @Bean(destroyMethod = "close")
    WorkerCommandRuntime workerCommandRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            KernelRedisProperties properties
    ) {
        return new RedisWorkerCommandRuntime(
                redisClient,
                codec,
                properties.redisPrefix()
        );
    }

    @Bean(destroyMethod = "close")
    SeedResultRuntime seedResultRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            KernelRedisProperties properties
    ) {
        return new RedisSeedResultRuntime(
                redisClient,
                codec,
                properties.redisPrefix()
        );
    }
}
