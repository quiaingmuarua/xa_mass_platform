package com.xa.mass.server.worker.binding;

import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.server.worker.identity.WorkerIdentityService;
import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerBindingProperties.class)
public class WorkerBindingConfiguration {

    @Bean
    WorkerEndpointDirectory workerEndpointDirectory(
            WorkerBindingProperties properties
    ) {
        return new WorkerEndpointDirectory(properties.endpoints());
    }

    @Bean(destroyMethod = "close")
    WorkerBindingRegistry workerBindingRegistry(
            RedisClient redisClient,
            XaMassRedisProperties redisProperties
    ) {
        return new RedisWorkerBindingRegistry(
                redisClient,
                redisProperties.keyspace()
        );
    }

    @Bean
    WorkerBindingService workerBindingService(
            WorkerBindingRegistry registry,
            WorkerEndpointDirectory endpoints,
            WorkerIdentityService identities,
            WorkerRuntime workerRuntime
    ) {
        return new WorkerBindingService(
                registry,
                endpoints,
                identities,
                workerRuntime
        );
    }
}
