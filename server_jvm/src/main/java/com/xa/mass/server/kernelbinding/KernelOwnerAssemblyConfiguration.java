package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.MappedWorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerPropertyIndex;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.kernel.worker.redis.RedisHashWorkerPropertyIndexProvider;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import io.lettuce.core.RedisClient;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerPropertyIndexProperties.class)
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
    PythonKernelHttpTransport pythonKernelHttpTransport(
            RestClient pythonKernelRestClient
    ) {
        return new PythonKernelHttpTransport(pythonKernelRestClient);
    }

    @Bean
    HttpTaskRuntime httpTaskRuntime(
            PythonKernelHttpTransport transport
    ) {
        return new HttpTaskRuntime(transport);
    }

    @Bean(destroyMethod = "close")
    RedisTaskRuntime redisTaskRuntime(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisTaskRuntime(
                redisClient,
                properties.redisPrefix()
        );
    }

    @Bean
    @Primary
    TaskRuntime taskRuntime(
            HttpTaskRuntime httpTaskRuntime,
            RedisTaskRuntime redisTaskRuntime
    ) {
        return new AssembledTaskRuntime(
                httpTaskRuntime,
                redisTaskRuntime
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

    @Bean(destroyMethod = "close")
    RedisHashWorkerPropertyIndexProvider
            redisHashWorkerPropertyIndexProvider(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisHashWorkerPropertyIndexProvider(
                redisClient,
                properties.redisPrefix()
        );
    }

    @Bean
    MappedWorkerPropertyIndexRuntime workerPropertyIndexRuntime(
            RedisWorkerResourceCatalog workerResourceCatalog,
            RedisHashWorkerPropertyIndexProvider redisProvider,
            WorkerPropertyIndexProperties properties
    ) {
        var indexes = new LinkedHashMap<String, WorkerPropertyIndex>();
        properties.registry().forEach(
                (propertyField, implementation) -> {
                    switch (implementation) {
                        case WorkerPropertyIndexProperties.REDIS_HASH -> indexes.put(
                                        propertyField,
                                        redisProvider.create(propertyField)
                                );
                        default -> throw new IllegalArgumentException(
                                "Unknown Worker property index implementation: "
                                        + implementation
                        );
                    }
                }
        );
        return new MappedWorkerPropertyIndexRuntime(
                workerResourceCatalog,
                indexes
        );
    }

    @Bean
    TaskLifecycleCommands taskLifecycleCommands(
            PythonKernelHttpTransport transport
    ) {
        return new HttpTaskLifecycleCommands(transport);
    }

    @Bean
    TaskDispatchWakeCommands taskDispatchWakeCommands(
            PythonKernelHttpTransport transport
    ) {
        return new HttpTaskDispatchWakeCommands(transport);
    }

}
