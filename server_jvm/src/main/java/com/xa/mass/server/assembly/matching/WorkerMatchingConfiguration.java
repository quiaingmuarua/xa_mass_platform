package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.rules.MatchingStorage;
import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(MatchingProperties.class)
public class WorkerMatchingConfiguration {
    @Bean
    MatchingStorage matchingStorage(RedisClient client, XaMassRedisProperties redis) {
        return new MatchingStorage(client,redis.keyspace());
    }
    @Bean(destroyMethod="close")
    RedisWorkerMatchingCatalog workerMatchingCatalog(MatchingStorage storage, MatchingProperties config) {
        var catalog = MatchingComposition.create(storage,config.groups());
        try { catalog.rebuildIndexes(); return catalog; }
        catch (RuntimeException failure) { catalog.close(); throw failure; }
    }
}
