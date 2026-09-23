package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.TaskEvidenceRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.server.task.TaskItemOutcomeProperties;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import com.xa.mass.workermatching.WorkerProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({KernelPacerProperties.class, TaskItemOutcomeProperties.class})
public class KernelPacerConfiguration {

    @Bean
    WorkerObservationConsumer workerObservationConsumer(List<WorkerPropertyProjection> projections,
            ObjectProvider<WorkerProperties> properties, ObjectProvider<WorkerResourceCommandService> commands) {
        var running = new AtomicBoolean();
        var failures = new AtomicLong();
        var routes = new LinkedHashMap<String, Map<EventKey, List<FunctionHandler>>>();
        if (!projections.isEmpty()) {
            var handler = new PlatformPropertiesHandler(projections, properties.getObject(), commands.getObject(),
                    running::get, failures::addAndGet);
            for (var projection : projections) {
                routes.computeIfAbsent(projection.workerGroupId(), ignored -> new LinkedHashMap<>())
                        .put(new EventKey(projection.messageEventName(), projection.observationEventName()), List.of(handler));
            }
        }
        return new WorkerObservationConsumer(routes, running, failures);
    }

    @Bean
    KernelPacerRuntime kernelPacerRuntime(
            KernelPacerProperties properties,
            XaMassRedisProperties redisProperties,
            TaskEvidenceRuntime taskEvidence,
            TaskRuntime taskRuntime,
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerServiceabilityRuntime serviceability,
            WorkerMatching workerMatching,
            ObjectProvider<WorkerObservationConsumer> observations
    ) {
        validatePresetScope(properties.preset(), redisProperties.scope());
        var consumer = observations.getIfAvailable();
        return KernelPacerRuntime.assemble(
                properties.preset(),
                properties.shutdownTimeout(),
                properties.assignmentBatchLimit(),
                TaskItemOutcomeProperties.FAILED_TAG,
                TaskItemOutcomeProperties.SUCCEEDED_TAG,
                taskEvidence,
                taskRuntime,
                taskScores,
                itemScores,
                taskCatalog,
                workerScores,
                workerCatalog,
                workerCommands,
                serviceability,
                workerMatching,
                consumer != null && consumer.enabled() ? consumer::accept : ignored -> {}
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
