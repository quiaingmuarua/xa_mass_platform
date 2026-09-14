package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.RuleHandler;
import com.xa.mass.workermatching.rules.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(MatchingRuleProperties.class)
public class WorkerMatchingConfiguration {
    @Bean
    Map<String,RuleHandler> matchingRuleHandlers() {
        return Map.of("worker.default",new DefaultRuleHandler(),
                "worker.country",new CountryRuleHandler(),
                "worker.messaging.available",new MessagingRuleHandler(),
                "proof.worker.facts",new ProofFactsRuleHandler());
    }

    @Bean(destroyMethod="close")
    RedisWorkerMatchingCatalog workerMatchingCatalog(RedisClient client,XaMassRedisProperties redis,MatchingRuleProperties rules,
            @Qualifier("matchingRuleHandlers") Map<String,RuleHandler> handlers) {
        var catalog=new RedisWorkerMatchingCatalog(client,redis.keyspace(),handlers,rules.workerGroups(),rules.defaultRefillTargets());
        try { catalog.rebuildIndexes(); return catalog; }
        catch (RuntimeException failure) { catalog.close(); throw failure; }
    }
}
