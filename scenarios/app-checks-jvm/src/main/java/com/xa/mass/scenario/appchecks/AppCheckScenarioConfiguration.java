package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectWorkerRequirements;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import com.xa.mass.workermatching.FixedWindowPoolDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;
import java.util.*;
import static com.xa.mass.scenario.appchecks.AppCheckWorkerSupply.*;

@Configuration(proxyBeanMethods = false)
@Import(AppCheckController.class)
@EnableConfigurationProperties(AppCheckConfigurationProperties.class)
public class AppCheckScenarioConfiguration {
    @Bean AppCheckContract registrationContract() { return AppCheckContracts.registration(); }
    @Bean AppCheckContract simulatedRegistrationContract() { return AppCheckContracts.simulatedRegistration(); }
    @Bean AppCheckContract originalCarrierContract() { return AppCheckContracts.originalCarrier(); }
    @Bean AppCheckContract uint32PrimeContract() { return AppCheckContracts.uint32Prime(); }
    @Bean AppCheckContract md5PrefixContract() { return AppCheckContracts.md5Prefix(); }
    @Bean AppCheckContract originalCarrierBatchContract() { return AppCheckContracts.originalCarrierBatch(); }
    @Bean AppCheckContract md5PrefixBatchContract() { return AppCheckContracts.md5PrefixBatch(); }
    @Bean AppCheckContracts appCheckContracts(List<AppCheckContract> definitions) { return new AppCheckContracts(definitions); }

    @Bean(destroyMethod = "close")
    AppCheckConfigurationStore appCheckConfigurationStore(AppCheckConfigurationProperties properties, ResourceLoader resources, AppCheckContracts contracts) {
        return new AppCheckConfigurationStore(Path.of(properties.configuration().path()), () -> {
            try (var input = resources.getResource(properties.configuration().seed()).getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
        }, contracts);
    }

    @Bean
    AppCheckConfigurationStore.Snapshot appCheckEffectiveConfiguration(AppCheckConfigurationStore store) {
        return store.effective();
    }

    @Bean
    ProjectDefinition appCheckProject(AppCheckConfigurationStore.Snapshot config) {
        return new ProjectDefinition("app-checks", config.configuration().workerGroupIds());
    }

    @Bean
    ProjectWorkerRequirements appCheckRequirements(AppCheckConfigurationStore.Snapshot config) {
        var groups = new LinkedHashMap<String, ProjectWorkerRequirements.Resources>();
        for (var app : config.configuration().checks().values()) {
            var pools = new LinkedHashSet<String>();
            app.execution().refill().forEach(target -> pools.add(target.poolName()));
            var events = new LinkedHashSet<>(Set.of(app.execution().eventCode()));
            var functions = new LinkedHashSet<>(Set.of(app.execution().workerSelector().executorName()));
            var existing = groups.get(app.workerGroupId());
            if (existing != null) {
                events.addAll(existing.eventCodes()); pools.addAll(existing.pools()); functions.addAll(existing.functions());
            }
            groups.put(app.workerGroupId(), new ProjectWorkerRequirements.Resources(events, pools, functions));
        }
        return new ProjectWorkerRequirements("app-checks", groups);
    }

    @Bean
    FixedWindowPoolDefinition appCheckWindowPool(AppCheckConfigurationStore.Snapshot config) {
        return new FixedWindowPoolDefinition(POOL, FUNCTION, LAST, COUNT, config.configuration().poolGroups());
    }

    @Bean
    WorkerPropertyProjection appCheckAssignmentProjections(AppCheckConfigurationStore.Snapshot config) {
        var events = new LinkedHashMap<String, Set<String>>();
        config.configuration().checks().values().stream()
                .filter(check -> FUNCTION.equals(check.execution().workerSelector().executorName()))
                .forEach(check -> events.computeIfAbsent(check.workerGroupId(), ignored -> new LinkedHashSet<>())
                        .add(check.execution().eventCode()));
        var projections = new LinkedHashMap<String, List<WorkerPropertyProjection.Projection>>();
        events.forEach((group, names) -> {
            var window = new AppCheckAssignmentWindow(config.configuration().poolGroups().get(group).windowMillis());
            projections.put(group, names.stream().map(event -> new WorkerPropertyProjection.Projection(event, window::project)).toList());
        });
        return new WorkerPropertyProjection("worker.assigned", projections);
    }

    @Bean(destroyMethod = "close")
    AppCheckTaskService appChecks(ProjectDirectory projects, ProjectTaskQueryService queries,
                                  TaskCreationService creation, TaskDataService data,
                                  OperationGuard operations, TaskResultsExportService exports,
                                  AppCheckConfigurationStore.Snapshot config, AppCheckContracts contracts) {
        return new AppCheckTaskService(projects, queries, creation, data, operations, exports, config.configuration(), contracts);
    }
}
