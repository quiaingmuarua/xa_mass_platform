package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import com.xa.mass.workermatching.FixedWindowPoolDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import static com.xa.mass.scenario.appchecks.AppCheckWorkerSupply.*;

@Configuration(proxyBeanMethods = false)
@Import(AppCheckController.class)
@EnableConfigurationProperties(AppCheckPoolProperties.class)
public class AppCheckScenarioConfiguration {
    @Bean
    FixedWindowPoolDefinition appCheckWindowPool(AppCheckPoolProperties config) {
        return new FixedWindowPoolDefinition(POOL, FUNCTION, LAST, COUNT, config.groups());
    }

    @Bean
    WorkerPropertyProjection appAAssignmentProjection(AppCheckPoolProperties config) {
        return assignmentProjection(APPS.get("app-a"), config);
    }

    @Bean
    WorkerPropertyProjection appBAssignmentProjection(AppCheckPoolProperties config) {
        return assignmentProjection(APPS.get("app-b"), config);
    }

    private static WorkerPropertyProjection assignmentProjection(String group, AppCheckPoolProperties config) {
        var window = new AppCheckAssignmentWindow(config.groups().get(group).windowMillis());
        return new WorkerPropertyProjection(group, EVENT,
                "worker.assigned", window::project);
    }

    @Bean(destroyMethod = "close")
    AppCheckTaskService appChecks(ProjectDirectory projects, ProjectTaskQueryService queries,
                                  TaskCreationService creation, TaskDataService data, TaskLifecycleService lifecycle,
                                  OperationGuard operations, TaskResultsExportService exports) {
        return new AppCheckTaskService(projects, queries, creation, data, lifecycle, operations, exports);
    }
}
