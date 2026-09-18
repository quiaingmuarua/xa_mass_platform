package com.xa.mass.scenario.messages;

import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.project.ProjectDirectory;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import(MessageController.class)
public class MessageCampaignsScenarioConfiguration {
    @Bean(destroyMethod = "close")
    MessageTaskService messageTasks(ProjectDirectory projects, com.xa.mass.server.project.ProjectTaskQueryService queries, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle,
            @Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new MessageTaskService(projects, queries, creation, data, lifecycle, workerGroupId);
    }
}
