package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.assembly.KernelPacerPolicyConfig;
import com.xa.mass.kernel.assignment.AssignmentDispatchApplication;
import com.xa.mass.kernel.assignment.AssignmentDispatchApplicationConfig;
import com.xa.mass.kernel.assignment.CandidateWarmupSchedule;
import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.TaskDispatchPacer;
import com.xa.mass.kernel.assignment.TaskItemDispatcher;
import com.xa.mass.kernel.assignment.TaskRunningActivationPacer;
import com.xa.mass.kernel.assignment.TaskWorkerAllocationPacer;
import com.xa.mass.kernel.assignment.WorkerCandidateAcquirer;
import com.xa.mass.kernel.assignment.WorkerCandidateMatcher;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
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
    KernelPacerPolicyConfig kernelPacerPolicyConfig(
            KernelPacerProperties properties
    ) {
        return KernelPacerPolicyConfig.fromJson(
                readKernelConfig(properties)
        );
    }

    @Bean
    ResultRoutingApplicationConfig resultRoutingApplicationConfig(
            KernelPacerPolicyConfig policy
    ) {
        return policy.resultRouting();
    }

    @Bean
    WorkerServiceabilityAssemblyConfig workerServiceabilityAssemblyConfig(
            KernelPacerPolicyConfig policy
    ) {
        return policy.workerServiceability();
    }

    @Bean
    AssignmentDispatchApplicationConfig assignmentDispatchApplicationConfig(
            KernelPacerPolicyConfig policy
    ) {
        return policy.assignmentDispatch();
    }

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

    @Bean
    WorkerCandidateMatcher workerCandidateMatcher(
            WorkerResourceCatalog workerCatalog
    ) {
        return new WorkerCandidateMatcher(workerCatalog);
    }

    @Bean
    WorkerCandidateAcquirer workerCandidateAcquirer(
            CandidateWorkerCache candidateCache,
            WorkerScoreCore workerScore,
            WorkerCandidateMatcher matcher,
            WorkerServiceabilityAssemblyConfig serviceability
    ) {
        return new WorkerCandidateAcquirer(
                candidateCache,
                workerScore,
                matcher,
                AssignmentDispatchApplicationConfig.WORKER_SCAN_LIMIT,
                serviceability.enabled()
                        ? serviceability.hotEligibilityFloorMillis()
                        : null
        );
    }

    @Bean
    TaskWorkerAllocationPacer taskWorkerAllocationPacer(
            CandidateWarmupSchedule warmups,
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWorkerCache candidateCache
    ) {
        return new TaskWorkerAllocationPacer(
                warmups,
                taskScore,
                taskCatalog,
                candidateAcquirer,
                candidateCache
        );
    }

    @Bean
    TaskRunningActivationPacer taskRunningActivationPacer(
            TaskScoreBandCore taskScore,
            TaskItemScoreBandCore itemScore,
            TaskResourceCatalog taskCatalog,
            CandidateWarmupSchedule warmups
    ) {
        return new TaskRunningActivationPacer(
                taskScore,
                itemScore,
                taskCatalog,
                warmups
        );
    }

    @Bean
    ResultContextCodec resultContextCodec() {
        return new ResultContextCodec();
    }

    @Bean
    TaskItemDispatcher taskItemDispatcher(
            TaskItemScoreBandCore itemScore,
            TaskRuntime taskRuntime,
            WorkerCandidateAcquirer candidateAcquirer,
            CandidateWarmupSchedule warmups,
            ResultContextCodec resultContextCodec
    ) {
        return new TaskItemDispatcher(
                itemScore,
                taskRuntime,
                candidateAcquirer,
                warmups,
                resultContextCodec
        );
    }

    @Bean
    TaskDispatchPacer taskDispatchPacer(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerCommandRuntime commandRuntime,
            TaskItemScoreBandCore itemScore,
            TaskItemDispatcher itemDispatcher
    ) {
        return new TaskDispatchPacer(
                taskScore,
                taskCatalog,
                commandRuntime,
                itemScore,
                itemDispatcher
        );
    }

    @Bean
    AssignmentDispatchApplication assignmentDispatchApplication(
            TaskWorkerAllocationPacer allocation,
            TaskRunningActivationPacer activation,
            TaskDispatchPacer dispatch
    ) {
        return new AssignmentDispatchApplication(
                allocation,
                activation,
                dispatch
        );
    }

    @Bean
    KernelPacerAssembly kernelPacerAssembly(
            KernelPacerProperties properties,
            KernelPacerPolicyConfig policy,
            ResultRoutingApplication resultRouting,
            WorkerServiceabilityResultApplication serviceabilityResult,
            WorkerServiceabilityDispatchApplication serviceabilityDispatch,
            AssignmentDispatchApplication assignmentDispatch
    ) {
        return new KernelPacerAssembly(
                properties,
                policy,
                resultRouting,
                serviceabilityResult,
                serviceabilityDispatch,
                assignmentDispatch
        );
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
