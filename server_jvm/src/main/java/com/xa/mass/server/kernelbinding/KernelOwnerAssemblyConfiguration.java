package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.assignment.redis.RedisCandidateWorkerCache;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.DefaultTaskCallItemSubmission;
import com.xa.mass.kernel.task.DefaultTaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.server.kernelredis.XaMassRedisProperties;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KernelOwnerAssemblyConfiguration {

    @Bean(destroyMethod = "close")
    RedisCandidateWorkerCache redisCandidateWorkerCache(
            RedisClient redisClient,
            XaMassRedisProperties properties
    ) {
        return new RedisCandidateWorkerCache(
                redisClient,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisTaskScoreBandCore redisTaskScoreBandCore(
            RedisClient redisClient,
            XaMassRedisProperties properties
    ) {
        return new RedisTaskScoreBandCore(
                redisClient,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisTaskItemScoreBandCore redisTaskItemScoreBandCore(
            RedisClient redisClient,
            XaMassRedisProperties properties
    ) {
        return new RedisTaskItemScoreBandCore(
                redisClient,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisTaskRuntime redisTaskRuntime(
            RedisClient redisClient,
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore taskItemScore,
            XaMassRedisProperties properties
    ) {
        return new RedisTaskRuntime(
                redisClient,
                taskScore,
                taskItemScore,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisTaskResourceCatalog taskResourceCatalog(
            RedisClient redisClient,
            XaMassRedisProperties properties
    ) {
        return new RedisTaskResourceCatalog(
                redisClient,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisWorkerScoreCore redisWorkerScoreCore(
            RedisClient redisClient,
            XaMassRedisProperties properties
    ) {
        return new RedisWorkerScoreCore(
                redisClient,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisWorkerRuntime workerRuntime(
            RedisClient redisClient,
            RedisWorkerScoreCore scoreCore,
            XaMassRedisProperties properties
    ) {
        return new RedisWorkerRuntime(
                redisClient,
                scoreCore,
                properties.keyspace()
        );
    }

    @Bean(destroyMethod = "close")
    RedisWorkerResourceCatalog workerResourceCatalog(
            RedisClient redisClient,
            XaMassRedisProperties properties
    ) {
        return new RedisWorkerResourceCatalog(
                redisClient,
                properties.keyspace()
        );
    }

    @Bean
    TaskLifecycleCommands taskLifecycleCommands(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog
    ) {
        return new DefaultTaskLifecycleCommands(taskScore, taskCatalog);
    }

    @Bean
    TaskCallItemSubmission taskCallItemSubmission(
            TaskScoreBandCore taskScore,
            TaskRuntime taskRuntime
    ) {
        return new DefaultTaskCallItemSubmission(taskScore, taskRuntime);
    }

}
