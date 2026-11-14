package com.xa.mass.server.worker.connection;

import tools.jackson.databind.ObjectMapper;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerConnectionControlConfiguration {
    @Bean(destroyMethod = "close")
    WorkerConnectionControlStore workerConnectionControlStore(RedisClient client, XaMassRedisProperties properties, ObjectMapper json) {
        return new WorkerConnectionControlStore(client, properties.keyspace(), json);
    }
}
