package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import(AppCheckController.class)
public class AppCheckScenarioConfiguration {
    @Bean
    AppCheckAssignmentWindow appCheckAssignmentWindow() {
        return new AppCheckAssignmentWindow(60_000);
    }

    @Bean
    WorkerPropertyProjection appAAssignmentProjection(AppCheckAssignmentWindow window) {
        return assignmentProjection("app-a-sim", window);
    }

    @Bean
    WorkerPropertyProjection appBAssignmentProjection(AppCheckAssignmentWindow window) {
        return assignmentProjection("app-b-sim", window);
    }

    private static WorkerPropertyProjection assignmentProjection(String group, AppCheckAssignmentWindow window) {
        return new WorkerPropertyProjection(group, "extension.worker.app.registration.check",
                "worker.assigned", window::project);
    }

    @Bean(destroyMethod = "close")
    AppCheckTaskService appChecks(ProjectDirectory projects, ProjectTaskQueryService queries,
                                  TaskCreationService creation, TaskDataService data, TaskLifecycleService lifecycle) {
        return new AppCheckTaskService(projects, queries, creation, data, lifecycle);
    }
}
