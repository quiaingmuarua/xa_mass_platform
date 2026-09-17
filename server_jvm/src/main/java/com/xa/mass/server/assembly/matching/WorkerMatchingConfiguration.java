package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(MatchingProperties.class)
public class WorkerMatchingConfiguration {
    @Bean(destroyMethod="close")
    RedisWorkerMatchingCatalog workerMatchingCatalog(RedisClient client, XaMassRedisProperties redis, MatchingProperties config) {
        return MatchingComposition.create(client,redis.keyspace(),config.groups());
    }
}
