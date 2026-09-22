package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerProperties;
import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(MatchingProperties.class)
public class WorkerMatchingConfiguration {
    @Bean(destroyMethod="close")
    MatchingComposition matchingComposition(RedisClient client, XaMassRedisProperties redis, MatchingProperties config) {
        return MatchingComposition.create(client,redis.keyspace(),config.groups());
    }

    @Bean(destroyMethod="")
    WorkerMatchingCatalog workerMatchingCatalog(MatchingComposition composition) {
        return composition.catalog();
    }

    @Bean(destroyMethod="")
    WorkerProperties workerProperties(MatchingComposition composition) {
        return composition.properties();
    }
}
