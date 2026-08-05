package com.xa.mass.server.workeridentity;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerIdentityConfiguration {

    @Bean(destroyMethod = "close")
    WorkerIdentityRegistry workerIdentityRegistry(
            RedisClient redisClient,
            KernelRedisProperties redisProperties
    ) {
        return new RedisWorkerIdentityRegistry(
                redisClient,
                redisProperties.redisPrefix()
        );
    }

    @Bean
    WorkerIdentityService workerIdentityService(
            WorkerIdentityRegistry registry,
            WorkerResourceCatalog workerCatalog
    ) {
        return new WorkerIdentityService(registry, workerCatalog);
    }
}
