package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import(AppCheckController.class)
public class AppCheckScenarioConfiguration {
    @Bean(destroyMethod = "close")
    AppCheckTaskService appChecks(ProjectDirectory projects, ProjectTaskQueryService queries,
                                  TaskCreationService creation, TaskDataService data, TaskLifecycleService lifecycle) {
        return new AppCheckTaskService(projects, queries, creation, data, lifecycle);
    }
}
