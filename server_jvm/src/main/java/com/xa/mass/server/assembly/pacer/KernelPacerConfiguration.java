package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
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
            XaMassRedisProperties redisProperties,
            TaskResultRuntime taskResults,
            TaskRuntime taskRuntime,
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerServiceabilityRuntime serviceability,
            CandidateWorkerCache candidateCache,
            WorkerMatchQueue workerMatchQueue
    ) {
        validatePresetScope(properties.preset(), redisProperties.scope());
        return KernelPacerRuntime.assemble(
                properties.preset(),
                properties.shutdownTimeout(),
                taskResults,
                taskRuntime,
                taskScores,
                itemScores,
                taskCatalog,
                workerScores,
                workerCatalog,
                workerCommands,
                serviceability,
                candidateCache,
                workerMatchQueue
        );
    }

    static void validatePresetScope(
            KernelPacerRuntime.PolicyPreset preset,
            String redisScope
    ) {
        if (preset == KernelPacerRuntime.PolicyPreset.RUNTIME_BOUNDARY_PROOF
                && !redisScope.startsWith("test_")) {
            throw new IllegalStateException(
                    "operation=kernelPacer.validatePresetScope "
                            + "RUNTIME_BOUNDARY_PROOF requires a test_* "
                            + "Redis scope"
            );
        }
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
}
