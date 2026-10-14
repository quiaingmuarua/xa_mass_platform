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
@EnableConfigurationProperties(AppCheckStorageProperties.class)
public class AppCheckScenarioConfiguration {
    @Bean AppCheckContract registrationContract() { return AppCheckContracts.registration(); }
    @Bean AppCheckContract simulatedRegistrationContract() { return AppCheckContracts.simulatedRegistration(); }
    @Bean AppCheckContracts appCheckContracts(List<AppCheckContract> definitions) { return new AppCheckContracts(definitions); }

    @Bean(destroyMethod = "close")
    AppCheckConfigurationStore appCheckConfigurationStore(AppCheckStorageProperties properties, ResourceLoader resources, AppCheckContracts contracts) {
        return new AppCheckConfigurationStore(Path.of(properties.storage().path()), () -> {
            try (var input = resources.getResource(properties.storage().seed()).getInputStream()) {
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
        for (var app : config.configuration().applications().values()) {
            var pools = new LinkedHashSet<String>();
            app.execution().refill().forEach(target -> pools.add(target.poolName()));
            groups.put(app.workerGroupId(), new ProjectWorkerRequirements.Resources(Set.of(app.execution().eventCode()),
                    pools, Set.of(app.execution().workerSelector().executorName())));
        }
        return new ProjectWorkerRequirements("app-checks", groups);
    }

    @Bean
    FixedWindowPoolDefinition appCheckWindowPool(AppCheckConfigurationStore.Snapshot config) {
        return new FixedWindowPoolDefinition(POOL, FUNCTION, LAST, COUNT, config.configuration().poolGroups());
    }

    @Bean
    WorkerPropertyProjection appCheckAssignmentProjections(AppCheckConfigurationStore.Snapshot config) {
        var projections = new LinkedHashMap<String, WorkerPropertyProjection.Projection>();
        config.configuration().applications().values().forEach(app -> {
            var limit = config.configuration().poolGroups().get(app.workerGroupId());
            if (limit != null) {
                var window = new AppCheckAssignmentWindow(limit.windowMillis());
                projections.put(app.workerGroupId(), new WorkerPropertyProjection.Projection(app.execution().eventCode(), window::project));
            }
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
