package com.xa.mass.scenario.messages;

import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.workermatching.QualifiedCountryDefinition;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@Import(MessageController.class)
public class MessageCampaignsScenarioConfiguration {
    @Bean
    QualifiedCountryDefinition messageWorkerQualification() {
        return new QualifiedCountryDefinition(POOL, POOL_FUNCTION, PHONE_FUNCTION,
                REQUIRED_PROPERTY, REQUIRED_VALUE, COUNTRY_PROPERTY);
    }

    @Bean(destroyMethod = "close")
    MessageTaskService messageTasks(ProjectDirectory projects, com.xa.mass.server.project.ProjectTaskQueryService queries, TaskCreationService creation,
            TaskDataService data, TaskLifecycleService lifecycle,
            @Qualifier("scenarioWorkerGroup") String workerGroupId) {
        return new MessageTaskService(projects, queries, creation, data, lifecycle, workerGroupId);
    }
}
