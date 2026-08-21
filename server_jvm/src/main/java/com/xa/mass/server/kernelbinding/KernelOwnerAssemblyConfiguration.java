package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.DefaultTaskCallItemSubmission;
import com.xa.mass.kernel.task.DefaultTaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import io.lettuce.core.RedisClient;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class KernelOwnerAssemblyConfiguration {

    @Bean
    RestClient pythonKernelRestClient(PythonKernelProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    PythonKernelHealthClient pythonKernelHealthClient(
            RestClient pythonKernelRestClient
    ) {
        return new PythonKernelHealthClient(pythonKernelRestClient);
    }

    @Bean(destroyMethod = "close")
    RedisTaskScoreBandCore redisTaskScoreBandCore(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisTaskScoreBandCore(
                redisClient,
                properties.redisPrefix()
        );
    }

    @Bean(destroyMethod = "close")
    RedisTaskRuntime redisTaskRuntime(
            RedisClient redisClient,
            TaskScoreBandCore taskScore,
            KernelRedisProperties properties
    ) {
        return new RedisTaskRuntime(
                redisClient,
                taskScore,
                properties.redisPrefix()
        );
    }

    @Bean(destroyMethod = "close")
    RedisTaskResourceCatalog taskResourceCatalog(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisTaskResourceCatalog(
                redisClient,
                properties.redisPrefix()
        );
    }

    @Bean(destroyMethod = "close")
    RedisWorkerScoreCore redisWorkerScoreCore(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisWorkerScoreCore(
                redisClient,
                properties.redisPrefix()
        );
    }

    @Bean(destroyMethod = "close")
    RedisWorkerRuntime workerRuntime(
            RedisClient redisClient,
            RedisWorkerScoreCore scoreCore,
            KernelRedisProperties properties
    ) {
        return new RedisWorkerRuntime(
                redisClient,
                scoreCore,
                properties.redisPrefix()
        );
    }

    @Bean(destroyMethod = "close")
    RedisWorkerResourceCatalog workerResourceCatalog(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisWorkerResourceCatalog(
                redisClient,
                properties.redisPrefix()
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
