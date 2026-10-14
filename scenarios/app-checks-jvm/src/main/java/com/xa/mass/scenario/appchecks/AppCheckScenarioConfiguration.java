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
import java.util.function.BiFunction;
import static com.xa.mass.scenario.appchecks.AppCheckWorkerSupply.*;

@Configuration(proxyBeanMethods = false)
@Import(AppCheckController.class)
@EnableConfigurationProperties(AppCheckStorageProperties.class)
public class AppCheckScenarioConfiguration {
    @Bean(destroyMethod = "close")
    AppCheckConfigurationStore appCheckConfigurationStore(AppCheckStorageProperties properties, ResourceLoader resources) {
        return new AppCheckConfigurationStore(Path.of(properties.storage().path()), () -> {
            try (var input = resources.getResource(properties.storage().seed()).getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException failure) { throw new UncheckedIOException(failure); }
        });
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
        return new ProjectWorkerRequirements("app-checks", config.configuration().workerGroupIds(),
                Set.of(EVENT), Set.of(POOL), Set.of(FUNCTION));
    }

    @Bean
    FixedWindowPoolDefinition appCheckWindowPool(AppCheckConfigurationStore.Snapshot config) {
        return new FixedWindowPoolDefinition(POOL, FUNCTION, LAST, COUNT, config.configuration().poolGroups());
    }

    @Bean
    WorkerPropertyProjection appCheckAssignmentProjections(AppCheckConfigurationStore.Snapshot config) {
        var projections = new LinkedHashMap<String, BiFunction<Map<String, Object>, List<Long>, Map<String, Object>>>();
        config.configuration().applications().forEach(app -> {
            var window = new AppCheckAssignmentWindow(config.configuration().poolGroups().get(app.workerGroupId()).windowMillis());
            projections.put(app.workerGroupId(), window::project);
        });
        return new WorkerPropertyProjection(EVENT, "worker.assigned", projections);
    }

    @Bean(destroyMethod = "close")
    AppCheckTaskService appChecks(ProjectDirectory projects, ProjectTaskQueryService queries,
                                  TaskCreationService creation, TaskDataService data,
                                  OperationGuard operations, TaskResultsExportService exports,
                                  AppCheckConfigurationStore.Snapshot config) {
        return new AppCheckTaskService(projects, queries, creation, data, operations, exports, config.configuration());
    }
}
