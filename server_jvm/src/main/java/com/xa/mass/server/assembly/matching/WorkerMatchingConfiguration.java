package com.xa.mass.server.assembly.matching;

import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.FixedWindowPoolDefinition;
import com.xa.mass.workermatching.QualifiedCountryDefinition;
import com.xa.mass.workermatching.PartitionedLeasePoolDefinition;
import java.util.List;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.server.assembly.runtime.ServerWorkerAssemblyProperties;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import io.lettuce.core.RedisClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({MatchingProperties.class, ServerWorkerAssemblyProperties.class})
public class WorkerMatchingConfiguration {
    @Bean
    WorkerGroupBindings workerGroupBindings(MatchingProperties matching, ServerWorkerAssemblyProperties host,
                                           List<FixedWindowPoolDefinition> definitions) {
        return new WorkerGroupBindings(matching.groups(), host.fixedWindow(), definitions);
    }

    @Bean(destroyMethod="close")
    MatchingComposition matchingComposition(RedisClient client, XaMassRedisProperties redis, WorkerGroupBindings bindings,
            List<QualifiedCountryDefinition> qualifiedDefinitions,
            List<PartitionedLeasePoolDefinition> leaseDefinitions) {
        try {
            return MatchingComposition.create(client,redis.keyspace(),bindings.matchingGroups(),bindings.definitions(),qualifiedDefinitions, leaseDefinitions);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("operation=workerBindings.assemble xa.mass.worker-assembly.fixed-window / "
                    + "xa.mass.worker-matching: " + invalid.getMessage(), invalid);
        }
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
