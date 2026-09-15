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
    RedisRuleStorage matchingRuleStorage(RedisClient client,XaMassRedisProperties redis) {
        return new RedisRuleStorage(client,redis.keyspace());
    }

    @Bean
    Map<String,RuleHandler> matchingRuleHandlers(RedisRuleStorage storage,MatchingRuleProperties rules) {
        return Map.of("worker.default",new DefaultRuleHandler(storage,rules.workerGroups()),
                "worker.country",new CountryRuleHandler(storage),
                "worker.messaging.available",new MessagingRuleHandler(storage),
                "proof.worker.facts",new ProofFactsRuleHandler(storage));
    }

    @Bean(destroyMethod="close")
    RedisWorkerMatchingCatalog workerMatchingCatalog(RedisRuleStorage storage,MatchingRuleProperties rules,
            @Qualifier("matchingRuleHandlers") Map<String,RuleHandler> handlers) {
        var catalog=new RedisWorkerMatchingCatalog(storage,handlers,rules.workerGroups(),rules.defaultRefillTargets());
        try { catalog.rebuildIndexes(); return catalog; }
        catch (RuntimeException failure) { catalog.close(); throw failure; }
    }
}
