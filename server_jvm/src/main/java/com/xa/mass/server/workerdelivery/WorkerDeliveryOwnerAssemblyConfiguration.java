package com.xa.mass.server.workerdelivery;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.redis.RedisTaskResultRuntime;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.redis.RedisWorkerServiceabilityRuntime;
import com.xa.mass.server.kernelredis.XaMassRedisProperties;
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
            XaMassRedisProperties properties
    ) {
        return new RedisWorkerCommandRuntime(
                redisClient,
                codec,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    TaskResultRuntime taskResultRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            XaMassRedisProperties properties
    ) {
        return new RedisTaskResultRuntime(
                redisClient,
                codec,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    WorkerServiceabilityRuntime workerServiceabilityRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            XaMassRedisProperties properties
    ) {
        return new RedisWorkerServiceabilityRuntime(
                redisClient,
                codec,
                properties.keyspace()
        );
    }
}
