package com.xa.mass.server.kernelpacer;

import com.xa.mass.server.kernelredis.XaMassRedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KernelPacerProperties.class)
public class KernelPacerConfiguration {

    @Bean
    PythonKernelPacerProcess pythonKernelPacerProcess(
            KernelPacerProperties properties,
            XaMassRedisProperties redisProperties,
            JsonMapper json
    ) {
        return new PythonKernelPacerProcess(
                properties,
                redisProperties,
                json
        );
    }

    @Bean
    KernelPacerAssembly kernelPacerAssembly(
            KernelPacerProperties properties,
            PythonKernelPacerProcess pythonProcess
    ) {
        return new KernelPacerAssembly(properties, pythonProcess);
    }

    @Bean("kernel")
    HealthIndicator kernelHealthIndicator(KernelPacerAssembly assembly) {
        return new KernelPacerHealthIndicator(assembly);
    }
}
