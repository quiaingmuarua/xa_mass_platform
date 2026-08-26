package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.result.ResultRoutingApplication;
import com.xa.mass.kernel.result.ResultRoutingApplicationConfig;
import com.xa.mass.kernel.result.ResultRoutingPacer;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityAssemblyConfig;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchApplication;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityDispatchPacer;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultApplication;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityResultPacer;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.kernelredis.XaMassRedisProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KernelPacerProperties.class)
public class KernelPacerConfiguration {

    @Bean
    ResultRoutingPacer resultRoutingPacer(
            WorkerResultRuntime workerResults,
            TaskRuntime taskRuntime,
            TaskItemScoreBandCore itemScore,
            WorkerScoreCore workerScore
    ) {
        return new ResultRoutingPacer(
                workerResults,
                taskRuntime,
                itemScore,
                workerScore
        );
    }

    @Bean
    ResultRoutingApplication resultRoutingApplication(
            ResultRoutingPacer pacer
    ) {
        return new ResultRoutingApplication(pacer);
    }

    @Bean
    ResultRoutingApplicationConfig resultRoutingApplicationConfig(
            KernelPacerProperties properties
    ) {
        return ResultRoutingApplicationConfig.fromKernelConfigJson(
                readKernelConfig(properties)
        );
    }

    @Bean
    WorkerServiceabilityResultPacer workerServiceabilityResultPacer(
            WorkerServiceabilityRuntime runtime,
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScore
    ) {
        return new WorkerServiceabilityResultPacer(
                runtime,
                workerCatalog,
                workerScore
        );
    }

    @Bean
    WorkerServiceabilityResultApplication
            workerServiceabilityResultApplication(
                    WorkerServiceabilityResultPacer pacer
            ) {
        return new WorkerServiceabilityResultApplication(pacer);
    }

    @Bean
    WorkerServiceabilityAssemblyConfig workerServiceabilityAssemblyConfig(
                    KernelPacerProperties properties
            ) {
        return WorkerServiceabilityAssemblyConfig
                .fromKernelConfigJson(readKernelConfig(properties));
    }

    @Bean
    WorkerServiceabilityDispatchPacer workerServiceabilityDispatchPacer(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerScoreCore workerScore,
            WorkerResourceCatalog workerCatalog,
            WorkerServiceabilityRuntime runtime
    ) {
        return new WorkerServiceabilityDispatchPacer(
                taskScore,
                taskCatalog,
                workerScore,
                workerCatalog,
                runtime
        );
    }

    @Bean
    WorkerServiceabilityDispatchApplication
            workerServiceabilityDispatchApplication(
                    WorkerServiceabilityDispatchPacer pacer
            ) {
        return new WorkerServiceabilityDispatchApplication(pacer);
    }

    private static String readKernelConfig(
            KernelPacerProperties properties
    ) {
        if (!properties.enabled()) {
            return "{}";
        }
        Path workingDirectory = Path.of(properties.workingDirectory())
                .toAbsolutePath()
                .normalize();
        Path configured = Path.of(properties.configPath());
        Path configPath = configured.isAbsolute()
                ? configured.normalize()
                : workingDirectory.resolve(configured).normalize();
        try {
            return Files.readString(configPath, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "operation=kernelPacer.readConfig failed",
                    error
            );
        }
    }

    @Bean
    PythonKernelPacerProcess pythonKernelPacerProcess(
            KernelPacerProperties properties,
            XaMassRedisProperties redisProperties,
            JsonMapper json,
            WorkerServiceabilityAssemblyConfig serviceabilityConfig
    ) {
        return new PythonKernelPacerProcess(
                properties,
                redisProperties,
                json,
                serviceabilityConfig
        );
    }

    @Bean
    KernelPacerAssembly kernelPacerAssembly(
            KernelPacerProperties properties,
            PythonKernelPacerProcess pythonProcess,
            ResultRoutingApplication resultRouting,
            ResultRoutingApplicationConfig resultRoutingConfig,
            WorkerServiceabilityResultApplication serviceabilityResult,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            WorkerServiceabilityAssemblyConfig serviceabilityConfig
    ) {
        return new KernelPacerAssembly(
                properties,
                pythonProcess,
                resultRouting,
                resultRoutingConfig,
                serviceabilityResult,
                serviceabilityDispatch,
                serviceabilityConfig
        );
    }

    @Bean("kernel")
    HealthIndicator kernelHealthIndicator(KernelPacerAssembly assembly) {
        return new KernelPacerHealthIndicator(assembly);
    }
}
