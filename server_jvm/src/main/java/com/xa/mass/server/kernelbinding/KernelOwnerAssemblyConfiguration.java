package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.redis.RedisTaskResourceCatalog;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import io.lettuce.core.RedisClient;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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

    @Bean
    HttpWorkerRuntime workerRuntime(
            PythonKernelHttpTransport transport
    ) {
        return new HttpWorkerRuntime(transport);
    }

    @Bean
    HttpWorkerResourceCatalog httpWorkerResourceCatalog(
            PythonKernelHttpTransport transport
    ) {
        return new HttpWorkerResourceCatalog(transport);
    }

    @Bean(destroyMethod = "close")
    RedisWorkerResourceCatalog redisWorkerResourceCatalog(
            RedisClient redisClient,
            KernelRedisProperties properties
    ) {
        return new RedisWorkerResourceCatalog(
                redisClient,
                properties.redisPrefix()
        );
    }

    @Bean
    @Primary
    WorkerResourceCatalog workerResourceCatalog(
            HttpWorkerResourceCatalog controlProvider,
            RedisWorkerResourceCatalog readProvider
    ) {
        return new AssembledWorkerResourceCatalog(
                controlProvider,
                readProvider
        );
    }

    @Bean
    TaskLifecycleCommands taskLifecycleCommands(
            PythonKernelHttpTransport transport
    ) {
        return new HttpTaskLifecycleCommands(transport);
    }

}
