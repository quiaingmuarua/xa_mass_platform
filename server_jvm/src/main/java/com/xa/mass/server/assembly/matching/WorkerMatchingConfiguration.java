package com.xa.mass.server.assembly.matching;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingRuntime;
import io.lettuce.core.RedisClient;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkerMatchingConfiguration {

    @Bean(destroyMethod = "close")
    RedisWorkerMatchingCatalog workerMatchingCatalog(
            RedisClient redisClient,
            XaMassRedisProperties redisProperties
    ) {
        return new RedisWorkerMatchingCatalog(
                redisClient,
                redisProperties.keyspace()
        );
    }

    @Bean
    WorkerMatchingRuntime workerMatchingRuntime(
            WorkerMatchingCatalog catalog,
            CandidateWorkerCache candidateCache
    ) {
        return new WorkerMatchingRuntime(catalog, candidateCache);
    }

    @Bean
    WorkerMatchingAssembly workerMatchingAssembly(
            WorkerMatchingRuntime runtime
    ) {
        return new WorkerMatchingAssembly(runtime);
    }

    @Bean("workerMatching")
    HealthIndicator workerMatchingHealthIndicator(
            WorkerMatchingAssembly assembly
    ) {
        return new WorkerMatchingHealthIndicator(assembly);
    }
}
