package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KernelPacerProperties.class)
public class KernelPacerConfiguration {

    @Bean
    KernelPacerRuntime kernelPacerRuntime(
            KernelPacerProperties properties,
            WorkerResultRuntime workerResults,
            TaskRuntime taskRuntime,
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerServiceabilityRuntime serviceability,
            CandidateWorkerCache candidateCache,
            CandidateWarmupSchedule warmups
    ) {
        return KernelPacerRuntime.assemble(
                readKernelConfig(properties),
                properties.shutdownTimeout(),
                workerResults,
                taskRuntime,
                taskScores,
                itemScores,
                taskCatalog,
                workerScores,
                workerCatalog,
                workerCommands,
                serviceability,
                candidateCache,
                warmups
        );
    }

    @Bean
    KernelPacerAssembly kernelPacerAssembly(
            KernelPacerProperties properties,
            KernelPacerRuntime runtime
    ) {
        return new KernelPacerAssembly(properties, runtime);
    }

    @Bean("kernel")
    HealthIndicator kernelHealthIndicator(KernelPacerAssembly assembly) {
        return new KernelPacerHealthIndicator(assembly);
    }

    private static String readKernelConfig(
            KernelPacerProperties properties
    ) {
        if (!properties.enabled()) {
            return "{}";
        }
        Path configured = Path.of(properties.configPath());
        Path configPath = configured.isAbsolute()
                ? configured.normalize()
                : Path.of("").toAbsolutePath().resolve(configured).normalize();
        try {
            return Files.readString(configPath, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "operation=kernelPacer.readConfig failed",
                    error
            );
        }
    }
}
